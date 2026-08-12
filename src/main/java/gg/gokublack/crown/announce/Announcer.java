package gg.gokublack.crown.announce;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The single facade every announcement goes through (spec 9). Two sinks: in-game chat (plus a
 * screen title for high-salience events) and the Discord webhook.
 */
public final class Announcer {

    private Announcer() {
    }

    public static void emit(MinecraftServer server, AnnounceEvent event) {
        server.getPlayerList().broadcastSystemMessage(event.chat(), false);

        Component title = event.titleText();
        if (title == null && event.type().highSalience()) {
            title = event.chat();
        }
        if (title != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
                player.connection.send(new ClientboundSetTitleTextPacket(title));
            }
        }

        DiscordWebhookSink.send(event);
    }

    /** Chat-only variant for lines that should not reach Discord. */
    public static void chat(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }
}
