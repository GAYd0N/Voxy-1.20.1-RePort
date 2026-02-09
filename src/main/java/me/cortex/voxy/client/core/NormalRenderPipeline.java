package me.cortex.voxy.client.core;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.ARBComputeShader.glDispatchCompute;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glBindImageTexture;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL14.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL15.GL_READ_WRITE;
import static org.lwjgl.opengl.GL20C.nglUniform3fv;
import static org.lwjgl.opengl.GL20C.nglUniform4fv;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glTextureParameterf;

public class NormalRenderPipeline extends AbstractRenderPipeline {
    private GlTexture colourTex;
    private GlTexture colourSSAOTex;
    private final GlFramebuffer fbSSAO = new GlFramebuffer();
    private final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    private FullscreenBlit finalBlit;
    private boolean lastAtmosphericFog;
    private boolean lastEnvironmentalFog;
    private boolean lastRenderVanillaFog;

    private final Shader ssaoCompute = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:post/ssao.comp")
            .compile();

    protected NormalRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier);
        this.rebuildFinalBlit();
    }

    private void rebuildFinalBlit() {
        if (this.finalBlit != null) {
            this.finalBlit.delete();
        }
        this.lastAtmosphericFog = VoxyConfig.CONFIG.atmosphericFog;
        this.lastEnvironmentalFog = VoxyConfig.CONFIG.environmentalFog;
        this.lastRenderVanillaFog = VoxyConfig.CONFIG.renderVanillaFog;
        this.finalBlit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag",
                a->a.define("EMIT_COLOUR")
                        .defineIf("USE_ATMOSPHERIC_FOG", this.lastAtmosphericFog)
                        .defineIf("USE_ENV_FOG", this.lastEnvironmentalFog && this.lastRenderVanillaFog));
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFB, int srcWidth, int srcHeight) {
        if (this.colourTex == null || this.colourTex.getHeight() != viewport.height || this.colourTex.getWidth() != viewport.width) {
            if (this.colourTex != null) {
                this.colourTex.free();
                this.colourSSAOTex.free();
            }
            this.fb.resize(viewport.width, viewport.height);

            this.colourTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);
            this.colourSSAOTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);

            this.fb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.colourTex).verify();
            this.fbSSAO.bind(GL_DEPTH_STENCIL_ATTACHMENT, this.fb.getDepthTex()).bind(GL_COLOR_ATTACHMENT0, this.colourSSAOTex).verify();


            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.fb.getDepthTex().id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
        }

        this.initDepthStencil(sourceFB, this.fb.framebuffer.id, viewport.width, viewport.height, viewport.width, viewport.height);

        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport) {
        this.ssaoCompute.bind();
        try (var stack = MemoryStack.stackPush()) {
            long ptr = stack.nmalloc(4*4*4);
            viewport.MVP.getToAddress(ptr);
            nglUniformMatrix4fv(3, 1, false, ptr);//MVP
            viewport.MVP.invert(new Matrix4f()).getToAddress(ptr);
            nglUniformMatrix4fv(4, 1, false, ptr);//invMVP
        }


        glBindImageTexture(0, this.colourSSAOTex.id, 0, false,0, GL_READ_WRITE, GL_RGBA8);
        glBindTextureUnit(1, this.fb.getDepthTex().id);
        glBindTextureUnit(2, this.colourTex.id);

        glDispatchCompute((viewport.width+31)/32, (viewport.height+31)/32, 1);

        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        if (this.lastAtmosphericFog != VoxyConfig.CONFIG.atmosphericFog || 
            this.lastEnvironmentalFog != VoxyConfig.CONFIG.environmentalFog ||
            this.lastRenderVanillaFog != VoxyConfig.CONFIG.renderVanillaFog) {
            this.rebuildFinalBlit();
        }
        this.finalBlit.bind();

        if (VoxyConfig.CONFIG.environmentalFog && VoxyConfig.CONFIG.renderVanillaFog) {
            try (var stack = MemoryStack.stackPush()) {
                float start = RenderSystem.getShaderFogStart();
                float end = RenderSystem.getShaderFogEnd();
                float diff = end - start;
                if (Math.abs(diff) < 0.0001f) diff = 0.0001f;
                float invDiff = 1.0f / diff;
                var params = stack.floats(end, invDiff, -start * invDiff);
                nglUniform3fv(4, 1, MemoryUtil.memAddress(params));

                var color = RenderSystem.getShaderFogColor();
                var colorParams = stack.floats(color[0], color[1], color[2]);
                nglUniform3fv(5, 1, MemoryUtil.memAddress(colorParams));
            }
        }

        if (VoxyConfig.CONFIG.atmosphericFog) {
            try (var stack = MemoryStack.stackPush()) {
                // density, falloff, start, unused
                // Further increased density and adjusted parameters for better visibility
                var params = stack.floats(0.005f, 1.2f, 32.0f, 0.0f);
                nglUniform4fv(6, 1, MemoryUtil.memAddress(params));
                
                // Use vanilla fog color for atmospheric fog to match the environment
                var color = RenderSystem.getShaderFogColor();
                var colorParams = stack.floats(color[0], color[1], color[2]);
                nglUniform3fv(7, 1, MemoryUtil.memAddress(colorParams));
            }
        }

        glBindTextureUnit(3, this.colourSSAOTex.id);

        //Do alpha blending

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        AbstractRenderPipeline.transformBlitDepth(this.finalBlit, this.fb.getDepthTex().id, sourceFrameBuffer, viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
        glDisable(GL_BLEND);
        //glBlitNamedFramebuffer(this.fbSSAO.id, sourceFrameBuffer, 0,0, viewport.width, viewport.height, 0,0, viewport.width, viewport.height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    public void free() {
        this.finalBlit.delete();
        this.ssaoCompute.free();
        this.fb.free();
        this.fbSSAO.free();
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
        }
        super.free0();
    }
}
