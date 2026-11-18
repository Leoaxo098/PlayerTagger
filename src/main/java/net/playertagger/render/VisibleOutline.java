package net.playertagger.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Vector4f;

/** Draws a skin silhouette into a private mask, then colors its edges in screen space. */
public final class VisibleOutline {
    private static final RenderPipeline MASK = RenderPipeline.builder()
                    .withLocation(Identifier.of("playertagger", "pipeline/visible_mask"))
                    .withVertexShader("core/rendertype_outline")
                    .withFragmentShader(Identifier.of("playertagger", "core/visible_mask"))
                    .withSampler("Sampler0")
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(true)
                    .withCull(false)
                    .build();

    private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(Identifier.of("playertagger", "pipeline/visible_outline"))
            .withVertexShader(Identifier.of("playertagger", "core/visible_outline"))
            .withFragmentShader(Identifier.of("playertagger", "core/visible_outline"))
            .withSampler("Mask")
            .withSampler("MaskDepth")
            .withSampler("SceneDepth")
            .withVertexFormat(VertexFormats.POSITION, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build();

    private static final List<Stamp> STAMPS = new ArrayList<>();
    private static Framebuffer mask;
    private static boolean collecting;

    private VisibleOutline() { }

    public static void begin() {
        STAMPS.clear();
        collecting = true;
    }

    public static void submit(EntityModel<LivingEntityRenderState> model, LivingEntityRenderState state,
            MatrixStack matrices, Identifier skin, int light, int color) {
        if (collecting) {
            STAMPS.add(new Stamp(model, state, matrices.peek().copy(), skin, light, color));
        }
    }

    public static void draw() {
        collecting = false;
        try {
            if (STAMPS.isEmpty()) return;
            Framebuffer scene = MinecraftClient.getInstance().getFramebuffer();
            if (scene.getDepthAttachmentView() == null || scene.getColorAttachmentView() == null) return;

            // Skip alternate targets whose depth doesn't match the main scene.
            if (RenderSystem.outputDepthTextureOverride != null
                    && RenderSystem.outputDepthTextureOverride != scene.getDepthAttachmentView()) return;
            if (RenderSystem.outputColorTextureOverride != null
                    && RenderSystem.outputColorTextureOverride != scene.getColorAttachmentView()) return;

            if (mask == null) {
                mask = new SimpleFramebuffer("PlayerTagger mask", scene.textureWidth, scene.textureHeight, true);
            } else if (mask.textureWidth != scene.textureWidth || mask.textureHeight != scene.textureHeight) {
                mask.resize(scene.textureWidth, scene.textureHeight);
            }
            var device = RenderSystem.getDevice();
            device.createCommandEncoder().clearColorTexture(mask.getColorAttachment(), 0);
            mask.copyDepthFrom(scene);
            stampVisibleModels();

            var quadIndices = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
            var quadIndexBuffer = quadIndices.getIndexBuffer(6);
            try (var pass = device.createCommandEncoder().createRenderPass(
                    () -> "PlayerTagger outline", scene.getColorAttachmentView(), OptionalInt.empty())) {
                pass.setPipeline(COMPOSITE);
                pass.bindSampler("Mask", mask.getColorAttachmentView());
                pass.bindSampler("MaskDepth", mask.getDepthAttachmentView());
                pass.bindSampler("SceneDepth", scene.getDepthAttachmentView());
                pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
                pass.setIndexBuffer(quadIndexBuffer, quadIndices.getIndexType());
                pass.drawIndexed(0, 0, 6, 1);
            }
        } finally {
            STAMPS.clear();
        }
    }

    private static void stampVisibleModels() {
        var transforms = RenderSystem.getDynamicUniforms().write(RenderSystem.getModelViewMatrix(),
                new Vector4f(1.0f), RenderSystem.getModelOffset(), RenderSystem.getTextureMatrix(),
                RenderSystem.getShaderLineWidth());
        var mode = MASK.getVertexFormatMode();
        var format = MASK.getVertexFormat();
        var indices = RenderSystem.getSequentialBuffer(mode);
        var textures = MinecraftClient.getInstance().getTextureManager();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var allocator = new BufferAllocator(16_384)) {
            var matrices = new MatrixStack();
            for (Stamp stamp : STAMPS) {
                matrices.peek().getPositionMatrix().set(stamp.transform().getPositionMatrix());
                matrices.peek().getNormalMatrix().set(stamp.transform().getNormalMatrix());
                stamp.model().setAngles(stamp.state());
                var vertices = new BufferBuilder(allocator, mode, format);
                // Alpha reserves zero for the background and stores how much to fill at distance.
                double distance = Math.sqrt(stamp.state().squaredDistanceToCamera);
                int coverage = 1 + (int) Math.round(254 * Math.clamp((distance - 24) / 24, 0.0, 1.0));
                int maskColor = (coverage << 24) | (stamp.color() & 0xFFFFFF);
                stamp.model().render(matrices, vertices, stamp.light(),
                        OverlayTexture.DEFAULT_UV, maskColor);
                try (var model = vertices.endNullable()) {
                    if (model == null) continue;
                    var vertexBuffer = format.uploadImmediateVertexBuffer(model.getBuffer());
                    int indexCount = model.getDrawParameters().indexCount();
                    var indexBuffer = indices.getIndexBuffer(indexCount);
                    try (var pass = encoder.createRenderPass(() -> "PlayerTagger mask",
                            mask.getColorAttachmentView(), OptionalInt.empty(),
                            mask.getDepthAttachmentView(), OptionalDouble.empty())) {
                        pass.setPipeline(MASK);
                        RenderSystem.bindDefaultUniforms(pass);
                        pass.setUniform("DynamicTransforms", transforms);
                        pass.bindSampler("Sampler0", textures.getTexture(stamp.skin()).getGlTextureView());
                        pass.setVertexBuffer(0, vertexBuffer);
                        pass.setIndexBuffer(indexBuffer, indices.getIndexType());
                        pass.drawIndexed(0, 0, indexCount, 1);
                    }
                }
            }
        }
    }

    public static void close() {
        if (mask != null) {
            mask.delete();
            mask = null;
        }
        STAMPS.clear();
        collecting = false;
    }

    private record Stamp(EntityModel<LivingEntityRenderState> model, LivingEntityRenderState state,
                         MatrixStack.Entry transform, Identifier skin, int light, int color) { }
}
