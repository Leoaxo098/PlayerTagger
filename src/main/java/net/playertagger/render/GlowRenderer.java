package net.playertagger.render;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.playertagger.manager.PlayerTagManager;

import java.util.UUID;

/**
 * Pure, side-effect-free decision logic used by the rendering mixins.
 *
 * <p>Keeping this logic out of the mixins themselves means it can be unit
 * tested and reused if PlayerTagger ever grows a second render path (for
 * example a minimap integration, per the future-expandability goals), and
 * it keeps the mixins themselves as small and easy to audit as possible —
 * which matters because mixins patch engine code and every line in one is a
 * line that can conflict with another mod.</p>
 *
 * <p>Nothing in this class ever touches the entity's actual data tracker,
 * team, or network state. It only ever <em>reads</em> the entity's UUID and
 * asks {@link PlayerTagManager} — a purely local, client-side store —
 * whether that UUID is tagged. The glow is 100% a client-side rendering
 * decision; nothing here can leak information to a server or to other
 * players.</p>
 */
public final class GlowRenderer {

    private GlowRenderer() {
    }

    /**
     * Whether the given entity should be rendered through the vanilla
     * outline (glowing) pass this frame.
     */
    public static boolean shouldRenderOutline(Entity entity) {
        if (!(entity instanceof PlayerEntity player)) {
            return false;
        }
        PlayerTagManager manager = PlayerTagManager.getInstance();
        if (!manager.isEnabled()) {
            return false;
        }
        if (!manager.isShowThroughWalls()) {
            // The vanilla outline pass is inherently an x-ray effect (it
            // ignores the depth buffer), so "don't show through walls" is
            // implemented as "don't use the outline pass at all" rather
            // than a half-measure that would still leak through walls.
            return false;
        }
        if (player.isInvisible() && !manager.isShowInvisiblePlayers()) {
            return false;
        }
        UUID uuid = player.getUuid();
        return manager.isTagged(uuid);
    }

    /**
     * The ARGB outline color to use for the given entity, or -1 if it has
     * no PlayerTagger-assigned color (in which case vanilla's own
     * team-color logic should be used instead).
     */
    public static int outlineColorOrDefault(Entity entity, int vanillaDefault) {
        if (!(entity instanceof PlayerEntity player)) {
            return vanillaDefault;
        }
        PlayerTagManager manager = PlayerTagManager.getInstance();
        if (!manager.isEnabled()) {
            return vanillaDefault;
        }
        UUID uuid = player.getUuid();
        int color = manager.getColor(uuid);
        return color != -1 ? color : vanillaDefault;
    }
}
