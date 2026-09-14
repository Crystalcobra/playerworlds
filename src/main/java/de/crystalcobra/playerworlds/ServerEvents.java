package de.crystalcobra.playerworlds;

import net.neoforged.bus.api.SubscribeEvent;
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
    public void onServerTick(ServerTickEvent.Post event) {
        WorldManager.tick(event.getServer());
    }
}
