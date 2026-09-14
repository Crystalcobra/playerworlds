package de.crystalcobra.playerworlds;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
                .executes(ctx -> create(ctx, WorldType.NORMAL))
                .then(Commands.literal("normal").executes(ctx -> create(ctx, WorldType.NORMAL)))
                .then(Commands.literal("flat").executes(ctx -> create(ctx, WorldType.FLAT))));

        dispatcher.register(Commands.literal("myworld").executes(WorldCommands::teleport));

        dispatcher.register(Commands.literal("deleteworld")
                .executes(WorldCommands::requestDelete)
                .then(Commands.literal("confirm").executes(WorldCommands::confirmDelete)));
    }

    private static int create(CommandContext<CommandSourceStack> ctx, WorldType type) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();

        if (WorldManager.hasWorld(server, player.getUUID())) {
            fail(ctx, "Du hast bereits eine Welt. Nutze /myworld zum Teleportieren oder /deleteworld zum Löschen.");
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("Deine Welt wurde erstellt!").withStyle(ChatFormatting.GREEN), false);
        ServerLevel level = WorldManager.createWorld(server, player.getUUID(), type);
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
