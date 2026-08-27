package net.playertagger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.playertagger.command.CommandRegistrar;
import net.playertagger.manager.PlayerTagManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the PlayerTagger client mod.
 *
 * <p>PlayerTagger has no server-side component at all — it is declared as a
 * {@code "client"}-only entrypoint in {@code fabric.mod.json} — so this
 * class is the single place startup happens.</p>
 *
 * <p>Note on the various "glow must update immediately" triggers listed in
 * the spec (mod enabled/disabled, player added/removed, world load, server
 * join/disconnect, player join/leave, dimension change, respawn): none of
 * them require dedicated handling here. {@link net.playertagger.render.GlowRenderer}
 * and the rendering mixins consult {@link PlayerTagManager} fresh on every
 * single frame for every single entity, so there is no cache to invalidate —
 * the very next frame after any of those events always reflects the current
 * state automatically. The listener below exists purely for diagnostic
 * logging.</p>
 */
public final class PlayerTaggerClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlayerTagger");

    @Override
    public void onInitializeClient() {
        // Touching the singleton here (rather than lazily on first command)
        // guarantees the config is loaded from disk before the player can
        // interact with the game at all, and before the render mixins can
        // possibly be asked about any entity.
        PlayerTagManager manager = PlayerTagManager.getInstance();

        CommandRegistrar.register();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                LOGGER.info("PlayerTagger active on join: {} player(s) tagged, mod {}.",
                        manager.count(), manager.isEnabled() ? "enabled" : "disabled"));

        LOGGER.info("PlayerTagger initialized with {} tagged player(s).", manager.count());
    }
}
