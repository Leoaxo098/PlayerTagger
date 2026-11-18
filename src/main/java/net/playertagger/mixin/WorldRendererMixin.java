package net.playertagger.mixin;

import net.minecraft.client.render.WorldRenderer;
import net.playertagger.render.VisibleOutline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void playertagger$freeMask(CallbackInfo ci) {
        VisibleOutline.close();
    }
}
