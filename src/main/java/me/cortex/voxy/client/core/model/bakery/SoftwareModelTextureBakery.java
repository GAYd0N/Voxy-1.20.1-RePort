package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;

public class SoftwareModelTextureBakery {
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer();

    private boolean textureSetup = false;

    public SoftwareModelTextureBakery() {
    }

    public void setupTexture() {
        if (this.textureSetup) {
            return;
        }

        var textureManager = Minecraft.getInstance().getTextureManager();
        var texture = textureManager.getTexture(new ResourceLocation("minecraft", "textures/atlas/blocks.png"));

        int glTextureId = texture.getId();
        int targetMipLevel = 0;

        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, targetMipLevel, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, targetMipLevel, GL_TEXTURE_HEIGHT);

        int[] textureData = new int[width * height];
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());

        int prevTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, glTextureId);
        glGetTexImage(GL_TEXTURE_2D, targetMipLevel, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, prevTexture);

        buffer.asIntBuffer().get(textureData);

        this.rasterizer.setSamplerTexture(textureData, width, height);
        this.textureSetup = true;
    }

    public static int getMetaFromLayer(RenderType layer) {
        boolean hasDiscard = layer == RenderType.cutout() ||
                layer == RenderType.cutoutMipped() ||
                layer == RenderType.tripwire();

        int meta = hasDiscard ? 1 : 0;
        meta |= true ? 2 : 0;
        return meta;
    }

    private void bakeBlockModel(BlockState state, RenderType layer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;
        }

        if (state.hasBlockEntity()) {
            return;
        }

        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        if (model.isCustomRenderer()) {
            return;
        }

        int meta = getMetaFromLayer(layer);

        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            var quads = model.getQuads(state, direction, new SingleThreadedRandomSource(42L));
            if (quads.isEmpty()) {
                continue;
            }
            for (var quad : quads) {
                this.vc.quad(quad, meta | (quad.isTinted() ? 4 : 0));
            }
        }
    }

    private void bakeFluidState(BlockState state, RenderType layer, int face) {
        int metadata = getMetaFromLayer(layer);
        metadata |= 4;
        this.vc.setDefaultMeta(metadata);

        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        }, this.vc, state, state.getFluidState());
        this.vc.setDefaultMeta(0);
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX() * pos.getX() + fv.getY() * pos.getY() + fv.getZ() * pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.vc.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE) * 8;

    public int renderToOutput(BlockState state, long outputBuffer) {
        this.setupTexture();

        boolean isBlock = true;
        RenderType layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                layer = RenderType.solid();
            } else {
                layer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }

        BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            bbem = BakedBlockEntityModel.bake(state);
        }

        {
            this.rasterizer.setBlending(layer == RenderType.translucent());
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        if (isBlock) {
            this.vc.reset();
            this.bakeBlockModel(state, layer);
            isAnyShaded |= this.vc.anyShaded;
            isAnyDarkend |= this.vc.anyDarkendTex;
            if (!this.vc.isEmpty()) {
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
                    this.rasterizer.raster(VIEWS[i], this.vc);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
                }
            }
        } else {
            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.vc.reset();
                this.bakeFluidState(state, layer, i);
                if (this.vc.isEmpty()) continue;
                isAnyShaded |= this.vc.anyShaded;
                isAnyDarkend |= this.vc.anyDarkendTex;

                this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
                this.rasterizer.raster(VIEWS[i], this.vc);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
            }
        }

        if (bbem != null) {
            bbem.release();
        }

        return (isAnyShaded ? 1 : 0) | (isAnyDarkend ? 2 : 0);
    }

    static {
        addView(0, -90, 0, 0, 0);
        addView(1, 90, 0, 0, 0b100);
        addView(2, 0, 180, 0, 0b001);
        addView(3, 0, 0, 0, 0);
        addView(4, 0, 90, 270, 0b100);
        addView(5, 0, 270, 270, 0);
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack.mulPoseMatrix(new Matrix4f().scale(1 - 2 * (flip & 1), 1 - (flip & 2), 1 - ((flip >> 1) & 2)));
        stack.translate(-0.5f, -0.5f, -0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                        2, 0, 0, 0,
                        0, 2, 0, 0,
                        0, 0, -2, 0,
                        -1, -1, 1, 1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
