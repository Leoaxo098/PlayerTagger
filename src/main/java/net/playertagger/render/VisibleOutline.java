package net.playertagger.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.OutputTarget;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Draws a skin silhouette into a private mask, then colors its edges in screen space. */
public final class VisibleOutline {
    private static final RenderPipeline MASK = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
                    .withLocation(Identifier.of("playertagger", "pipeline/visible_mask"))
                    .withVertexShader("core/rendertype_outline")
                    .withFragmentShader(Identifier.of("playertagger", "core/visible_mask"))
                    .withSampler("Sampler0")
                    .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(true)
                    .withCull(false)
                    .build());

    private static final RenderPipeline COMPOSITE = RenderPipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.of("playertagger", "pipeline/visible_outline"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("playertagger", "core/visible_outline"))
            .withSampler("Mask")
            .withSampler("MaskDepth")
            .withSampler("SceneDepth")
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());

    private static final List<Stamp> STAMPS = new ArrayList<>();
    private static final OutputTarget MASK_TARGET = new OutputTarget("PlayerTagger mask", () -> VisibleOutline.mask);
    private static Framebuffer mask;
    private static boolean collecting;

    private VisibleOutline() { }

    public static void begin() {
        STAMPS.clear();
        collecting = true;
    }

    public static void submit(EntityModel<LivingEntityRenderState> model, LivingEntityRenderState state,
            MatrixStack matrices, Identifier skin, int color) {
        if (collecting) {
            STAMPS.add(new Stamp(model, state, matrices.peek().copy(), skin, color));
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

            var nearest = RenderSystem.getSamplerCache().get(FilterMode.NEAREST);
            try (var pass = device.createCommandEncoder().createRenderPass(
                    () -> "PlayerTagger outline", scene.getColorAttachmentView(), OptionalInt.empty())) {
                pass.setPipeline(COMPOSITE);
                pass.bindTexture("Mask", mask.getColorAttachmentView(), nearest);
                pass.bindTexture("MaskDepth", mask.getDepthAttachmentView(), nearest);
                pass.bindTexture("SceneDepth", scene.getDepthAttachmentView(), nearest);
                pass.draw(0, 3);
            }
        } finally {
            STAMPS.clear();
        }
    }

    private static void stampVisibleModels() {
        var previousColor = RenderSystem.outputColorTextureOverride;
        var previousDepth = RenderSystem.outputDepthTextureOverride;
        RenderSystem.outputColorTextureOverride = mask.getColorAttachmentView();
        RenderSystem.outputDepthTextureOverride = mask.getDepthAttachmentView();
        try (var allocator = new BufferAllocator(16_384)) {
            var buffers = VertexConsumerProvider.immediate(allocator);
            var matrices = new MatrixStack();
            for (Stamp stamp : STAMPS) {
                matrices.peek().copy(stamp.transform());
                stamp.model().setAngles(stamp.state());
                var setup = RenderSetup.builder(MASK).outputTarget(MASK_TARGET)
                        .texture("Sampler0", stamp.skin()).build();
                var layer = RenderLayer.of("playertagger_visible_mask", setup);
                // Alpha reserves zero for the background and stores how much to fill at distance.
                double distance = Math.sqrt(stamp.state().squaredDistanceToCamera);
                int coverage = 1 + (int) Math.round(254 * Math.clamp((distance - 24) / 24, 0.0, 1.0));
                int maskColor = (coverage << 24) | (stamp.color() & 0xFFFFFF);
                stamp.model().render(matrices, buffers.getBuffer(layer), stamp.state().light,
                        OverlayTexture.DEFAULT_UV, maskColor);
                buffers.draw(layer);
            }
        } finally {
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
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
                         MatrixStack.Entry transform, Identifier skin, int color) { }
}
