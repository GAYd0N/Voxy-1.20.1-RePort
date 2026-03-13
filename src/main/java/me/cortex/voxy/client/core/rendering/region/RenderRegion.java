package me.cortex.voxy.client.core.rendering.region;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;

import static me.cortex.voxy.client.core.rendering.region.RenderRegionManager.*;

/**
 * 渲染区域
 * 表示一个 8x4x8 区块的区域
 * 参考 Sodium 的 RenderRegion 实现
 */
public class RenderRegion {
    // 区域坐标（区域坐标系）
    private final int x, y, z;
    
    // 区域内的区块集合
    private final IntOpenHashSet sections = new IntOpenHashSet();
    
    // 区域状态
    private boolean markedForDeletion = false;
    private final long creationTime;
    
    // 区域统计
    private int sectionCount = 0;
    private long geometrySize = 0;
    
    public RenderRegion(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.creationTime = System.currentTimeMillis();
    }
    
    /**
     * 获取区域X坐标
     */
    public int getX() {
        return this.x;
    }
    
    /**
     * 获取区域Y坐标
     */
    public int getY() {
        return this.y;
    }
    
    /**
     * 获取区域Z坐标
     */
    public int getZ() {
        return this.z;
    }
    
    /**
     * 获取区块X起点（区块坐标）
     */
    public int getChunkX() {
        return this.x << REGION_WIDTH_SH;
    }
    
    /**
     * 获取区块Y起点（区块坐标）
     */
    public int getChunkY() {
        return this.y << REGION_HEIGHT_SH;
    }
    
    /**
     * 获取区块Z起点（区块坐标）
     */
    public int getChunkZ() {
        return this.z << REGION_LENGTH_SH;
    }
    
    /**
     * 获取世界X起点（方块坐标）
     */
    public int getOriginX() {
        return this.getChunkX() << 4;
    }
    
    /**
     * 获取世界Y起点（方块坐标）
     */
    public int getOriginY() {
        return this.getChunkY() << 4;
    }
    
    /**
     * 获取世界Z起点（方块坐标）
     */
    public int getOriginZ() {
        return this.getChunkZ() << 4;
    }
    
    /**
     * 获取创建时间
     */
    public long getCreationTime() {
        return this.creationTime;
    }
    
    /**
     * 添加区块到区域
     * @param sectionId 区块在区域内的ID（0-255）
     */
    public void addSection(int sectionId) {
        if (this.sections.add(sectionId)) {
            this.sectionCount++;
        }
    }
    
    /**
     * 从区域移除区块
     * @param sectionId 区块在区域内的ID
     */
    public void removeSection(int sectionId) {
        if (this.sections.remove(sectionId)) {
            this.sectionCount--;
        }
    }
    
    /**
     * 检查区域是否包含指定区块
     */
    public boolean containsSection(int sectionId) {
        return this.sections.contains(sectionId);
    }
    
    /**
     * 获取区域内的区块数量
     */
    public int getSectionCount() {
        return this.sectionCount;
    }
    
    /**
     * 检查区域是否为空
     */
    public boolean isEmpty() {
        return this.sectionCount == 0;
    }
    
    /**
     * 标记区域待删除
     */
    public void markForDeletion() {
        this.markedForDeletion = true;
    }
    
    /**
     * 检查是否标记为待删除
     */
    public boolean isMarkedForDeletion() {
        return this.markedForDeletion;
    }
    
    /**
     * 设置几何数据大小
     */
    public void setGeometrySize(long size) {
        this.geometrySize = size;
    }
    
    /**
     * 获取几何数据大小
     */
    public long getGeometrySize() {
        return this.geometrySize;
    }
    
    /**
     * 获取区块在区域内的ID
     * @param sectionX 区块X坐标（世界区块坐标）
     * @param sectionY 区块Y坐标
     * @param sectionZ 区块Z坐标
     * @return 区块在区域内的ID（0-255）
     */
    public int getSectionId(int sectionX, int sectionY, int sectionZ) {
        int localX = sectionX & REGION_WIDTH_M;
        int localY = sectionY & REGION_HEIGHT_M;
        int localZ = sectionZ & REGION_LENGTH_M;
        return localX | (localY << REGION_WIDTH_SH) | (localZ << (REGION_WIDTH_SH + REGION_HEIGHT_SH));
    }
    
    /**
     * 从区块ID获取本地X坐标
     */
    public static int getLocalX(int sectionId) {
        return sectionId & REGION_WIDTH_M;
    }
    
    /**
     * 从区块ID获取本地Y坐标
     */
    public static int getLocalY(int sectionId) {
        return (sectionId >> REGION_WIDTH_SH) & REGION_HEIGHT_M;
    }
    
    /**
     * 从区块ID获取本地Z坐标
     */
    public static int getLocalZ(int sectionId) {
        return (sectionId >> (REGION_WIDTH_SH + REGION_HEIGHT_SH)) & REGION_LENGTH_M;
    }
    
    /**
     * 获取区域键
     */
    public long getKey() {
        return RenderRegionManager.getRegionKey(this.getChunkX(), this.getChunkY(), this.getChunkZ());
    }
    
    /**
     * 计算区域中心到点的距离平方
     */
    public double getDistanceSquared(double worldX, double worldY, double worldZ) {
        double centerX = this.getOriginX() + (REGION_WIDTH << 4) / 2.0;
        double centerY = this.getOriginY() + (REGION_HEIGHT << 4) / 2.0;
        double centerZ = this.getOriginZ() + (REGION_LENGTH << 4) / 2.0;
        
        double dx = centerX - worldX;
        double dy = centerY - worldY;
        double dz = centerZ - worldZ;
        
        return dx * dx + dy * dy + dz * dz;
    }
    
    /**
     * 获取填充率
     * @return 填充率（0-1）
     */
    public float getFillFraction() {
        return (float) this.sectionCount / (REGION_WIDTH * REGION_HEIGHT * REGION_LENGTH);
    }
    
    /**
     * 获取填充率的倒数（用于缓冲区扩展）
     */
    public float getFillFractionInv() {
        return (float) (REGION_WIDTH * REGION_HEIGHT * REGION_LENGTH) / (float) this.sectionCount;
    }
    
    /**
     * 获取所有区块ID
     */
    public int[] getSectionIds() {
        return this.sections.toIntArray();
    }
    
    /**
     * 清理区域
     */
    public void clear() {
        this.sections.clear();
        this.sectionCount = 0;
        this.geometrySize = 0;
    }
    
    @Override
    public String toString() {
        return String.format("RenderRegion[%d, %d, %d](sections=%d)", this.x, this.y, this.z, this.sectionCount);
    }
}
