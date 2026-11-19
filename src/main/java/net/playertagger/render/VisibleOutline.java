package net.playertagger.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.playertagger.mixin.RenderTypeInvoker;

/** Draws a skin silhouette into a private mask, then colors its edges in screen space. */
public final class VisibleOutline {
    private static final RenderPipeline MASK = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("playertagger", "pipeline/visible_mask"))
                    .withVertexShader("core/rendertype_outline")
                    .withFragmentShader(Identifier.fromNamespaceAndPath("playertagger", "core/visible_mask"))
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .withSampler("Sampler0")
                    .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                    .withCull(false)
                    .build();

    private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("playertagger", "pipeline/visible_outline"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("playertagger", "core/visible_outline"))
            .withSampler("Mask")
            .withSampler("MaskDepth")
            .withSampler("SceneDepth")
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();

    private static final List<Stamp> STAMPS = new ArrayList<>();
    private static final OutputTarget MASK_TARGET = new OutputTarget("PlayerTagger mask", () -> VisibleOutline.mask);
    private static RenderTarget mask;
    private static boolean collecting;

    private VisibleOutline() { }

    public static void begin() {
        STAMPS.clear();
        collecting = true;
    }

    public static void submit(EntityModel<LivingEntityRenderState> model, LivingEntityRenderState state,
            PoseStack matrices, Identifier skin, int color) {
        if (collecting) {
            STAMPS.add(new Stamp(model, state, matrices.last().copy(), skin, color));
        }
    }

    public static void draw() {
        collecting = false;
        try {
            if (STAMPS.isEmpty()) return;
            RenderTarget scene = Minecraft.getInstance().getMainRenderTarget();
            if (scene.getDepthTextureView() == null || scene.getColorTextureView() == null) return;

            // Skip alternate targets whose depth doesn't match the main scene.
            if (RenderSystem.outputDepthTextureOverride != null
                    && RenderSystem.outputDepthTextureOverride != scene.getDepthTextureView()) return;
            if (RenderSystem.outputColorTextureOverride != null
                    && RenderSystem.outputColorTextureOverride != scene.getColorTextureView()) return;

            if (mask == null) {
                mask = new TextureTarget("PlayerTagger mask", scene.width, scene.height, true);
            } else if (mask.width != scene.width || mask.height != scene.height) {
                mask.resize(scene.width, scene.height);
            }
            var device = RenderSystem.getDevice();
            device.createCommandEncoder().clearColorTexture(mask.getColorTexture(), 0);
            mask.copyDepthFrom(scene);
            stampVisibleModels();

            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (var pass = device.createCommandEncoder().createRenderPass(
                    () -> "PlayerTagger outline", scene.getColorTextureView(), OptionalInt.empty())) {
                pass.setPipeline(COMPOSITE);
                pass.bindTexture("Mask", mask.getColorTextureView(), nearest);
                pass.bindTexture("MaskDepth", mask.getDepthTextureView(), nearest);
                pass.bindTexture("SceneDepth", scene.getDepthTextureView(), nearest);
                pass.draw(0, 3);
            }
        } finally {
            STAMPS.clear();
        }
    }

    private static void stampVisibleModels() {
        var previousColor = RenderSystem.outputColorTextureOverride;
        var previousDepth = RenderSystem.outputDepthTextureOverride;
        RenderSystem.outputColorTextureOverride = mask.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = mask.getDepthTextureView();
        try (var allocator = new ByteBufferBuilder(16_384)) {
            var buffers = MultiBufferSource.immediate(allocator);
            var matrices = new PoseStack();
            for (Stamp stamp : STAMPS) {
                matrices.last().set(stamp.transform());
                stamp.model().setupAnim(stamp.state());
                var setup = RenderSetup.builder(MASK).setOutputTarget(MASK_TARGET)
                        .withTexture("Sampler0", stamp.skin()).createRenderSetup();
                RenderType layer = RenderTypeInvoker.playertagger$create("playertagger_visible_mask", setup);
                // Alpha reserves zero for the background and stores how much to fill at distance.
                double distance = Math.sqrt(stamp.state().distanceToCameraSq);
                int coverage = 1 + (int) Math.round(254 * Math.clamp((distance - 24) / 24, 0.0, 1.0));
                int maskColor = (coverage << 24) | (stamp.color() & 0xFFFFFF);
                stamp.model().renderToBuffer(matrices, buffers.getBuffer(layer), stamp.state().lightCoords,
                        OverlayTexture.NO_OVERLAY, maskColor);
                buffers.endBatch(layer);
            }
        } finally {
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
        }
    }

    public static void close() {
        if (mask != null) {
            mask.destroyBuffers();
            mask = null;
        }
        STAMPS.clear();
        collecting = false;
    }

    private record Stamp(EntityModel<LivingEntityRenderState> model, LivingEntityRenderState state,
                         PoseStack.Pose transform, Identifier skin, int color) { }
}
