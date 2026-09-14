package de.crystalcobra.playerworlds;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public class ServerEvents {
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
