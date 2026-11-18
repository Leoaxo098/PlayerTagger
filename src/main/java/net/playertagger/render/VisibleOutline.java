package net.playertagger.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Defines;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderProgramKey;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Writes the visible player silhouette into a private framebuffer before drawing its outline. */
public final class VisibleOutline {
    private static final ShaderProgramKey MASK = new ShaderProgramKey(
            Identifier.of("playertagger", "core/visible_mask"),
            VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, Defines.EMPTY);
    private static final ShaderProgramKey COMPOSITE = new ShaderProgramKey(
            Identifier.of("playertagger", "core/visible_outline"), VertexFormats.POSITION, Defines.EMPTY);
    private static final List<Stamp> STAMPS = new ArrayList<>();
    private static BufferAllocator allocator;
    private static Framebuffer mask;
    private static Framebuffer sceneSnapshot;
    private static boolean collecting;

    private VisibleOutline() { }

    public static void begin() {
        clearStamps();
        collecting = true;
    }

    public static void submit(EntityModel<LivingEntityRenderState> model, LivingEntityRenderState state,
            MatrixStack matrices, Identifier skin, int color) {
        if (!collecting) return;
        if (allocator == null) allocator = new BufferAllocator(16_384);

        double distance = Math.sqrt(state.squaredDistanceToCamera);
        int coverage = 1 + (int) Math.round(254 * Math.clamp((distance - 24) / 24, 0.0, 1.0));
        int maskColor = (coverage << 24) | (color & 0xFFFFFF);
        var vertices = new BufferBuilder(allocator, VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
        // Both the model and its render state are reused by vanilla after this invocation.
        model.render(matrices, vertices, 0, OverlayTexture.DEFAULT_UV, maskColor);
        var buffer = vertices.endNullable();
        if (buffer != null) STAMPS.add(new Stamp(buffer, skin));
    }

    public static void draw() {
        collecting = false;
        try {
            if (STAMPS.isEmpty()) return;
            var client = MinecraftClient.getInstance();
            Framebuffer scene = client.getFramebuffer();
            if (scene.getDepthAttachment() <= 0 || scene.getColorAttachment() <= 0) return;
            if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != scene.fbo
                    || GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) return;
            var maskProgram = client.getShaderLoader().getOrCreateProgram(MASK);
            var outlineProgram = client.getShaderLoader().getOrCreateProgram(COMPOSITE);
            if (maskProgram == null || outlineProgram == null) return;

            int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            var previousProgram = RenderSystem.getShader();
            int texture0 = RenderSystem.getShaderTexture(0);
            int texture1 = RenderSystem.getShaderTexture(1);
            int texture2 = RenderSystem.getShaderTexture(2);
            boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
            int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            try {
                if (mask == null) {
                    mask = new SimpleFramebuffer(scene.textureWidth, scene.textureHeight, true);
                    mask.setClearColor(0, 0, 0, 0);
                    sceneSnapshot = new SimpleFramebuffer(scene.textureWidth, scene.textureHeight, true);
                } else if (mask.textureWidth != scene.textureWidth || mask.textureHeight != scene.textureHeight) {
                    mask.resize(scene.textureWidth, scene.textureHeight);
                    sceneSnapshot.resize(scene.textureWidth, scene.textureHeight);
                }
                RenderSystem.depthMask(true);
                mask.clear();
                mask.copyDepthFrom(scene);
                // Sample a copy instead of reading the scene's attached depth during compositing.
                sceneSnapshot.copyDepthFrom(scene);
                mask.beginWrite(true);
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.disableCull();
                RenderSystem.disableBlend();
                RenderSystem.setShader(maskProgram);
                for (int i = 0; i < STAMPS.size(); i++) {
                    Stamp stamp = STAMPS.get(i);
                    RenderSystem.setShaderTexture(0, stamp.skin());
                    // BufferRenderer takes ownership of the built buffer.
                    STAMPS.set(i, null);
                    BufferRenderer.drawWithGlobalProgram(stamp.buffer());
                }

                scene.beginWrite(true);
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShader(outlineProgram);
                RenderSystem.setShaderTexture(0, mask.getColorAttachment());
                RenderSystem.setShaderTexture(1, mask.getDepthAttachment());
                RenderSystem.setShaderTexture(2, sceneSnapshot.getDepthAttachment());
                renderComposite();
            } finally {
                scene.beginWrite(true);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                RenderSystem.setShader(previousProgram);
                RenderSystem.setShaderTexture(0, texture0);
                RenderSystem.setShaderTexture(1, texture1);
                RenderSystem.setShaderTexture(2, texture2);
                RenderSystem.depthMask(depthWrite);
                RenderSystem.depthFunc(depthFunction);
                if (depthTest) RenderSystem.enableDepthTest();
                else RenderSystem.disableDepthTest();
                if (culling) RenderSystem.enableCull();
                else RenderSystem.disableCull();
                RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                if (blending) RenderSystem.enableBlend();
                else RenderSystem.disableBlend();
            }
        } finally {
            clearStamps();
        }
    }

    private static void renderComposite() {
        var vertices = new BufferBuilder(allocator, VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION);
        vertices.vertex(-1, -1, 0);
        vertices.vertex(3, -1, 0);
        vertices.vertex(-1, 3, 0);
        BufferRenderer.drawWithGlobalProgram(vertices.end());
    }

    private static void clearStamps() {
        for (Stamp stamp : STAMPS) {
            if (stamp != null) stamp.buffer().close();
        }
        STAMPS.clear();
        if (allocator != null) allocator.clear();
    }

    public static void close() {
        collecting = false;
        clearStamps();
        if (allocator != null) {
            allocator.close();
            allocator = null;
        }
        if (mask != null) {
            mask.delete();
            mask = null;
        }
        if (sceneSnapshot != null) {
            sceneSnapshot.delete();
            sceneSnapshot = null;
        }
    }

    private record Stamp(BuiltBuffer buffer, Identifier skin) { }
}
