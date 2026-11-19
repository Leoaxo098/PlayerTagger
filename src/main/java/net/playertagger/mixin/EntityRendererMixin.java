package net.playertagger.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.playertagger.manager.PlayerTagManager;
import net.playertagger.render.OutlineVisibility;
import net.playertagger.render.PlayerOutlineState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void playertagger$updateOutline(Entity entity, EntityRenderState state, float tickDelta, CallbackInfo ci) {
        PlayerOutlineState outline = (PlayerOutlineState) state;
        // Minecraft reuses render states, so always clear the previous entity's color.
        outline.playertagger$setOutlineColor(0);
        if (!(entity instanceof Player player)) return;
        Minecraft client = Minecraft.getInstance();
        PlayerTagManager manager = PlayerTagManager.getInstance();
        if (player == client.player || client.level == null
                || !OutlineVisibility.shouldShow(manager.isEnabled(), manager.isTagged(player.getUUID()), player.isInvisible())) return;

        outline.playertagger$setOutlineColor(manager.getColor(player.getUUID()));
    }
}
