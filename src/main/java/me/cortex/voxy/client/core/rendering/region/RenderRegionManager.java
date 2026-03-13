package me.cortex.voxy.client.core.rendering.region;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;

/**
 * 渲染区域管理器
 * 参考 Sodium 的 RenderRegionManager 实现
 * 
 * 主要功能：
 * 1. 区域管理：将世界划分为多个区域进行管理
 * 2. 批量上传：高效上传几何数据到GPU
 * 3. 区块淡入：新构建的区块有淡入动画效果
 */
public class RenderRegionManager {
    // 区域尺寸（以区块为单位）
    public static final int REGION_WIDTH = 8;
    public static final int REGION_HEIGHT = 4;
    public static final int REGION_LENGTH = 8;
    
    // 区域掩码
    public static final int REGION_WIDTH_M = REGION_WIDTH - 1;
    public static final int REGION_HEIGHT_M = REGION_HEIGHT - 1;
    public static final int REGION_LENGTH_M = REGION_LENGTH - 1;
    
    // 区域位移
    public static final int REGION_WIDTH_SH = Integer.bitCount(REGION_WIDTH_M);
    public static final int REGION_HEIGHT_SH = Integer.bitCount(REGION_HEIGHT_M);
    public static final int REGION_LENGTH_SH = Integer.bitCount(REGION_LENGTH_M);
    
    // 每帧最大上传时间（纳秒）
    private static final long MAX_UPLOAD_TIME_NS = 2_000_000; // 2ms
    
    private final Long2ReferenceOpenHashMap<RenderRegion> regions = new Long2ReferenceOpenHashMap<>();
    private final ConcurrentLinkedDeque<BuiltSection> buildResults = new ConcurrentLinkedDeque<>();
    
    private final SectionFadeManager fadeManager;
    private final BatchUploadQueue uploadQueue;
    
    private final int maxSectionCount;
    private double cameraX, cameraY, cameraZ;
    
    public RenderRegionManager(int maxSectionCount) {
        this.maxSectionCount = maxSectionCount;
        this.fadeManager = new SectionFadeManager(maxSectionCount);
        this.uploadQueue = new BatchUploadQueue();
    }
    
    /**
     * 设置摄像机位置
     */
    public void setCameraPosition(double x, double y, double z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        this.fadeManager.setCameraPosition(x, y, z);
    }
    
    /**
     * 提交构建结果
     */
    public void submitBuildResult(BuiltSection result) {
        if (result != null && !result.isEmpty()) {
            this.buildResults.add(result);
        }
    }
    
    /**
     * 处理构建结果并上传
     * @return 上传的区块数量
     */
    public int processBuildResults() {
        List<BuiltSection> results = new ArrayList<>();
        BuiltSection result;
        while ((result = this.buildResults.poll()) != null) {
            results.add(result);
        }
        
        if (results.isEmpty()) {
            return 0;
        }
        
        int uploadedCount = 0;
        
        for (BuiltSection builtSection : results) {
            if (builtSection.isEmpty()) {
                builtSection.free();
                continue;
            }
            
            // 获取或创建区域
            int sectionX = WorldEngine.getX(builtSection.position);
            int sectionY = WorldEngine.getY(builtSection.position);
            int sectionZ = WorldEngine.getZ(builtSection.position);
            int level = WorldEngine.getLevel(builtSection.position);
            
            RenderRegion region = this.getOrCreateRegion(sectionX, sectionY, sectionZ);
            
            // 处理几何数据上传
            if (builtSection.geometryBuffer != null) {
                this.processGeometryUpload(region, builtSection);
            }
            
            // 触发淡入效果
            this.fadeManager.onSectionBuilt(region.getSectionId(sectionX, sectionY, sectionZ), 
                    sectionX, sectionY, sectionZ);
            
            uploadedCount++;
            builtSection.free();
        }
        
        return uploadedCount;
    }
    
