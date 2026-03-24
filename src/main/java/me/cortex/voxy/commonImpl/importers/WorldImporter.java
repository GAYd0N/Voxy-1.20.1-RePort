package me.cortex.voxy.commonImpl.importers;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.lwjgl.system.MemoryUtil;

import com.mojang.serialization.Codec;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.voxelization.ArrayLightingSupplier;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.importers.IDataImporter.ICompletionCallback;
import me.cortex.voxy.commonImpl.importers.IDataImporter.IUpdateCallback;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.PalettedContainerRO.PackedData;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

public class WorldImporter implements IDataImporter {
    private static final int SECTION_STATE_ENTRY_COUNT = 16 * 16 * 16;
    private static final Pattern REGION_FILE_NAME_PATTERN = Pattern.compile("^r\\.-?\\d+\\.-?\\d+\\.mca$");
    private static final int COMPACT_BLOCK_STATES_LENGTH_5BIT = 320;
    private static final int COMPACT_BLOCK_STATES_LENGTH_6BIT = 384;
    private static final int RECOVERY_DETAIL_LOG_LIMIT = 8;
    private static final int RECOVERY_SUMMARY_LOG_INTERVAL = 64;

    private final WorldEngine world;
    private final PalettedContainerRO<Holder<Biome>> defaultBiomeProvider;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final Codec<PalettedContainer<BlockState>> blockStateCodec;
    private final AtomicInteger estimatedTotalChunks = new AtomicInteger();//Slowly converges to the true value
    private final AtomicInteger totalChunks = new AtomicInteger();
    private final AtomicInteger chunksProcessed = new AtomicInteger();
    private final AtomicInteger recoveredCompactStorageCount = new AtomicInteger();

    private final ConcurrentLinkedDeque<Runnable> jobQueue = new ConcurrentLinkedDeque<>();
    private final Service service;

    private volatile boolean isRunning;

    public WorldImporter(WorldEngine worldEngine, Level mcWorld, ServiceManager sm, BooleanSupplier runChecker) {
        this.world = worldEngine;
        this.service = sm.createService(() -> new Pair<>(this::runQueuedJob, () -> {}), 3, "World importer", runChecker);

        var biomeRegistry = mcWorld.registryAccess().registryOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getHolder(Biomes.PLAINS).orElseThrow();
        this.defaultBiomeProvider = new PalettedContainerRO<>() {
            @Override
            public Holder<Biome> get(int x, int y, int z) {
                return defaultBiome;
            }

            @Override
            public void getAll(Consumer<Holder<Biome>> action) {

            }

            @Override
            public void write(FriendlyByteBuf buf) {

            }

            @Override
            public int getSerializedSize() {
                return 0;
            }

            @Override
            public boolean maybeHas(Predicate<Holder<Biome>> predicate) {
                return false;
            }

            @Override
            public void count(PalettedContainer.CountConsumer<Holder<Biome>> counter) {

            }

            @Override
            public PalettedContainer<Holder<Biome>> recreate() {
                return null;
            }

            @Override
            public PackedData<Holder<Biome>> pack(IdMap<Holder<Biome>> idMap, PalettedContainer.Strategy strategy) {
                return null;
            }
        };

        this.biomeCodec = PalettedContainer.codecRO(biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
        this.blockStateCodec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());;
    }


