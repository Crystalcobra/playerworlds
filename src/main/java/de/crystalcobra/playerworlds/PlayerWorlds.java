package de.crystalcobra.playerworlds;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(PlayerWorlds.MODID)
public class PlayerWorlds {
    public static final String MODID = "playerworlds";

    public PlayerWorlds(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(new ServerEvents());
    }
}
