package me.cortex.voxy.common.voxelization;

/**
 * 光照数据供应接口
 * 用于传递光照数据，支持 Ecliptic-Seasons 等 Mod 通过 Mixin 修改光照
 */
public interface ILightingSupplier {
    /**
     * 获取指定位置的组合光照值
     * 
     * @param x 局部 X 坐标 (0-15)
     * @param y 局部 Y 坐标 (0-15)
     * @param z 局部 Z 坐标 (0-15)
     * @return 组合光照值 (低4位=天空光, 高4位=方块光)
     */
    byte supply(int x, int y, int z);

    /**
     * 获取方块光原始数据（可选）
     * 
     * @return 方块光字节数组，可能为 null
     */
    default byte[] getBlockLight() {
        return null;
    }

    /**
     * 获取天空光原始数据（可选）
     * 
     * @return 天空光字节数组，可能为 null
     */
    default byte[] getSkyLight() {
        return null;
    }
}
