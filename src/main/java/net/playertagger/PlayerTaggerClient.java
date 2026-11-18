package net.playertagger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.playertagger.command.CommandRegistrar;
import net.playertagger.manager.PlayerTagManager;
import net.playertagger.render.VisibleOutline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Initializes the client-only PlayerTagger mod. */
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
        WorldRenderEvents.BEFORE_ENTITIES.register(context -> VisibleOutline.begin());
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> VisibleOutline.draw());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                LOGGER.info("PlayerTagger active on join: {} player(s) tagged, mod {}.",
                        manager.count(), manager.isEnabled() ? "enabled" : "disabled"));

        LOGGER.info("PlayerTagger initialized with {} tagged player(s).", manager.count());
    }
}
