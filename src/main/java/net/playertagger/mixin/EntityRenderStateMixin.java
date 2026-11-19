package net.playertagger.mixin;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.playertagger.render.PlayerOutlineState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements PlayerOutlineState {
    @Unique private int playertagger$outlineColor;

    @Override public int playertagger$getOutlineColor() { return playertagger$outlineColor; }
    @Override public void playertagger$setOutlineColor(int color) { playertagger$outlineColor = color; }
}
