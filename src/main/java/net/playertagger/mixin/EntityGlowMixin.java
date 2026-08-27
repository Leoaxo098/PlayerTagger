package net.playertagger.mixin;

import net.minecraft.entity.Entity;
import net.playertagger.render.GlowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why this mixin exists:
 * <p>
 * Minecraft's own "glowing" outline render pass (the same one used by the
 * vanilla Glowing status effect) is driven entirely by
 * {@link Entity#isGlowing()}. That flag is normally backed by a
 * server-synced entity data tracker value, which is exactly what the mod
 * spec says PlayerTagger must never touch — writing to it would mean
 * sending/echoing entity state that could theoretically be observed by other
 * systems and would not survive a fresh data update from the server anyway.
 * <p>
 * What this mixin modifies:
 * <p>
 * We inject at the {@code RETURN} of {@link Entity#isGlowing()} and, if the
 * entity is a player tagged in {@link net.playertagger.manager.PlayerTagManager},
 * override the return value to {@code true}. This never writes to the
 * entity's data tracker, never sends a packet, and only affects what value
 * this client's own code observes when it asks "is this entity glowing?" —
 * which is exactly the local-only rendering hook the spec calls for.
 * <p>
 * Why Fabric API alone is insufficient:
 * <p>
 * Fabric API does not expose a client-only "force this specific entity to
 * render with an outline" hook; the vanilla renderer only consults
 * {@code Entity#isGlowing()} (and a couple of unrelated spectator checks),
 * so the only way to influence the outline pass without a full custom
 * shader/render-layer (explicitly out of scope per the spec) is to
 * intercept that one method.
 */
@Mixin(Entity.class)
public abstract class EntityGlowMixin {

    @Inject(method = "isGlowing", at = @At("RETURN"), cancellable = true)
    private void playertagger$forceGlowForTaggedPlayers(CallbackInfoReturnable<Boolean> cir) {
        // "this" is safe to cast: the mixin target is Entity itself, and
        // GlowRenderer performs its own instanceof PlayerEntity check so
        // non-player entities are always left untouched.
        if (!cir.getReturnValueZ() && GlowRenderer.shouldRenderOutline((Entity) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
