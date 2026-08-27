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
 * When the vanilla renderer draws an entity's glowing outline, it colors
 * that outline using {@link Entity#getTeamColorValue()} — normally the
 * entity's scoreboard team color, or white if it has none. The spec
 * explicitly forbids scoreboard tricks, and each tagged player needs their
 * own independently configurable color (stored in
 * {@code config/playertagger.json}), so we cannot rely on real teams.
 * <p>
 * What this mixin modifies:
 * <p>
 * We inject at the {@code RETURN} of {@link Entity#getTeamColorValue()} and,
 * for a tagged player, replace the returned color with the one stored for
 * that player by {@link net.playertagger.render.GlowColorManager}. This is
 * read-only from the renderer's perspective — it never creates or modifies
 * a scoreboard team, and has no effect on anything except which color this
 * client happens to paint the outline pixels with.
 * <p>
 * Why Fabric API alone is insufficient:
 * <p>
 * There is no Fabric API event for "what color should this entity's outline
 * be"; the color is computed by this single vanilla method, so intercepting
 * it directly is the minimal-footprint way to plug in a per-player color
 * without reimplementing outline rendering from scratch.
 */
@Mixin(Entity.class)
public abstract class EntityOutlineColorMixin {

    @Inject(method = "getTeamColorValue", at = @At("RETURN"), cancellable = true)
    private void playertagger$overrideOutlineColor(CallbackInfoReturnable<Integer> cir) {
        int vanillaColor = cir.getReturnValueI();
        int resolved = GlowRenderer.outlineColorOrDefault((Entity) (Object) this, vanillaColor);
        if (resolved != vanillaColor) {
            cir.setReturnValue(resolved);
        }
    }
}
