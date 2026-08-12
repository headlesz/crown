package gg.gokublack.crown.announce;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * One announcement, fanned out to both sinks (spec 9).
 *
 * @param type          drives colour and salience
 * @param chat          the in-game chat line
 * @param titleText     optional screen title for high-salience events
 * @param discordTitle  embed title
 * @param discordBody   embed description
 */
public record AnnounceEvent(AnnounceType type,
                            Component chat,
                            @Nullable Component titleText,
                            String discordTitle,
                            String discordBody) {

    public static AnnounceEvent of(AnnounceType type, Component chat, String discordTitle, String discordBody) {
        return new AnnounceEvent(type, chat, null, discordTitle, discordBody);
    }

    public AnnounceEvent withTitle(Component title) {
        return new AnnounceEvent(type, chat, title, discordTitle, discordBody);
    }
}
