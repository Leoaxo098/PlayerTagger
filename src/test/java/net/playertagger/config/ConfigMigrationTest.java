package net.playertagger.config;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigMigrationTest {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempDirectory("playertagger-config-test").resolve("playertagger.json");
        Files.writeString(file, """
                {"enabled":false,"players":[{"uuid":"01234567-89ab-cdef-0123-456789abcdef",
                "username":"Steve","color":"aqua"}],
                "settings":{"showThroughWalls":true,"showInvisiblePlayers":true},"extra":"keep"}
                """);
        ConfigManager manager = new ConfigManager(file);
        PlayerTaggerConfig config = manager.load();
        if (config.isEnabled() || config.getPlayers().size() != 1
                || !config.getPlayers().getFirst().getUsername().equals("Steve")
                || !config.getPlayers().getFirst().getColor().equals("aqua")) {
            throw new AssertionError("Old configuration did not retain the saved player and enabled state");
        }
        manager.save(config);
        String saved = Files.readString(file);
        if (saved.contains("showThroughWalls") || saved.contains("showInvisiblePlayers")
                || !saved.contains("keep") || !saved.contains("01234567-89ab-cdef-0123-456789abcdef")) {
            throw new AssertionError("Configuration migration lost data or retained retired settings");
        }
    }
}
