package net.playertagger.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderPipelines.class)
public interface RenderPipelineInvoker {
    @Invoker("registerOptional")
    static RenderPipeline playertagger$registerOptional(RenderPipeline pipeline) {
        throw new AssertionError();
    }
}
