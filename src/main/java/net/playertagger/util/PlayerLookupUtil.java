package net.playertagger.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Helper for turning a typed username into a stable {@link UUID}, and for
 * answering "who is currently online" questions used by tab completion and
 * {@code /playertagger list}.
 *
 * <p>Resolution order:</p>
 * <ol>
 *     <li>The current server's player list (instant, no network call,
 *     always correct for the server you're actually on).</li>
 *     <li>Mojang's public profile API, for tagging someone who is not
 *     currently online (best-effort, short timeout so a command never
 *     hangs the game for long).</li>
 *     <li>The vanilla offline-mode UUID derivation
 *     ({@code OfflinePlayer:<name>}), used as a last resort so LAN/offline
 *     servers still get a stable, deterministic UUID to key off of.</li>
 * </ol>
 */
public final class PlayerLookupUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlayerTagger/Lookup");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    private PlayerLookupUtil() {
    }

    /**
     * Finds the currently online {@link PlayerListEntry} with an exact,
     * case-sensitive username match, if any.
     */
    public static Optional<PlayerListEntry> findOnline(String username) {
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null) {
            return Optional.empty();
        }
        for (PlayerListEntry entry : handler.getPlayerList()) {
            if (getProfileName(entry).equals(username)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** All usernames currently visible in the tab list, for command suggestions. */
    public static Collection<String> onlineUsernames() {
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null) {
            return List.of();
        }
        return handler.getPlayerList().stream()
                .map(PlayerLookupUtil::getProfileName)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * Resolves a username to a UUID using the strategy documented on the
     * class, never throwing — worst case this deterministically derives an
     * offline-mode UUID so {@code /playertagger add} always succeeds.
     */
    public static UUID resolveUuid(String username) {
        Optional<PlayerListEntry> online = findOnline(username);
        if (online.isPresent()) {
            return getProfileId(online.get());
        }

        Optional<UUID> remote = lookupViaMojangApi(username);
        if (remote.isPresent()) {
            return remote.get();
        }

        return offlineUuid(username);
    }

    /** Returns the profile's name, or an empty string when the profile is missing. */
    public static String getProfileName(PlayerListEntry entry) {
        if (entry == null || entry.getProfile() == null) {
            return "";
        }
        return entry.getProfile().getName();
    }

    /** Returns the profile UUID, or the deterministic fallback for a missing profile. */
    public static UUID getProfileId(PlayerListEntry entry) {
        if (entry == null || entry.getProfile() == null) {
            return offlineUuid("");
        }
        return entry.getProfile().getId();
    }

    private static Optional<UUID> lookupViaMojangApi(String username) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return Optional.empty();
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("id")) {
                return Optional.empty();
            }
            return Optional.of(parseUndashedUuid(json.get("id").getAsString()));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.debug("Mojang API lookup failed for '{}': {}", username, e.toString());
            return Optional.empty();
        }
    }

    private static UUID parseUndashedUuid(String undashed) {
        String dashed = undashed.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }

    /** Mirrors vanilla's deterministic offline-mode UUID derivation. */
    public static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
