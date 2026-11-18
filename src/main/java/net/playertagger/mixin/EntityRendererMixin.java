package net.playertagger.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.playertagger.manager.PlayerTagManager;
import net.playertagger.render.OutlineVisibility;
import net.playertagger.render.PlayerOutlineState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private void playertagger$updateOutline(Entity entity, EntityRenderState state, float tickDelta, CallbackInfo ci) {
        PlayerOutlineState outline = (PlayerOutlineState) state;
        // Minecraft reuses render states, so always clear the previous entity's color.
        outline.playertagger$setOutlineColor(0);
        if (!(entity instanceof PlayerEntity player)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerTagManager manager = PlayerTagManager.getInstance();
        if (player == client.player || client.world == null
                || !OutlineVisibility.shouldShow(manager.isEnabled(), manager.isTagged(player.getUuid()), player.isInvisible())) return;

        outline.playertagger$setOutlineColor(manager.getColor(player.getUuid()));
    }
}
