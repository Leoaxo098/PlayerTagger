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
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Writes the visible player silhouette into a private framebuffer before drawing its outline. */
public final class VisibleOutline {
    private static final RenderPipeline MASK = RenderPipeline.builder()
            .withLocation(Identifier.of("playertagger", "pipeline/visible_mask"))
            .withVertexShader("core/rendertype_outline")
            .withFragmentShader(Identifier.of("playertagger", "core/visible_mask"))
            .withUniform("ModelViewMat", UniformType.MATRIX4X4)
            .withUniform("ProjMat", UniformType.MATRIX4X4)
            .withSampler("Sampler0")
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
            .withVertexFormat(VertexFormats.POSITION, VertexFormat.DrawMode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build();

    private static final List<Stamp> STAMPS = new ArrayList<>();
    private static BufferAllocator allocator;
    private static Framebuffer mask;
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
        var vertices = new BufferBuilder(allocator, MASK.getVertexFormatMode(), MASK.getVertexFormat());
        // Both the model and its render state are reused by vanilla after this invocation.
        model.render(matrices, vertices, 0, OverlayTexture.DEFAULT_UV, maskColor);
        var buffer = vertices.endNullable();
        if (buffer != null) STAMPS.add(new Stamp(buffer, skin));
    }

    public static void draw() {
        collecting = false;
        try {
            if (STAMPS.isEmpty()) return;
            Framebuffer scene = MinecraftClient.getInstance().getFramebuffer();
            if (scene.getDepthAttachment() == null || scene.getColorAttachment() == null) return;

            if (mask == null) {
                mask = new SimpleFramebuffer("PlayerTagger mask", scene.textureWidth, scene.textureHeight, true);
            } else if (mask.textureWidth != scene.textureWidth || mask.textureHeight != scene.textureHeight) {
                mask.resize(scene.textureWidth, scene.textureHeight);
            }

            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.clearColorTexture(mask.getColorAttachment(), 0);
            mask.copyDepthFrom(scene);
            stampVisibleModels();

            var vertices = new BufferBuilder(allocator, COMPOSITE.getVertexFormatMode(), COMPOSITE.getVertexFormat());
            vertices.vertex(-1, -1, 0);
            vertices.vertex(3, -1, 0);
            vertices.vertex(-1, 3, 0);
            try (var triangle = vertices.end()) {
                var vertexBuffer = COMPOSITE.getVertexFormat().uploadImmediateVertexBuffer(triangle.getBuffer());
                try (var pass = encoder.createRenderPass(scene.getColorAttachment(), OptionalInt.empty())) {
                    pass.setPipeline(COMPOSITE);
                    pass.bindSampler("Mask", mask.getColorAttachment());
                    pass.bindSampler("MaskDepth", mask.getDepthAttachment());
                    pass.bindSampler("SceneDepth", scene.getDepthAttachment());
                    pass.setVertexBuffer(0, vertexBuffer);
                    pass.draw(0, 3);
                }
            }
        } finally {
            clearStamps();
        }
    }

    private static void stampVisibleModels() {
        var textures = MinecraftClient.getInstance().getTextureManager();
        var indices = RenderSystem.getSequentialBuffer(MASK.getVertexFormatMode());
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        for (Stamp stamp : STAMPS) {
            var model = stamp.buffer();
            var vertexBuffer = MASK.getVertexFormat().uploadImmediateVertexBuffer(model.getBuffer());
            int indexCount = model.getDrawParameters().indexCount();
            var indexBuffer = indices.getIndexBuffer(indexCount);
            try (var pass = encoder.createRenderPass(mask.getColorAttachment(), OptionalInt.empty(),
                    mask.getDepthAttachment(), OptionalDouble.empty())) {
                pass.setPipeline(MASK);
                pass.setUniform("ModelViewMat", RenderSystem.getModelViewMatrix());
                pass.setUniform("ProjMat", RenderSystem.getProjectionMatrix());
                pass.bindSampler("Sampler0", textures.getTexture(stamp.skin()).getGlTexture());
                pass.setVertexBuffer(0, vertexBuffer);
                pass.setIndexBuffer(indexBuffer, indices.getIndexType());
                pass.drawIndexed(0, indexCount);
            }
        }
    }

    private static void clearStamps() {
        for (Stamp stamp : STAMPS) stamp.buffer().close();
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
    }

    private record Stamp(BuiltBuffer buffer, Identifier skin) { }
}
