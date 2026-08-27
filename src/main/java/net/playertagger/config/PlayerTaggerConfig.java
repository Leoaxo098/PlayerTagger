package net.playertagger.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Plain data model mirroring {@code config/playertagger.json}.
 *
 * <p>This class intentionally contains no logic beyond simple accessors so
 * that it maps cleanly onto the JSON schema described in the mod
 * specification. All loading, saving, and validation lives in
 * {@link ConfigManager}.</p>
 */
public final class PlayerTaggerConfig {

    private boolean enabled = true;
    private List<PlayerEntry> players = new ArrayList<>();
    private Settings settings = new Settings();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<PlayerEntry> getPlayers() {
        if (players == null) {
            players = new ArrayList<>();
        }
        return players;
    }

    public void setPlayers(List<PlayerEntry> players) {
        this.players = players != null ? players : new ArrayList<>();
    }

    public Settings getSettings() {
        if (settings == null) {
            settings = new Settings();
        }
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = settings != null ? settings : new Settings();
    }

    /**
     * One entry in the {@code players} array of the config file.
     */
    public static final class PlayerEntry {
        @SerializedName("uuid")
        private String uuid;

        @SerializedName("username")
        private String username;

        @SerializedName("color")
        private String color;

        public PlayerEntry() {
            // Required for Gson deserialization.
        }

        public PlayerEntry(UUID uuid, String username, String color) {
            this.uuid = Objects.requireNonNull(uuid).toString();
            this.username = Objects.requireNonNull(username);
            this.color = Objects.requireNonNull(color);
        }

        public String getUuid() {
            return uuid;
        }

        public String getUsername() {
            return username;
        }

        public String getColor() {
            return color;
        }
    }

    /**
     * The {@code settings} object of the config file. Kept intentionally
     * small today, but new toggles can be added here without touching the
     * rest of the mod thanks to {@link ConfigManager}'s tolerant parsing.
     */
    public static final class Settings {
        @SerializedName("showThroughWalls")
        private boolean showThroughWalls = true;

        @SerializedName("showInvisiblePlayers")
        private boolean showInvisiblePlayers = true;

        public boolean isShowThroughWalls() {
            return showThroughWalls;
        }

        public void setShowThroughWalls(boolean showThroughWalls) {
            this.showThroughWalls = showThroughWalls;
        }

        public boolean isShowInvisiblePlayers() {
            return showInvisiblePlayers;
        }

        public void setShowInvisiblePlayers(boolean showInvisiblePlayers) {
            this.showInvisiblePlayers = showInvisiblePlayers;
        }
    }
}
