package de.crystalcobra.playerworlds;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class WorldCommands {
    private static final long CONFIRM_TIMEOUT_MS = 30_000;
    private static final Map<UUID, Long> PENDING_DELETES = new HashMap<>();

    private WorldCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("createworld")
                .executes(ctx -> create(ctx, WorldType.NORMAL, null))
                .then(Commands.literal("normal")
                        .executes(ctx -> create(ctx, WorldType.NORMAL, null))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> create(ctx, WorldType.NORMAL, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("vanilla")
                        .executes(ctx -> create(ctx, WorldType.VANILLA, null))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> create(ctx, WorldType.VANILLA, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("flat")
                        .executes(ctx -> create(ctx, WorldType.FLAT, null))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> create(ctx, WorldType.FLAT, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> create(ctx, WorldType.NORMAL, StringArgumentType.getString(ctx, "name")))));

        dispatcher.register(Commands.literal("renameworld")
                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(WorldCommands::rename)));

        dispatcher.register(Commands.literal("tpworld").executes(WorldCommands::teleport));

        dispatcher.register(Commands.literal("deleteworld")
                .executes(WorldCommands::requestDelete)
                .then(Commands.literal("confirm").executes(WorldCommands::confirmDelete)));

        dispatcher.register(Commands.literal("worldinvite")
                .then(Commands.argument("spieler", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), b))
                        .executes(WorldCommands::invite)));

        dispatcher.register(Commands.literal("worldkick")
                .then(Commands.argument("spieler", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(memberNames(ctx), b))
                        .executes(WorldCommands::kick)));

        dispatcher.register(Commands.literal("worldmembers").executes(WorldCommands::members));
        dispatcher.register(Commands.literal("lockworld").executes(ctx -> setLocked(ctx, true)));
        dispatcher.register(Commands.literal("unlockworld").executes(ctx -> setLocked(ctx, false)));

        dispatcher.register(Commands.literal("visitworld")
                .then(Commands.argument("welt", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(visitSuggestions(ctx), b))
                        .executes(WorldCommands::visit)));
    }

    private static final int MAX_NAME_LENGTH = 32;

    @Nullable
    private static String validateName(CommandContext<CommandSourceStack> ctx, MinecraftServer server, String name, UUID self) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            fail(ctx, "Der Name muss 1-" + MAX_NAME_LENGTH + " Zeichen lang sein.");
            return null;
        }
        PlayerWorldsData.Entry other = PlayerWorldsData.get(server).byName(trimmed);
        if (other != null && !other.owner().equals(self)) {
            fail(ctx, "Eine Welt mit diesem Namen gibt es schon.");
            return null;
        }
        return trimmed;
    }

    /** Display name of a world: its custom name, or "Welt von <Spieler>". */
    public static String displayName(MinecraftServer server, PlayerWorldsData.Entry entry) {
        return entry.name().isEmpty() ? "Welt von " + nameOf(server, entry.owner()) : entry.name();
    }

    /** Argument to pass to /visitworld for this world. */
    private static String visitArg(MinecraftServer server, PlayerWorldsData.Entry entry) {
        return entry.name().isEmpty() ? nameOf(server, entry.owner()) : entry.name();
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        PlayerWorldsData.Entry entry = ownWorld(ctx, player);
        if (entry == null) {
            return 0;
        }
        String name = validateName(ctx, server, StringArgumentType.getString(ctx, "name"), player.getUUID());
        if (name == null) {
            return 0;
        }
        entry.setName(name);
        PlayerWorldsData.get(server).setDirty();
        ctx.getSource().sendSuccess(() -> Component.literal("Deine Welt heißt jetzt \"" + name + "\".").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static Iterable<String> visitSuggestions(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (PlayerWorldsData.Entry e : PlayerWorldsData.get(server).worlds().values()) {
            out.add(visitArg(server, e));
        }
        return out;
    }

    private static int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        PlayerWorldsData.Entry entry = ownWorld(ctx, player);
        if (entry == null) {
            return 0;
        }
        GameProfile target = lookup(server, StringArgumentType.getString(ctx, "spieler"));
        if (target == null) {
            fail(ctx, "Spieler nicht gefunden.");
            return 0;
        }
        if (target.getId().equals(player.getUUID())) {
            fail(ctx, "Das ist deine eigene Welt.");
            return 0;
        }
        if (!entry.members().add(target.getId())) {
            fail(ctx, target.getName() + " hat bereits Zugang zu deiner Welt.");
            return 0;
        }
        PlayerWorldsData.get(server).setDirty();
        ctx.getSource().sendSuccess(() -> Component.literal(target.getName() + " hat jetzt Zugang zu deiner Welt.")
                .withStyle(ChatFormatting.GREEN), false);
        ServerPlayer online = server.getPlayerList().getPlayer(target.getId());
        if (online != null) {
            String world = displayName(server, entry);
            online.sendSystemMessage(Component.literal(player.getName().getString()
                    + " hat dich zu \"" + world + "\" eingeladen. Betreten mit /visitworld " + visitArg(server, entry))
                    .withStyle(ChatFormatting.GREEN));
        }
        return 1;
    }

    private static int kick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        PlayerWorldsData.Entry entry = ownWorld(ctx, player);
        if (entry == null) {
            return 0;
        }
        GameProfile target = lookup(server, StringArgumentType.getString(ctx, "spieler"));
        if (target == null || !entry.members().remove(target.getId())) {
            fail(ctx, "Dieser Spieler hat keinen Zugang zu deiner Welt.");
            return 0;
        }
        PlayerWorldsData.get(server).setDirty();
        WorldManager.evictUnauthorized(server, player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(target.getName() + " hat keinen Zugang mehr zu deiner Welt.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int members(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlayerWorldsData.Entry entry = ownWorld(ctx, player);
        if (entry == null) {
            return 0;
        }
        String names = entry.members().stream()
                .map(id -> nameOf(ctx.getSource().getServer(), id))
                .collect(Collectors.joining(", "));
        String state = entry.locked() ? "gesperrt (nur du und eingeladene Spieler)" : "offen (jeder darf rein)";
        String world = displayName(ctx.getSource().getServer(), entry);
        ctx.getSource().sendSuccess(() -> Component.literal("\"" + world + "\" ist " + state + ". Eingeladen: "
                + (names.isEmpty() ? "niemand" : names)).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int setLocked(CommandContext<CommandSourceStack> ctx, boolean locked) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        PlayerWorldsData.Entry entry = ownWorld(ctx, player);
        if (entry == null) {
            return 0;
        }
        entry.setLocked(locked);
        PlayerWorldsData.get(server).setDirty();
        if (locked) {
            WorldManager.evictUnauthorized(server, player.getUUID());
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Deine Welt ist jetzt gesperrt. Nur du und eingeladene Spieler kommen rein.").withStyle(ChatFormatting.GREEN), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Deine Welt ist jetzt offen. Jeder kann sie mit /visitworld " + visitArg(server, entry) + " betreten.")
                    .withStyle(ChatFormatting.GREEN), false);
        }
        return 1;
    }

    private static int visit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        String arg = StringArgumentType.getString(ctx, "welt").trim();
        PlayerWorldsData.Entry entry = PlayerWorldsData.get(server).byName(arg);
        if (entry == null) {
            GameProfile target = lookup(server, arg);
            entry = target == null ? null : PlayerWorldsData.get(server).get(target.getId());
        }
        ServerLevel level = entry == null ? null : WorldManager.getWorld(server, entry.owner());
        if (entry == null || level == null) {
            fail(ctx, "Keine Welt mit diesem Namen oder Spieler gefunden.");
            return 0;
        }
        if (!entry.canEnter(player.getUUID())) {
            fail(ctx, "Diese Welt ist gesperrt. Du brauchst eine Einladung des Besitzers.");
            return 0;
        }
        WorldManager.teleportToWorld(player, level);
        return 1;
    }

    @Nullable
    private static PlayerWorldsData.Entry ownWorld(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        PlayerWorldsData.Entry entry = PlayerWorldsData.get(ctx.getSource().getServer()).get(player.getUUID());
        if (entry == null) {
            fail(ctx, "Du hast noch keine Welt. Erstelle eine mit /createworld.");
        }
        return entry;
    }

    @Nullable
    private static GameProfile lookup(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getGameProfile();
        }
        Optional<GameProfile> cached = server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(name);
        return cached.orElse(null);
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getName().getString();
        }
        if (server.getProfileCache() != null) {
            Optional<GameProfile> cached = server.getProfileCache().get(id);
            if (cached.isPresent()) {
                return cached.get().getName();
            }
        }
        return id.toString();
    }

    private static Iterable<String> memberNames(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayer player = ctx.getSource().getPlayer();
        PlayerWorldsData.Entry entry = player == null ? null : PlayerWorldsData.get(server).get(player.getUUID());
        if (entry == null) {
            return java.util.List.of();
        }
        return entry.members().stream().map(id -> nameOf(server, id)).toList();
    }

    private static int create(CommandContext<CommandSourceStack> ctx, WorldType type, @Nullable String rawName) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();

        if (WorldManager.hasWorld(server, player.getUUID())) {
            fail(ctx, "Du hast bereits eine Welt. Nutze /tpworld zum Teleportieren oder /deleteworld zum Löschen.");
            return 0;
        }
        String name = "";
        if (rawName != null) {
            name = validateName(ctx, server, rawName, player.getUUID());
            if (name == null) {
                return 0;
            }
        }

        String shown = name.isEmpty() ? "Deine Welt" : "Deine Welt \"" + name + "\"";
        ctx.getSource().sendSuccess(() -> Component.literal(shown + " wurde erstellt!").withStyle(ChatFormatting.GREEN), false);
        ServerLevel level = WorldManager.createWorld(server, player.getUUID(), type, name);
        WorldManager.teleportToWorld(player, level);
        return 1;
    }

    private static int teleport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = WorldManager.getWorld(ctx.getSource().getServer(), player.getUUID());
        if (level == null) {
            fail(ctx, "Du hast noch keine Welt. Erstelle eine mit /createworld.");
            return 0;
        }
        WorldManager.teleportToWorld(player, level);
        return 1;
    }

    private static int requestDelete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!WorldManager.hasWorld(ctx.getSource().getServer(), player.getUUID())) {
            fail(ctx, "Du hast keine Welt, die gelöscht werden könnte.");
            return 0;
        }
        PENDING_DELETES.put(player.getUUID(), System.currentTimeMillis());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Deine Welt wird unwiderruflich gelöscht! Bestätige innerhalb von 30 Sekunden mit /deleteworld confirm")
                .withStyle(ChatFormatting.RED), false);
        return 1;
    }

    private static int confirmDelete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        Long requested = PENDING_DELETES.remove(player.getUUID());
        if (requested == null || System.currentTimeMillis() - requested > CONFIRM_TIMEOUT_MS) {
            fail(ctx, "Keine Löschanfrage offen. Nutze zuerst /deleteworld.");
            return 0;
        }
        if (!WorldManager.hasWorld(server, player.getUUID())) {
            fail(ctx, "Du hast keine Welt, die gelöscht werden könnte.");
            return 0;
        }
        WorldManager.deleteWorld(server, player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("Deine Welt wurde gelöscht.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal(message));
    }
}
