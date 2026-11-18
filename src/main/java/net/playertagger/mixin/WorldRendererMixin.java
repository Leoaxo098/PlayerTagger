package net.playertagger.mixin;

import net.minecraft.client.render.WorldRenderer;
import net.playertagger.render.VisibleOutline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "method_62214", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;pushEntityRenders(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/state/WorldRenderState;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V"))
    private void playertagger$beginOutline(CallbackInfo ci) {
        VisibleOutline.begin();
    }

    @Inject(method = "method_62214", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/render/BlockRenderLayerGroup;TRANSLUCENT:Lnet/minecraft/client/render/BlockRenderLayerGroup;"))
    private void playertagger$composeOutline(CallbackInfo ci) {
        VisibleOutline.draw();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void playertagger$freeMask(CallbackInfo ci) {
        VisibleOutline.close();
    }
}
