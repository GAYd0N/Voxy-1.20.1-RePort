package me.cortex.voxy.client.core.rendering.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;

/**
 * 批量上传队列管理器
 * 参考 Sodium 的 StagingBuffer 和 RenderRegionManager 的上传机制
 * 支持批量收集上传请求，然后一次性提交到GPU
 */
public class BatchUploadQueue {
    // 单次上传的最大大小（字节）
    private static final long MAX_SINGLE_UPLOAD_SIZE = 2L << 20; // 2MB
    // 每帧最大上传大小（字节）
    private static final long MAX_FRAME_UPLOAD_SIZE = 16L << 20; // 16MB
    
    private final ConcurrentLinkedQueue<PendingUpload> pendingUploads = new ConcurrentLinkedQueue<>();
    private final Long2ObjectOpenHashMap<List<PendingUpload>> uploadsByRegion = new Long2ObjectOpenHashMap<>();
    
    private long totalPendingSize = 0;
    private long frameUploadBudget = MAX_FRAME_UPLOAD_SIZE;
    
    /**
     * 待处理的上传请求
     */
    public static class PendingUpload {
        public final GlBuffer targetBuffer;
        public final long targetOffset;
        public final MemoryBuffer data;
        public final long regionKey;
        public final int sectionId;
        
        public PendingUpload(GlBuffer targetBuffer, long targetOffset, MemoryBuffer data, long regionKey, int sectionId) {
            this.targetBuffer = targetBuffer;
            this.targetOffset = targetOffset;
            this.data = data;
            this.regionKey = regionKey;
            this.sectionId = sectionId;
        }
    }
    
    /**
     * 上传结果
     */
    public static class UploadResult {
        public final boolean success;
        public final long bytesUploaded;
        public final int sectionCount;
        
        public UploadResult(boolean success, long bytesUploaded, int sectionCount) {
            this.success = success;
            this.bytesUploaded = bytesUploaded;
            this.sectionCount = sectionCount;
        }
    }
    
    /**
     * 添加上传请求到队列
     * @return 是否成功添加（如果超出预算则返回false）
     */
    public boolean enqueue(GlBuffer targetBuffer, long targetOffset, MemoryBuffer data, long regionKey, int sectionId) {
        long uploadSize = data.size;
        
        // 检查是否超出预算
        if (this.totalPendingSize + uploadSize > this.frameUploadBudget) {
            return false;
        }
        
        PendingUpload upload = new PendingUpload(targetBuffer, targetOffset, data, regionKey, sectionId);
        this.pendingUploads.add(upload);
        
        // 按区域分组
        this.uploadsByRegion.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(upload);
        
        this.totalPendingSize += uploadSize;
        return true;
    }
    
    /**
     * 执行所有待处理的上传
     * @return 上传结果
     */
    public UploadResult flush() {
        if (this.pendingUploads.isEmpty()) {
            return new UploadResult(true, 0, 0);
        }
        
        long totalBytes = 0;
        int sectionCount = 0;
        
        // 按区域批量处理上传
        for (var entry : this.uploadsByRegion.long2ObjectEntrySet()) {
            long regionKey = entry.getLongKey();
            List<PendingUpload> uploads = entry.getValue();
            
            for (PendingUpload upload : uploads) {
                // 使用 UploadStream 执行上传
                long ptr = UploadStream.INSTANCE.upload(upload.targetBuffer, upload.targetOffset, upload.data.size);
                MemoryUtil.memCopy(upload.data.address, ptr, upload.data.size);
                
                totalBytes += upload.data.size;
                sectionCount++;
            }
        }
        
        // 提交所有上传
        UploadStream.INSTANCE.commit();
        
        // 设置内存屏障
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        
        // 清理
        this.clear();
        
        return new UploadResult(true, totalBytes, sectionCount);
    }
    
    /**
     * 异步处理上传（分帧处理）
     * @param maxBytes 本帧最大处理字节数
     * @return 上传结果
     */
    public UploadResult flushPartial(long maxBytes) {
        if (this.pendingUploads.isEmpty()) {
            return new UploadResult(true, 0, 0);
        }
        
        long remainingBudget = Math.min(maxBytes, this.frameUploadBudget);
        long totalBytes = 0;
        int sectionCount = 0;
        
        List<PendingUpload> processedUploads = new ArrayList<>();
        
        while (!this.pendingUploads.isEmpty() && remainingBudget > 0) {
            PendingUpload upload = this.pendingUploads.peek();
            
            if (upload.data.size > remainingBudget) {
                break;
            }
            
            upload = this.pendingUploads.poll();
            
            long ptr = UploadStream.INSTANCE.upload(upload.targetBuffer, upload.targetOffset, upload.data.size);
            MemoryUtil.memCopy(upload.data.address, ptr, upload.data.size);
            
            totalBytes += upload.data.size;
            remainingBudget -= upload.data.size;
            sectionCount++;
            
            processedUploads.add(upload);
        }
        
        UploadStream.INSTANCE.commit();
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        
        // 更新待处理大小
        this.totalPendingSize -= totalBytes;
        
        // 从区域映射中移除已处理的上传
        for (PendingUpload upload : processedUploads) {
            List<PendingUpload> regionUploads = this.uploadsByRegion.get(upload.regionKey);
            if (regionUploads != null) {
                regionUploads.remove(upload);
                if (regionUploads.isEmpty()) {
                    this.uploadsByRegion.remove(upload.regionKey);
                }
            }
            // 释放数据缓冲区
            upload.data.free();
        }
        
        return new UploadResult(true, totalBytes, sectionCount);
    }
    
    /**
     * 清空所有待处理的上传
     */
    public void clear() {
        // 释放所有数据缓冲区
        PendingUpload upload;
        while ((upload = this.pendingUploads.poll()) != null) {
            upload.data.free();
        }
        
        this.uploadsByRegion.clear();
        this.totalPendingSize = 0;
    }
    
    /**
     * 获取待处理上传数量
     */
    public int getPendingCount() {
        return this.pendingUploads.size();
    }
    
    /**
     * 获取待处理上传总大小
     */
    public long getPendingSize() {
        return this.totalPendingSize;
    }
    
    /**
     * 设置每帧上传预算
     */
    public void setFrameUploadBudget(long budget) {
        this.frameUploadBudget = budget;
    }
    
    /**
     * 检查是否有待处理的上传
     */
    public boolean hasPendingUploads() {
        return !this.pendingUploads.isEmpty();
    }
    
    /**
     * 获取区域数量
     */
    public int getRegionCount() {
        return this.uploadsByRegion.size();
    }
}