    /**
     * 处理几何数据上传
     */
    private void processGeometryUpload(RenderRegion region, BuiltSection section) {
        // 这里需要与 Voxy 的几何数据系统集成
        // 目前只是占位，实际实现需要与 BasicSectionGeometryData 配合
    }
    
    /**
     * 获取或创建区域
     */
    public RenderRegion getOrCreateRegion(int sectionX, int sectionY, int sectionZ) {
        long key = getRegionKey(sectionX, sectionY, sectionZ);
        RenderRegion region = this.regions.get(key);
        
        if (region == null) {
            region = new RenderRegion(
                    sectionX >> REGION_WIDTH_SH,
                    sectionY >> REGION_HEIGHT_SH,
                    sectionZ >> REGION_LENGTH_SH
            );
            this.regions.put(key, region);
        }
        
        return region;
    }
    
    /**
     * 获取区域
     */
    public RenderRegion getRegion(int sectionX, int sectionY, int sectionZ) {
        long key = getRegionKey(sectionX, sectionY, sectionZ);
        return this.regions.get(key);
    }
    
    /**
     * 移除区域
     */
    public void removeRegion(int sectionX, int sectionY, int sectionZ) {
        long key = getRegionKey(sectionX, sectionY, sectionZ);
        RenderRegion region = this.regions.remove(key);
        if (region != null) {
            region.markForDeletion();
        }
    }
    
    /**
     * 更新所有区域
     * 每帧调用
     */
    public void update() {
        // 更新淡入效果
        this.fadeManager.update();
        
        // 处理待上传队列
        if (this.uploadQueue.hasPendingUploads()) {
            this.uploadQueue.flushPartial(MAX_UPLOAD_TIME_NS / 1_000_000);
        }
        
        // 清理空区域
        var iter = this.regions.values().iterator();
        while (iter.hasNext()) {
            RenderRegion region = iter.next();
            if (region.isEmpty() && region.isMarkedForDeletion()) {
                iter.remove();
            }
        }
    }
    
    /**
     * 获取区域键
     */
    public static long getRegionKey(int sectionX, int sectionY, int sectionZ) {
        long x = sectionX >> REGION_WIDTH_SH;
        long y = sectionY >> REGION_HEIGHT_SH;
        long z = sectionZ >> REGION_LENGTH_SH;
        return (x & 0xFFFFFFL) | ((y & 0xFFFFFFL) << 24) | ((z & 0xFFFFFFL) << 48);
    }
    
    /**
     * 获取所有加载的区域
     */
    public Collection<RenderRegion> getLoadedRegions() {
        return this.regions.values();
    }
    
    /**
     * 获取区域数量
     */
    public int getRegionCount() {
        return this.regions.size();
    }
    
    /**
     * 获取淡入管理器
     */
    public SectionFadeManager getFadeManager() {
        return this.fadeManager;
    }
    
    /**
     * 获取上传队列
     */
    public BatchUploadQueue getUploadQueue() {
        return this.uploadQueue;
    }
    
    /**
     * 获取待处理的构建结果数量
     */
    public int getPendingBuildResultCount() {
        return this.buildResults.size();
    }
    
    /**
     * 清理所有资源
     */
    public void free() {
        // 清理构建结果
        BuiltSection result;
        while ((result = this.buildResults.poll()) != null) {
            result.free();
        }
        
        // 清理区域
        this.regions.clear();
        
        // 清理上传队列
        this.uploadQueue.clear();
        
        // 清理淡入管理器
        this.fadeManager.free();
    }
    
    /**
     * 重置所有状态
     */
    public void reset() {
        this.buildResults.clear();
        this.regions.clear();
        this.uploadQueue.clear();
        this.fadeManager.reset();
    }
    
    /**
     * 添加调试信息
     */
    public void addDebugInfo(List<String> debug) {
        debug.add("Regions: " + this.regions.size());
        debug.add("Pending uploads: " + this.uploadQueue.getPendingCount());
        debug.add("Fading sections: " + this.fadeManager.getFadingSectionCount());
        debug.add("Pending build results: " + this.buildResults.size());
    }
}
