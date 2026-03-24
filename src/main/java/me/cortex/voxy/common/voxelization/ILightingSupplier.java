package me.cortex.voxy.common.voxelization;

public interface ILightingSupplier {
    byte supply(int x, int y, int z);

    default byte[] getBlockLight() {
        return null;
    }

    default byte[] getSkyLight() {
        return null;
    }
}
