package me.cortex.voxy.client.core.rendering.region;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;

/**
 * 管理区块淡入效果
 * 参考 Sodium 的 RenderRegionManager 中的区块淡入实现
 */
public class SectionFadeManager {
    // 淡入动画持续时间（毫秒）
    private static final int FADE_DURATION_MS = 500;
    // 近距离区块不执行淡入（距离平方阈值）
    private static final float NEARBY_DISTANCE_SQ = 768.0f;
    
    private GlBuffer fadeTimeBuffer;
    private final Int2IntOpenHashMap sectionFadeTimes = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap sectionCreationTimes = new Int2IntOpenHashMap();
    
    private int maxSectionCount;
    private double cameraX, cameraY, cameraZ;
    
    public SectionFadeManager(int maxSectionCount) {
        this.maxSectionCount = maxSectionCount;
        this.sectionFadeTimes.defaultReturnValue(-1);
        this.sectionCreationTimes.defaultReturnValue(-1);
        this.fadeTimeBuffer = new GlBuffer((long) maxSectionCount * Integer.BYTES).zero();
    }
    
    /**
     * 设置摄像机位置（用于计算距离）
     */
    public void setCameraPosition(double x, double y, double z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
    }
    
    /**
     * 当区块首次构建时调用
     * @param sectionId 区块ID
     * @param sectionX 区块X坐标（区块坐标）
     * @param sectionY 区块Y坐标
     * @param sectionZ 区块Z坐标
     * @return 是否需要淡入动画
     */
    public boolean onSectionBuilt(int sectionId, int sectionX, int sectionY, int sectionZ) {
        if (this.sectionCreationTimes.containsKey(sectionId)) {
            return false;
        }
        
        long currentTime = System.currentTimeMillis();
        this.sectionCreationTimes.put(sectionId, (int) currentTime);
        
        // 计算到摄像机的距离
        double centerX = (sectionX << 4) + 8;
        double centerY = (sectionY << 4) + 8;
        double centerZ = (sectionZ << 4) + 8;
        double dx = centerX - this.cameraX;
        double dy = centerY - this.cameraY;
        double dz = centerZ - this.cameraZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        
        // 近距离区块不执行淡入
        if (distanceSq < NEARBY_DISTANCE_SQ) {
            this.sectionFadeTimes.put(sectionId, -1);
            return false;
        }
        
        // 设置淡入开始时间
        this.sectionFadeTimes.put(sectionId, 0);
        return true;
    }
    
    /**
     * 更新所有区块的淡入状态
     * 每帧调用一次
     */
    public void update() {
        if (this.sectionFadeTimes.isEmpty()) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        boolean needsUpload = false;
        
        var iter = this.sectionFadeTimes.int2IntEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            int sectionId = entry.getIntKey();
            int fadeTime = entry.getIntValue();
            
            if (fadeTime == -1) {
                continue;
            }
            
            int creationTime = this.sectionCreationTimes.get(sectionId);
            int elapsed = (int) (currentTime - creationTime);
            
            if (elapsed >= FADE_DURATION_MS) {
                // 淡入完成
                entry.setValue(-1);
            } else {
                entry.setValue(elapsed);
                needsUpload = true;
            }
        }
        
        if (needsUpload) {
            this.uploadFadeTimes();
        }
    }
    
    /**
     * 上传淡入时间到GPU
     */
    private void uploadFadeTimes() {
        int count = this.sectionFadeTimes.size();
        if (count == 0) {
            return;
        }
        
        long ptr = UploadStream.INSTANCE.upload(this.fadeTimeBuffer, 0, (long) count * Integer.BYTES);
        
        var iter = this.sectionFadeTimes.int2IntEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            int sectionId = entry.getIntKey();
            int fadeTime = entry.getIntValue();
            MemoryUtil.memPutInt(ptr + (long) sectionId * Integer.BYTES, fadeTime);
        }
        
        UploadStream.INSTANCE.commit();
    }
    
    /**
     * 移除区块
     */
    public void removeSection(int sectionId) {
        this.sectionFadeTimes.remove(sectionId);
        this.sectionCreationTimes.remove(sectionId);
    }
    
    /**
     * 绑定淡入时间缓冲区到着色器
     * @param binding 绑定点
     */
    public void bind(int binding) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, this.fadeTimeBuffer.id);
    }
    
    /**
     * 获取缓冲区ID
     */
    public int getBufferId() {
        return this.fadeTimeBuffer.id;
    }
    
    /**
     * 调整容量
     */
    public void resize(int newMaxSectionCount) {
        if (newMaxSectionCount <= this.maxSectionCount) {
            return;
        }
        
        this.fadeTimeBuffer.free();
        this.maxSectionCount = newMaxSectionCount;
        this.fadeTimeBuffer = new GlBuffer((long) maxSectionCount * Integer.BYTES).zero();
    }
    
    /**
     * 清理资源
     */
    public void free() {
        this.fadeTimeBuffer.free();
        this.sectionFadeTimes.clear();
        this.sectionCreationTimes.clear();
    }
    
    /**
     * 重置所有状态
     */
    public void reset() {
        this.sectionFadeTimes.clear();
        this.sectionCreationTimes.clear();
    }
    
    /**
     * 获取正在淡入的区块数量
     */
    public int getFadingSectionCount() {
        int count = 0;
        var iter = this.sectionFadeTimes.int2IntEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (entry.getIntValue() != -1) {
                count++;
            }
        }
        return count;
    }
}
