package me.cortex.voxy.common.config.storage;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

public abstract class StorageBackend implements IMappingStorage, IStoredSectionPositionIterator {

    public abstract MemoryBuffer getSectionData(long key, MemoryBuffer scratch);

    public abstract void setSectionData(long key, MemoryBuffer data);

    public abstract void deleteSectionData(long key);

    public abstract void flush();

    public abstract void close();

    public List<StorageBackend> getChildBackends() {
        return List.of();
    }

    public final List<StorageBackend> collectAllBackends() {
        List<StorageBackend> backends = new ArrayList<>();
        backends.add(this);
        for (var child : this.getChildBackends()) {
            backends.addAll(child.collectAllBackends());
        }
        return backends;
    }

    public abstract void iterateStoredSectionPositions(LongConsumer consumer);

    @Override
    public void iteratePositions(int level, LongConsumer callback) {
        this.iterateStoredSectionPositions(callback);
    }
}
