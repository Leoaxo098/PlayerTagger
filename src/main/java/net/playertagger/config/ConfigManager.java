package net.playertagger.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;

/**
 * Owns the lifecycle of {@code config/playertagger.json}: creating it with
 * sane defaults the first time the mod runs, loading it on startup, and
 * persisting it after every change made through {@link net.playertagger.manager.PlayerTagManager}.
 *
 * <p>The config is treated as a single global file shared across every
 * world and server the player joins, which is what gives PlayerTagger its
 * "tag once, stay tagged everywhere" persistence.</p>
 */
public final class ConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlayerTagger/Config");

    /** Top-level keys we understand; anything else is preserved verbatim. */
    private static final Set<String> KNOWN_TOP_LEVEL_KEYS = Set.of("enabled", "players", "settings");

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path configPath;

    /**
     * Any JSON fields present in the file on disk that this version of the
     * mod does not recognize. Kept around so a config edited by a future
     * (or modified) version of PlayerTagger doesn't silently lose data the
     * next time this version saves it.
     */
    private JsonObject unknownFields = new JsonObject();

    public ConfigManager() {
        this(FabricLoader.getInstance().getConfigDir().resolve("playertagger.json"));
    }

    /** Package-private constructor used by tests to point at a temp file. */
    ConfigManager(Path configPath) {
        this.configPath = configPath;
    }

    /**
     * Loads the config from disk, creating a default one if it does not
     * exist yet, and repairing it in place if it is corrupted.
     */
    public PlayerTaggerConfig load() {
        try {
            if (Files.notExists(configPath)) {
                LOGGER.info("No config found at {}, creating default config.", configPath);
                PlayerTaggerConfig defaultConfig = new PlayerTaggerConfig();
                save(defaultConfig);
                return defaultConfig;
            }

            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                LOGGER.warn("Config file at {} was empty, restoring defaults.", configPath);
                return recoverWithDefaults();
            }

            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                LOGGER.warn("Config file at {} did not contain a JSON object, restoring defaults.", configPath);
                return recoverWithDefaults();
            }

            JsonObject root = parsed.getAsJsonObject();
            captureUnknownFields(root);

            PlayerTaggerConfig config = gson.fromJson(root, PlayerTaggerConfig.class);
            if (config == null) {
                LOGGER.warn("Config file at {} parsed to null, restoring defaults.", configPath);
                return recoverWithDefaults();
            }

            // Guard against partially-corrupt structures (e.g. "players" was
            // present but not an array, "settings" was a string, etc.)
            // Gson will have already thrown for outright type mismatches,
            // which is caught below, so at this point missing collections
            // just need to be normalized to empty ones.
            if (config.getPlayers() == null) {
                config.setPlayers(new java.util.ArrayList<>());
            }


            return config;
        } catch (JsonSyntaxException | IOException e) {
            LOGGER.error("Failed to read/parse {} ({}); backing up and restoring defaults.",
                    configPath, e.toString());
            backupCorruptedFile();
            return recoverWithDefaults();
        }
    }

    /**
     * Serializes the given config to disk immediately. Called after every
     * mutation so nothing is ever lost to a crash.
     */
    public synchronized void save(PlayerTaggerConfig config) {
        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            JsonObject root = gson.toJsonTree(config).getAsJsonObject();

            // Merge back any fields this version of the mod doesn't
            // understand so we don't clobber them.
            for (var entry : unknownFields.entrySet()) {
                if (!KNOWN_TOP_LEVEL_KEYS.contains(entry.getKey())) {
                    root.add(entry.getKey(), entry.getValue());
                }
            }

            String json = gson.toJson(root);

            Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);

            try {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException atomicMoveUnsupported) {
                // Some filesystems (notably certain network drives) don't
                // support atomic moves; fall back to a plain, non-atomic one.
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", configPath, e);
        }
    }

    private void captureUnknownFields(JsonObject root) {
        JsonObject extras = new JsonObject();
        for (var entry : root.entrySet()) {
            if (!KNOWN_TOP_LEVEL_KEYS.contains(entry.getKey())) {
                extras.add(entry.getKey(), entry.getValue());
            }
        }
        this.unknownFields = extras;
    }

    private PlayerTaggerConfig recoverWithDefaults() {
        PlayerTaggerConfig defaultConfig = new PlayerTaggerConfig();
        save(defaultConfig);
        return defaultConfig;
    }

    private void backupCorruptedFile() {
        try {
            Path backup = configPath.resolveSibling(
                    "playertagger.json.corrupted-" + Instant.now().getEpochSecond() + ".bak");
            Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Backed up corrupted config to {}", backup);
        } catch (IOException e) {
            LOGGER.error("Could not back up corrupted config file {}", configPath, e);
        }
    }
}
