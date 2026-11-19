package net.playertagger.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
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
                    .withSampler("Sampler0").build())
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(false)
            .build();

    private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("playertagger", "pipeline/visible_outline"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("playertagger", "core/visible_outline"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withSampler("Mask").withSampler("MaskDepth").withSampler("SceneDepth").build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();

    private static final List<Stamp> STAMPS = new ArrayList<>();
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
            RenderTarget scene = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (scene.getDepthTextureView() == null || scene.getColorTextureView() == null) return;

            // Skip alternate targets whose depth doesn't match the main scene.
            if (RenderSystem.outputDepthTextureOverride != null
                    && RenderSystem.outputDepthTextureOverride != scene.getDepthTextureView()) return;
            if (RenderSystem.outputColorTextureOverride != null
                    && RenderSystem.outputColorTextureOverride != scene.getColorTextureView()) return;

            if (mask == null) {
                mask = new TextureTarget("PlayerTagger mask", scene.width, scene.height, true, GpuFormat.RGBA8_UNORM);
            } else if (mask.width != scene.width || mask.height != scene.height) {
                mask.resize(scene.width, scene.height);
            }
            var device = RenderSystem.getDevice();
            device.createCommandEncoder().clearColorTexture(mask.getColorTexture(), new Vector4f(0.0f));
            mask.copyDepthFrom(scene);
            stampVisibleModels();

            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (var pass = device.createCommandEncoder().createRenderPass(
                    () -> "PlayerTagger outline", scene.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(COMPOSITE);
                pass.bindTexture("Mask", mask.getColorTextureView(), nearest);
                pass.bindTexture("MaskDepth", mask.getDepthTextureView(), nearest);
                pass.bindTexture("SceneDepth", scene.getDepthTextureView(), nearest);
                pass.draw(3, 1, 0, 0);
            }
        } finally {
            STAMPS.clear();
        }
    }

    private static void stampVisibleModels() {
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
                        pass.setPipeline(MASK);
                        RenderSystem.bindDefaultUniforms(pass);
                        pass.setUniform("DynamicTransforms", transforms);
                        pass.bindTexture("Sampler0", skin.getTextureView(), skin.getSampler());
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
