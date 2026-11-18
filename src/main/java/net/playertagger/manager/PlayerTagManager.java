package net.playertagger.manager;

import net.playertagger.config.ConfigManager;
import net.playertagger.config.PlayerTaggerConfig;
import net.playertagger.render.GlowColorManager;
import net.playertagger.storage.TaggedPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Single source of truth for "which players are tagged" and "is the mod
 * currently enabled".
 *
 * <p>This class is deliberately the <em>only</em> place that touches
 * {@link ConfigManager}. Everything else — commands, the renderer, future
 * GUIs — goes through here, which keeps the persistence logic in one spot
 * and makes it trivial to reason about when a save happens.</p>
 *
 * <p>All label-affecting reads ({@link #isEnabled()}, {@link #isTagged(UUID)},
 * {@link #getColor(UUID)}) are cheap {@link Map} lookups so the renderer can
 * call them every frame without any caching layer; this is what makes label
 * state update instantly on every trigger described in the spec (toggle,
 * add, remove, world load, (re)join, dimension change, respawn, etc.) —
 * there is no stale cache to invalidate in the first place.</p>
 */
public final class PlayerTagManager {

    private static final PlayerTagManager INSTANCE = new PlayerTagManager();

    private final ConfigManager configManager = new ConfigManager();

    /** Keyed by UUID for O(1) lookups from the render/mixin hot path. */
    private final Map<UUID, TaggedPlayer> taggedPlayers = new LinkedHashMap<>();

    private volatile boolean enabled = true;

    private PlayerTagManager() {
        loadFromDisk();
    }

    public static PlayerTagManager getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Enabled state
    // ------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the whole mod. Labels disappear/reappear the very
     * next frame because the renderer checks {@link #isEnabled()} directly;
     * commands keep working regardless of this flag.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        persist();
    }

    public boolean toggle() {
        setEnabled(!enabled);
        return enabled;
    }

    // ------------------------------------------------------------------
    // Tag queries (hot path — keep these allocation-free)
    // ------------------------------------------------------------------

    public boolean isTagged(UUID uuid) {
        return uuid != null && taggedPlayers.containsKey(uuid);
    }

    /** Returns the resolved ARGB color for a tagged player, or -1 if untagged. */
    public int getColor(UUID uuid) {
        TaggedPlayer player = taggedPlayers.get(uuid);
        return player != null ? GlowColorManager.resolve(player.color()) : -1;
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    public enum AddResult {ADDED, ALREADY_TAGGED}

    public enum RemoveResult {REMOVED, NOT_TAGGED}

    /**
     * Tags a player by UUID, storing their current username as the "last
     * known" display name. Duplicate additions are ignored (see
     * {@link AddResult#ALREADY_TAGGED}).
     */
    public AddResult addPlayer(UUID uuid, String username) {
        if (taggedPlayers.containsKey(uuid)) {
            // Refresh the last-known username even if already tagged, since
            // this is a cheap opportunistic update and costs nothing.
            TaggedPlayer existing = taggedPlayers.get(uuid);
            if (!existing.username().equals(username)) {
                taggedPlayers.put(uuid, existing.withUsername(username));
                persist();
            }
            return AddResult.ALREADY_TAGGED;
        }

        String color = GlowColorManager.defaultColorForIndex(taggedPlayers.size());
        taggedPlayers.put(uuid, new TaggedPlayer(uuid, username, color));
        persist();
        return AddResult.ADDED;
    }

    public RemoveResult removePlayer(UUID uuid) {
        TaggedPlayer removed = taggedPlayers.remove(uuid);
        if (removed == null) {
            return RemoveResult.NOT_TAGGED;
        }
        persist();
        return RemoveResult.REMOVED;
    }

    public void setColor(UUID uuid, String colorName) {
        TaggedPlayer existing = taggedPlayers.get(uuid);
        if (existing == null) {
            return;
        }
        taggedPlayers.put(uuid, existing.withColor(colorName));
        persist();
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    public Optional<TaggedPlayer> get(UUID uuid) {
        return Optional.ofNullable(taggedPlayers.get(uuid));
    }

    /** Returns a defensive, stable-ordered snapshot for iteration (e.g. by /playertagger list). */
    public List<TaggedPlayer> listAll() {
        return new ArrayList<>(taggedPlayers.values());
    }

    public int count() {
        return taggedPlayers.size();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void loadFromDisk() {
        PlayerTaggerConfig config = configManager.load();
        this.enabled = config.isEnabled();
        this.taggedPlayers.clear();
        for (PlayerTaggerConfig.PlayerEntry entry : config.getPlayers()) {
            try {
                UUID uuid = UUID.fromString(entry.getUuid());
                String color = GlowColorManager.isValidColor(entry.getColor())
                        ? entry.getColor()
                        : GlowColorManager.defaultColorForIndex(taggedPlayers.size());
                taggedPlayers.put(uuid, new TaggedPlayer(uuid, entry.getUsername(), color));
            } catch (IllegalArgumentException | NullPointerException malformedEntry) {
                // Skip a single corrupted entry rather than discarding the
                // whole list; the config is robust against partial corruption.
            }
        }
    }

    /** Writes the current in-memory state back to {@code config/playertagger.json}. */
    private void persist() {
        PlayerTaggerConfig config = new PlayerTaggerConfig();
        config.setEnabled(enabled);

        List<PlayerTaggerConfig.PlayerEntry> entries = new ArrayList<>();
        for (TaggedPlayer player : taggedPlayers.values()) {
            entries.add(new PlayerTaggerConfig.PlayerEntry(player.uuid(), player.username(), player.color()));
        }
        config.setPlayers(entries);

        configManager.save(config);
    }

    /** Exposed for tests / future GUI code that wants to observe the raw collection. */
    Collection<TaggedPlayer> rawValues() {
        return taggedPlayers.values();
    }
}
