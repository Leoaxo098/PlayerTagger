package net.playertagger.storage;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record describing a single tagged player.
 *
 * <p>Identity is always by {@link UUID}. The username is only a "last known"
 * display value that is refreshed opportunistically whenever we see the
 * player online again; it is never used for equality or lookups.</p>
 *
 * @param uuid     the player's unique, stable Mojang UUID
 * @param username the last known username for display purposes
 * @param color    the identifier of the label color assigned to this player,
 *                 as understood by {@link net.playertagger.render.GlowColorManager}
 */
public record TaggedPlayer(UUID uuid, String username, String color) {

    public TaggedPlayer {
        Objects.requireNonNull(uuid, "uuid must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(color, "color must not be null");
    }

    /**
     * Returns a copy of this tagged player with an updated username.
     * Used to keep the "last known username" fresh without losing the color
     * assignment.
     */
    public TaggedPlayer withUsername(String newUsername) {
        return new TaggedPlayer(this.uuid, newUsername, this.color);
    }

    /**
     * Returns a copy of this tagged player with a new label color.
     */
    public TaggedPlayer withColor(String newColor) {
        return new TaggedPlayer(this.uuid, this.username, newColor);
    }
}