    @Override
    public void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback) {
        if (this.isRunning) {
            throw new IllegalStateException();
        }
        if (this.worker == null) {//Can happen if no files
            completionCallback.onCompletion(0);
            return;
        }
        this.isRunning = true;
        this.world.acquireRef();
        this.updateCallback = updateCallback;
        this.completionCallback = completionCallback;
        this.worker.start();
    }

    @Override
    public WorldEngine getEngine() {
        return this.world;
    }

    private final AtomicBoolean isShutdown = new AtomicBoolean();
    public void shutdown() {
        if (this.isShutdown.getAndSet(true)) {
            return;
        }
        this.isRunning = false;
        if (this.worker != null) {
            try {
                this.worker.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (this.service.isLive()) {
            this.world.releaseRef();
            this.service.shutdown();
        }
        //Free all the remaining entries by running the lambda
        Runnable job;
        while ((job = this.jobQueue.poll()) != null) {
            job.run();
        }
    }

    private void runQueuedJob() {
        Runnable job = this.jobQueue.poll();
        if (job != null) {
            job.run();
        }
    }

    private boolean shouldAbortImport() {
        if (!this.isRunning || this.isShutdown.get()) {
            return true;
        }
        if (!this.service.isLive() || !this.world.isLive()) {
            return true;
        }
        return this.world.instanceIn != null && !this.world.instanceIn.isRunning();
    }

    private interface IImporterMethod <T> {
        void importRegion(T file) throws Exception;
    }

    private volatile Thread worker;
    private IUpdateCallback updateCallback;
    private ICompletionCallback completionCallback;
    public void importRegionDirectoryAsync(File directory) {
        var files = directory.listFiles((dir, name) -> {
            if (!isValidRegionFileName(name)) {
                if (shouldLogUnknownRegionFile(name)) {
                    Logger.warn("Skipping non-region file: " + name);
                }
                return false;
            }
            return true;
        });
        if (files == null) {
            return;
        }
        Arrays.sort(files, File::compareTo);
        this.importRegionsAsync(files, this::importRegionFile);
    }

    public void importZippedRegionDirectoryAsync(File zip, String innerDirectory) {
        try {
            innerDirectory = innerDirectory.replace("\\\\", "\\").replace("\\", "/");
            var file = new ZipFile(zip);
            ArrayList<ZipArchiveEntry> regions = new ArrayList<>();
            for (var e = file.getEntries(); e.hasMoreElements();) {
                var entry = e.nextElement();
                if (entry.isDirectory()||!entry.getName().startsWith(innerDirectory)) {
                    continue;
                }
                var parts = entry.getName().split("/");
                var name = parts[parts.length-1];
                if (!isValidRegionFileName(name)) {
                    if (shouldLogUnknownRegionFile(name)) {
                        Logger.warn("Skipping non-region file in zip: " + name);
                    }
                    continue;
                }
                regions.add(entry);
            }
            this.importRegionsAsync(regions.toArray(ZipArchiveEntry[]::new), (entry)->{
                if (entry.getSize() == 0) {
                    return;
                }
                var buf = new MemoryBuffer(entry.getSize());
                try (var channel = Channels.newChannel(file.getInputStream(entry))) {
                    if (channel.read(buf.asByteBuffer()) != buf.size) {
                        buf.free();
                        throw new IllegalStateException("Could not read full zip entry");
                    }
                }

                var parts = entry.getName().split("/");
                var name = parts[parts.length-1];
                var sections = name.split("\\.");

                try {
                    this.importRegion(buf, Integer.parseInt(sections[1]), Integer.parseInt(sections[2]));
                } catch (NumberFormatException e) {
                    Logger.error("Invalid format for region position, x: \""+sections[1]+"\" z: \"" + sections[2] + "\" skipping region");
                }
                buf.free();
            });
            file.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private <T> void importRegionsAsync(T[] regionFiles, IImporterMethod<T> importer) {
        this.totalChunks.set(0);
        this.estimatedTotalChunks.set(0);
        this.chunksProcessed.set(0);
        this.worker = new Thread(() -> {
            this.estimatedTotalChunks.addAndGet(regionFiles.length*1024);
            for (var file : regionFiles) {
                if (this.shouldAbortImport()) {
                    this.finishWorkerEarly();
                    return;
                }
                this.estimatedTotalChunks.addAndGet(-1024);
                try {
                    importer.importRegion(file);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                while ((this.totalChunks.get()-this.chunksProcessed.get() > 10_000) && !this.shouldAbortImport()) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (this.shouldAbortImport()) {
                    this.finishWorkerEarly();
                    return;
                }
            }
            if (this.shouldAbortImport()) {
                this.finishWorkerEarly();
                return;
            }
            this.service.blockTillEmpty();
            while (this.chunksProcessed.get() != this.totalChunks.get() && !this.shouldAbortImport()) {
                Thread.yield();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            if (this.shouldAbortImport()) {
                this.finishWorkerEarly();
                return;
            }
            if (!this.isShutdown.getAndSet(true)) {
                this.worker = null;
                this.service.shutdown();
                this.world.releaseRef();
            }
            this.completionCallback.onCompletion(this.totalChunks.get());
        });
        this.worker.setName("World importer");
    }

    private void finishWorkerEarly() {
        if (this.service.isLive()) {
            this.service.blockTillEmpty();
        }
        this.completionCallback.onCompletion(this.totalChunks.get());
        this.worker = null;
    }

    public boolean isBusy() {
        return this.isRunning || this.worker != null;
    }

    public boolean isRunning() {
        return this.isRunning || (this.worker != null && this.worker.isAlive());
    }

    private void importRegionFile(File file) throws IOException {
        var name = file.getName();
        if (!isValidRegionFileName(name)) {
            if (shouldLogUnknownRegionFile(name)) {
                Logger.warn("Skipping non-region file: " + name);
            }
            throw new IllegalStateException();
        }
        var sections = name.split("\\.");
        int rx = 0;
        int rz = 0;
        try {
            rx = Integer.parseInt(sections[1]);
            rz = Integer.parseInt(sections[2]);
        } catch (NumberFormatException e) {
            Logger.error("Invalid format for region position, x: \""+sections[1]+"\" z: \"" + sections[2] + "\" skipping region");
            return;
        }
        try (var fileStream = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            if (fileStream.size() == 0) {
                return;
            }
            var fileData = new MemoryBuffer(fileStream.size());
            if (fileStream.read(fileData.asByteBuffer(), 0) < 8192) {
                fileData.free();
                Logger.warn("Header of region file invalid");
                return;
            }
            this.importRegion(fileData, rx, rz);
            fileData.free();
        }
    }


    private void importRegion(MemoryBuffer regionFile, int x, int z) {
        //Find and load all saved chunks
        final long fileSize = regionFile.size;
        final long baseAddress = regionFile.address;
        if (fileSize < 8192) {//File not big enough
            Logger.warn("Header of region file invalid");
            return;
        }
        for (int idx = 0; idx < 1024; idx++) {
            if (this.shouldAbortImport()) {
                break;
            }
            int sectorMeta = Integer.reverseBytes(MemoryUtil.memGetInt(baseAddress + idx * 4L));//Assumes little endian
            if (sectorMeta == 0) {
                //Empty chunk
                continue;
            }
            int sectorStart = sectorMeta>>>8;
            int sectorCount = sectorMeta&((1<<8)-1);

            if (sectorCount == 0) {
                continue;
            }

            //TODO: create memory copy for each section
            long sectorEnd = (sectorStart + (long) sectorCount) * 4096L;
            if (fileSize < sectorEnd) {
                Logger.warn("Cannot access chunk sector as it goes out of bounds. start bytes: " + (sectorStart*4096L) + " sector count: " + sectorCount + " fileSize: " + fileSize);
                continue;
            }

            {
                long base = baseAddress + sectorStart * 4096L;
                int chunkLen = sectorCount * 4096;
                int m = Integer.reverseBytes(MemoryUtil.memGetInt(base));
                byte b = MemoryUtil.memGetByte(base + 4L);
                if (m == 0) {
                    Logger.error("Chunk is allocated, but stream is missing");
                } else {
                    int n = m - 1;
                    if (fileSize < (n + sectorStart*4096L)) {
                        Logger.warn("Chunk stream to small");
                    } else if ((b & 128) != 0) {
                        if (n != 0) {
                            Logger.error("Chunk has both internal and external streams");
                        }
                        Logger.error("Chunk has external stream which is not supported");
                    } else if (n > chunkLen-5) {
                        Logger.error("Chunk stream is truncated: expected "+n+" but read " + (chunkLen-5));
                    } else if (n < 0) {
                        Logger.error("Declared size of chunk is negative");
                    } else {
                        var data = new MemoryBuffer(n).cpyFrom(base + 5);
                        Runnable importTask = () -> {
                            if (this.shouldAbortImport()) {
                                this.totalChunks.decrementAndGet();
                                this.estimatedTotalChunks.decrementAndGet();
                                data.free();
                                return;
                            }
                            try {
                                try (var decompressedData = this.decompress(b, data)) {
                                    if (decompressedData == null) {
                                        Logger.error("Error decompressing chunk data");
                                    } else {
                                        var nbt = NbtIo.read(decompressedData);
                                        this.importChunkNBT(nbt, x, z);
                                    }
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                data.free();
                            }
                        };
                        this.jobQueue.add(importTask);
                        this.totalChunks.incrementAndGet();
                        this.estimatedTotalChunks.incrementAndGet();
                        if (!this.service.tryExecute() && this.jobQueue.remove(importTask)) {
                            this.totalChunks.decrementAndGet();
                            this.estimatedTotalChunks.decrementAndGet();
                            data.free();
                        }
                    }
                }
            }
        }
    }

    private static InputStream createInputStream(MemoryBuffer data) {
        return new InputStream() {
            private long offset = 0;
            @Override
            public int read() {
                return MemoryUtil.memGetByte(data.address + (this.offset++)) & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                len = Math.min(len, this.available());
                if (len == 0) {
                    return -1;
                }
                UnsafeUtil.memcpy(data.address+this.offset, len, b, off); this.offset+=len;
                return len;
            }

            @Override
            public int available() {
                return (int) (data.size-this.offset);
            }
        };
    }

    private DataInputStream decompress(byte flags, MemoryBuffer stream) throws IOException {
        RegionFileVersion chunkStreamVersion = RegionFileVersion.fromId(flags);
        if (chunkStreamVersion == null) {
            Logger.error("Chunk has invalid chunk stream version");
            return null;
        } else {
            return new DataInputStream(chunkStreamVersion.wrap(createInputStream(stream)));
        }
    }

    private void importChunkNBT(CompoundTag chunk, int regionX, int regionZ) {
        if (this.shouldAbortImport()) {
            this.totalChunks.decrementAndGet();
            this.estimatedTotalChunks.decrementAndGet();
            return;
        }
        CompoundTag chunkData = this.resolveChunkData(chunk);
        ListTag sections = this.getSectionList(chunkData);
        if (sections == null || sections.isEmpty()) {
            this.totalChunks.decrementAndGet();
            return;
        }

        // Keep the previous status gate for known statuses, but allow older/unknown layouts through.
        if (chunkData.contains("Status", Tag.TAG_STRING)) {
            var status = ChunkStatus.byName(chunkData.getString("Status"));
            if (status != null && status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) {
                this.totalChunks.decrementAndGet();
                return;
            }
        }

        try {
            int x = chunkData.getInt("xPos");
            int z = chunkData.getInt("zPos");
            if (x>>5 != regionX || z>>5 != regionZ) {
                Logger.error("Chunk position is not located in correct region, expected: (" + regionX + ", " + regionZ+"), got: " + "(" + (x>>5) + ", " + (z>>5)+"), importing anyway");
            }

            for (var sectionE : sections) {
                var section = (CompoundTag) sectionE;
                int y = section.getInt("Y");
                this.importSectionNBT(x, y, z, section);
            }
        } catch (Exception e) {
            Logger.error("Exception importing world chunk:",e);
        }

        this.updateCallback.onUpdate(this.chunksProcessed.incrementAndGet(), this.estimatedTotalChunks.get());
    }

    private CompoundTag resolveChunkData(CompoundTag chunk) {
        if (chunk.contains("Level", Tag.TAG_COMPOUND)) {
            return chunk.getCompound("Level");
        }
        return chunk;
    }

    private ListTag getSectionList(CompoundTag chunkData) {
        if (chunkData.contains("sections", Tag.TAG_LIST)) {
            return chunkData.getList("sections", Tag.TAG_COMPOUND);
        }
        if (chunkData.contains("Sections", Tag.TAG_LIST)) {
            return chunkData.getList("Sections", Tag.TAG_COMPOUND);
        }
        return null;
    }

    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private void importSectionNBT(int x, int y, int z, CompoundTag section) {
        PalettedContainer<BlockState> blockStates;
        PalettedContainerRO<Holder<Biome>> biomes = this.defaultBiomeProvider;

        CompoundTag blockStatesTag = section.getCompound("block_states");
        if (!blockStatesTag.isEmpty()) {
            blockStates = this.decodeBlockStatesWithCompactFallback(blockStatesTag, x, y, z, false);
            if (blockStates == null) {
                //TODO: if its only partial, it means should try to upgrade the nbt format with datafixerupper probably
                return;
            }

            var optBiomes = section.getCompound("biomes");
            if (!optBiomes.isEmpty()) {
                biomes = this.biomeCodec.parse(NbtOps.INSTANCE, optBiomes).result().orElse(this.defaultBiomeProvider);
            }
        } else if (section.contains("Palette", Tag.TAG_LIST)) {
            ListTag legacyPalette = section.getList("Palette", Tag.TAG_COMPOUND);
            if (legacyPalette.isEmpty()) {
                return;
            }

            CompoundTag legacyStates = new CompoundTag();
            legacyStates.put("palette", legacyPalette.copy());
            if (section.contains("BlockStates", Tag.TAG_LONG_ARRAY)) {
                long[] data = section.getLongArray("BlockStates");
                if (data.length != 0) {
                    legacyStates.putLongArray("data", data);
                }
            }

            blockStates = this.decodeBlockStatesWithCompactFallback(legacyStates, x, y, z, true);
            if (blockStates == null) {
                return;
            }
        } else {
            return;
        }
        byte[] blockLightData = section.getByteArray("BlockLight");
        byte[] skyLightData = section.getByteArray("SkyLight");

        byte[] bl = blockLightData.length == 2048 ? blockLightData : null;
        byte[] sl = skyLightData.length == 2048 ? skyLightData : null;

        ILightingSupplier lightSupplier = new ArrayLightingSupplier(bl, sl);
        VoxelizedSection csec = WorldConversionFactory.convert(
                SECTION_CACHE.get().setPosition(x, y, z),
                this.world.getMapper(),
                blockStates,
                biomes,
                lightSupplier
        );

        WorldConversionFactory.mipSection(csec, this.world.getMapper());
        WorldUpdater.insertUpdate(this.world, csec);
    }

    private PalettedContainer<BlockState> decodeBlockStatesWithCompactFallback(CompoundTag blockStatesTag, int x, int y, int z, boolean legacyPath) {
        if (!blockStatesTag.contains("data", Tag.TAG_LONG_ARRAY)) {
            return this.blockStateCodec.parse(NbtOps.INSTANCE, blockStatesTag).resultOrPartial(Logger::error).orElse(null);
        }

        long[] raw = blockStatesTag.getLongArray("data");
        if (isKnownCompactStorageLength(raw.length)) {
            PalettedContainer<BlockState> recovered = this.tryDecodeWithRepackedStorage(blockStatesTag, raw, x, y, z, legacyPath);
            if (recovered != null) {
                return recovered;
            }
            return this.blockStateCodec.parse(NbtOps.INSTANCE, blockStatesTag).resultOrPartial(Logger::error).orElse(null);
        }

        var decodeResult = this.blockStateCodec.parse(NbtOps.INSTANCE, blockStatesTag);
        var direct = decodeResult.result().orElse(null);
        if (direct != null) {
            return direct;
        }

        PalettedContainer<BlockState> recovered = this.tryDecodeWithRepackedStorage(blockStatesTag, raw, x, y, z, legacyPath);
        if (recovered != null) {
            return recovered;
        }

        return decodeResult.resultOrPartial(Logger::error).orElse(null);
    }

    private PalettedContainer<BlockState> tryDecodeWithRepackedStorage(CompoundTag blockStatesTag, long[] raw, int x, int y, int z, boolean legacyPath) {
        long[] repacked = tryRepackCompactStorage(raw);
        if (repacked == null) {
            return null;
        }

        CompoundTag repackedTag = blockStatesTag.copy();
        repackedTag.putLongArray("data", repacked);
        var recovered = this.blockStateCodec.parse(NbtOps.INSTANCE, repackedTag).result().orElse(null);
        if (recovered != null) {
            this.logRecoveredCompactStorage(raw.length, repacked.length, x, y, z, legacyPath);
        }
        return recovered;
    }

    private void logRecoveredCompactStorage(int fromLength, int toLength, int x, int y, int z, boolean legacyPath) {
        int recoveredCount = this.recoveredCompactStorageCount.incrementAndGet();
        if (recoveredCount <= RECOVERY_DETAIL_LOG_LIMIT) {
            Logger.warn("Recovered " + (legacyPath ? "legacy " : "") + "PalettedContainer storage at section (" + x + ", " + y + ", " + z + ") by repacking BlockStates from " + fromLength + " to " + toLength + " longs");
            return;
        }
        if (((recoveredCount - RECOVERY_DETAIL_LOG_LIMIT) % RECOVERY_SUMMARY_LOG_INTERVAL) == 0) {
            Logger.warn("Recovered " + (legacyPath ? "legacy " : "") + "PalettedContainer storage " + recoveredCount + " times so far; latest section (" + x + ", " + y + ", " + z + "), latest repack " + fromLength + "->" + toLength + " longs");
        }
    }

    private static boolean isKnownCompactStorageLength(int length) {
        return length == COMPACT_BLOCK_STATES_LENGTH_5BIT || length == COMPACT_BLOCK_STATES_LENGTH_6BIT;
    }

    private static long[] tryRepackCompactStorage(long[] compactData) {
        if (compactData.length == 0 || (compactData.length % 64) != 0) {
            return null;
        }

        int bits = compactData.length / 64;
        if (bits <= 0 || bits >= 32) {
            return null;
        }

        int valuesPerLong = 64 / bits;
        if (valuesPerLong <= 0) {
            return null;
        }

        int paddedLength = (SECTION_STATE_ENTRY_COUNT + valuesPerLong - 1) / valuesPerLong;
        if (paddedLength == compactData.length) {
            return null;
        }

        long mask = (1L << bits) - 1L;
        long[] paddedData = new long[paddedLength];

        for (int index = 0; index < SECTION_STATE_ENTRY_COUNT; index++) {
            int bitIndex = index * bits;
            int srcLongIndex = bitIndex >>> 6;
            int srcBitOffset = bitIndex & 63;

            long value = compactData[srcLongIndex] >>> srcBitOffset;
            if ((srcBitOffset + bits) > 64) {
                if (srcLongIndex + 1 >= compactData.length) {
                    return null;
                }
                value |= compactData[srcLongIndex + 1] << (64 - srcBitOffset);
            }
            value &= mask;

            int dstLongIndex = index / valuesPerLong;
            int dstBitOffset = (index % valuesPerLong) * bits;
            paddedData[dstLongIndex] |= value << dstBitOffset;
        }

        return paddedData;
    }

    private static boolean isValidRegionFileName(String name) {
        return REGION_FILE_NAME_PATTERN.matcher(name).matches();
    }

    private static boolean shouldLogUnknownRegionFile(String name) {
        return !name.endsWith(".backup");
    }
}
