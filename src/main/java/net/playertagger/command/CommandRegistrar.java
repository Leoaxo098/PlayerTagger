package net.playertagger.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.playertagger.manager.PlayerTagManager;
import net.playertagger.storage.TaggedPlayer;
import net.playertagger.util.PlayerLookupUtil;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Registers the {@code /playertagger} command tree using Fabric's
 * client-only command API ({@link ClientCommandManager}). Every subcommand
 * runs entirely on the client — none of it ever reaches the server, which
 * is essential to PlayerTagger staying invisible to other players.
 */
public final class CommandRegistrar {

    private static final int PAGE_SIZE = 8;

    private CommandRegistrar() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("playertagger")
                        .then(ClientCommandManager.literal("toggle")
                                .executes(CommandRegistrar::toggle))
                        .then(ClientCommandManager.literal("add")
                                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                        .suggests(CommandRegistrar::suggestOnlinePlayers)
                                        .executes(CommandRegistrar::add)))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                        .suggests(CommandRegistrar::suggestTaggedPlayers)
                                        .executes(CommandRegistrar::remove)))
                        .then(ClientCommandManager.literal("list")
                                .executes(context -> list(context, 1))
                                .then(ClientCommandManager.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> list(context, IntegerArgumentType.getInteger(context, "page")))))
        ));
    }

    // ------------------------------------------------------------------
    // toggle
    // ------------------------------------------------------------------

    private static int toggle(CommandContext<FabricClientCommandSource> context) {
        boolean nowEnabled = PlayerTagManager.getInstance().toggle();
        context.getSource().sendFeedback(nowEnabled
                ? Text.literal("PlayerTagger enabled.").formatted(Formatting.GREEN)
                : Text.literal("PlayerTagger disabled.").formatted(Formatting.RED));
        return 1;
    }

    // ------------------------------------------------------------------
    // add
    // ------------------------------------------------------------------

    private static int add(CommandContext<FabricClientCommandSource> context) {
        String username = StringArgumentType.getString(context, "player");
        UUID uuid = PlayerLookupUtil.resolveUuid(username);

        PlayerTagManager.AddResult result = PlayerTagManager.getInstance().addPlayer(uuid, username);
        if (result == PlayerTagManager.AddResult.ALREADY_TAGGED) {
            context.getSource().sendFeedback(
                    Text.literal(username + " is already tagged.").formatted(Formatting.YELLOW));
        } else {
            context.getSource().sendFeedback(
                    Text.literal("Added " + username + ".").formatted(Formatting.GREEN));
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // remove
    // ------------------------------------------------------------------

    private static int remove(CommandContext<FabricClientCommandSource> context) {
        String username = StringArgumentType.getString(context, "player");
        PlayerTagManager manager = PlayerTagManager.getInstance();

        UUID uuid = resolveTaggedUuidByUsername(manager, username)
                .orElseGet(() -> PlayerLookupUtil.resolveUuid(username));

        PlayerTagManager.RemoveResult result = manager.removePlayer(uuid);
        if (result == PlayerTagManager.RemoveResult.REMOVED) {
            context.getSource().sendFeedback(
                    Text.literal("Removed " + username + ".").formatted(Formatting.GREEN));
        } else {
            context.getSource().sendFeedback(
                    Text.literal("Player not tagged.").formatted(Formatting.RED));
        }
        return 1;
    }

    private static java.util.Optional<UUID> resolveTaggedUuidByUsername(PlayerTagManager manager, String username) {
        return manager.listAll().stream()
                .filter(tagged -> tagged.username().equals(username))
                .map(TaggedPlayer::uuid)
                .findFirst();
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    private static int list(CommandContext<FabricClientCommandSource> context, int requestedPage) {
        List<TaggedPlayer> tagged = PlayerTagManager.getInstance().listAll().stream()
                .sorted(Comparator.comparing(TaggedPlayer::username, String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (tagged.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("No tagged players.").formatted(Formatting.GRAY));
            return 1;
        }

        int totalPages = Math.max(1, (int) Math.ceil(tagged.size() / (double) PAGE_SIZE));
        int page = Math.min(Math.max(requestedPage, 1), totalPages);

        context.getSource().sendFeedback(
                Text.literal("Tagged Players (Page " + page + "/" + totalPages + ")").formatted(Formatting.GOLD));
        context.getSource().sendFeedback(Text.literal(""));

        int fromIndex = (page - 1) * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, tagged.size());
        for (TaggedPlayer player : tagged.subList(fromIndex, toIndex)) {
            boolean online = PlayerLookupUtil.findOnline(player.username()).isPresent();
            Text status = online
                    ? Text.literal("Online").formatted(Formatting.GREEN)
                    : Text.literal("Offline").formatted(Formatting.DARK_GRAY);
            context.getSource().sendFeedback(Text.literal(padRight(player.username(), 10)).append(status));
        }

        return 1;
    }

    private static String padRight(String text, int width) {
        return text.length() >= width ? text + "  " : text + " ".repeat(width - text.length());
    }

    // ------------------------------------------------------------------
    // Suggestions
    // ------------------------------------------------------------------

    private static CompletableFuture<Suggestions> suggestOnlinePlayers(
            CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        for (String name : PlayerLookupUtil.onlineUsernames()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestTaggedPlayers(
            CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        for (TaggedPlayer player : PlayerTagManager.getInstance().listAll()) {
            builder.suggest(player.username());
        }
        return builder.buildFuture();
    }
}
