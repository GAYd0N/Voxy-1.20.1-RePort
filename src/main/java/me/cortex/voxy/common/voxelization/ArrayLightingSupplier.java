package me.cortex.voxy.common.voxelization;

public class ArrayLightingSupplier implements ILightingSupplier {
    private final byte[] blockLight;
    private final byte[] skyLight;

    public ArrayLightingSupplier(byte[] blockLight, byte[] skyLight) {
        this.blockLight = blockLight;
        this.skyLight = skyLight;
    }

    @Override
    public byte supply(int x, int y, int z) {
        int i = (y << 8) | (z << 4) | x;
        int block = unpackLightNibble(this.blockLight, i);
        int sky = unpackLightNibble(this.skyLight, i);
        return (byte) (sky | (block << 4));
    }

    @Override
    public byte[] getBlockLight() {
        return this.blockLight;
    }

    @Override
    public byte[] getSkyLight() {
        return this.skyLight;
    }

    private static int unpackLightNibble(byte[] packedLight, int index) {
        if (packedLight == null) {
            return 0;
        }
        int packed = packedLight[index >> 1] & 0xFF;
        return (index & 1) == 0 ? (packed & 0xF) : (packed >> 4);
    }
}
