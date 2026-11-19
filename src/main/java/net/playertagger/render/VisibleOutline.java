package net.playertagger.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.playertagger.mixin.RenderPipelineInvoker;
import org.joml.Vector4f;

/** Draws a skin silhouette into a private mask, then colors its edges in screen space. */
public final class VisibleOutline {
    private static final RenderPipeline MASK = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("playertagger", "pipeline/visible_mask"))
            .withVertexShader("core/rendertype_outline")
            .withFragmentShader(Identifier.fromNamespaceAndPath("playertagger", "core/visible_mask"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(ColorTargetState.DEFAULT)
            // Minecraft 26.3 clears depth to zero and stores closer fragments at higher depth values.
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(false)
            .build();

    private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("playertagger", "pipeline/visible_outline"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("playertagger", "core/visible_outline"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("Mask", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("MaskDepth", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();

    private static final List<Stamp> STAMPS = new ArrayList<>();
    private static RenderTarget mask;
    private static boolean collecting;

    private VisibleOutline() { }

    public static void registerPipelines() {
        RenderPipelineInvoker.playertagger$registerOptional(MASK);
        RenderPipelineInvoker.playertagger$registerOptional(COMPOSITE);
    }

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
            RenderTarget scene = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (scene.getDepthTextureView() == null || scene.getColorTextureView() == null) return;
            if (scene.getDepthTexture().getFormat() != GpuFormat.D32_FLOAT
                    || scene.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM) return;

            var maskPipeline = RenderSystem.getCompiledPipelineNullable(MASK);
            var compositePipeline = RenderSystem.getCompiledPipelineNullable(COMPOSITE);
            if (maskPipeline == null || compositePipeline == null) return;

            if (mask == null) {
                mask = new TextureTarget("PlayerTagger mask", scene.width, scene.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            } else if (mask.width != scene.width || mask.height != scene.height) {
                mask.resize(scene.width, scene.height);
            }
            var device = RenderSystem.getDevice();
            device.createCommandEncoder().clearColorTexture(mask.getColorTexture(), new Vector4f(0.0f));
            mask.copyDepthFrom(scene);
            stampVisibleModels(maskPipeline);

            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (var pass = device.createCommandEncoder().createRenderPass(
                    () -> "PlayerTagger outline", scene.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(compositePipeline);
                pass.setUniform("Mask", mask.getColorTextureView(), nearest);
                pass.setUniform("MaskDepth", mask.getDepthTextureView(), nearest);
                pass.setUniform("SceneDepth", scene.getDepthTextureView(), nearest);
                pass.draw(3, 1, 0, 0);
            }
        } finally {
            STAMPS.clear();
        }
    }

    private static void stampVisibleModels(CompiledRenderPipeline maskPipeline) {
        var device = RenderSystem.getDevice();
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
        var textures = Minecraft.getInstance().getTextureManager();
        try (var allocator = new ByteBufferBuilder(16_384)) {
            var matrices = new PoseStack();
            for (Stamp stamp : STAMPS) {
                matrices.last().set(stamp.transform());
                stamp.model().setupAnim(stamp.state());
                // Alpha reserves zero for the background and stores how much to fill at distance.
                double distance = Math.sqrt(stamp.state().distanceToCameraSq);
                int coverage = 1 + (int) Math.round(254 * Math.clamp((distance - 24) / 24, 0.0, 1.0));
                int maskColor = (coverage << 24) | (stamp.color() & 0xFFFFFF);
                var vertices = new BufferBuilder(allocator, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
                stamp.model().renderToBuffer(matrices, vertices, stamp.state().lightCoords,
                        OverlayTexture.NO_OVERLAY, maskColor);
                try (var mesh = vertices.build()) {
                    if (mesh == null) continue;
                    int indexCount = mesh.drawState().indexCount();
                    var indexBuffer = indices.getBuffer(indexCount);
                    var skin = textures.getTexture(stamp.skin());
                    try (var vertexBuffer = device.createBuffer(() -> "PlayerTagger silhouette vertices",
                                GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                         var pass = device.createCommandEncoder().createRenderPass(
                                () -> "PlayerTagger mask", mask.getColorTextureView(), Optional.empty(),
                                mask.getDepthTextureView(), OptionalDouble.empty())) {
                        pass.setPipeline(maskPipeline);
                        RenderSystem.bindDefaultUniforms(pass);
                        pass.setUniform("DynamicTransforms", transforms);
                        pass.setUniform("Sampler0", skin.getTextureView(), skin.getSampler());
                        pass.setVertexBuffer(0, vertexBuffer.slice());
                        pass.setIndexBuffer(indexBuffer, indices.type());
                        pass.drawIndexed(indexCount, 1, 0, 0, 0);
                    }
                }
            }
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
