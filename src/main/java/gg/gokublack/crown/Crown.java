package gg.gokublack.crown;

import com.mojang.logging.LogUtils;
import gg.gokublack.crown.announce.DiscordWebhookSink;
import gg.gokublack.crown.core.CrownConfig;
import gg.gokublack.crown.core.CrownEvents;
import gg.gokublack.crown.endgate.EndGate;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Crown: governance and retention systems for the Goku Black v2 SMP.
 *
 * <p>Server-side only by design (spec 2.3): no registered items, blocks, entities or custom
 * packets, and no client classes. Everything player-facing is expressed through vanilla chat
 * components, titles, boss bars and command trees.
 */
@Mod(Crown.MOD_ID)
public final class Crown {
    public static final String MOD_ID = "crown";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Crown(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, CrownConfig.SPEC);
        NeoForge.EVENT_BUS.register(CrownEvents.class);
        NeoForge.EVENT_BUS.register(EndGate.class);
        NeoForge.EVENT_BUS.register(DiscordWebhookSink.class);
        LOGGER.info("Crown initialised (server-side governance systems)");
    }
}
