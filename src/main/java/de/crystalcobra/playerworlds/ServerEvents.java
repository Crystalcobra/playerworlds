package de.crystalcobra.playerworlds;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public class ServerEvents {
    private final Map<UUID, ResourceKey<Level>> deathDimension = new HashMap<>();

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && WorldManager.ownerOf(player.level().dimension()) != null) {
            deathDimension.put(player.getUUID(), player.level().dimension());
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isEndConquered()) {
            return;
        }
        ResourceKey<Level> died = deathDimension.remove(player.getUUID());
        if (died == null || player.level().dimension().equals(died)) {
            return;
        }
        ServerLevel level = player.getServer().getLevel(died);
        if (level != null && WorldManager.canEnter(player.getServer(), died, player.getUUID())) {
            WorldManager.teleportToWorld(player, level);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        WorldCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        WorldManager.restoreAll(event.getServer());
    }

    @SubscribeEvent
    public void onTravel(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!WorldManager.canEnter(player.getServer(), event.getDimension(), player.getUUID())) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal("Diese Welt ist gesperrt.").withStyle(ChatFormatting.RED));
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        WorldManager.tick(event.getServer());
    }
}
