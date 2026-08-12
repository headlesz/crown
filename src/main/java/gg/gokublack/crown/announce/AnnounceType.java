package gg.gokublack.crown.announce;

import net.minecraft.ChatFormatting;

/** The exhaustive v1 event list (spec 9). */
public enum AnnounceType {
    TERM_STARTED(0xF1C40F, ChatFormatting.GOLD, true),
    TERM_ENDED(0x95A5A6, ChatFormatting.GRAY, true),
    ELECTION_OPENED(0x3498DB, ChatFormatting.AQUA, true),
    ELECTION_EXTENDED(0x5DADE2, ChatFormatting.AQUA, false),
    ELECTION_CLOSED(0x2980B9, ChatFormatting.AQUA, true),
    INTERREGNUM(0xE74C3C, ChatFormatting.RED, true),
    DECREE(0x9B59B6, ChatFormatting.LIGHT_PURPLE, false),
    EVENT_SCHEDULED(0x1ABC9C, ChatFormatting.GREEN, false),
    EVENT_STARTING(0x16A085, ChatFormatting.GREEN, true),
    EVENT_CANCELLED(0x7F8C8D, ChatFormatting.GRAY, false),
    TITLE_GRANTED(0xF39C12, ChatFormatting.YELLOW, false),
    COMMISSION_ISSUED(0xD35400, ChatFormatting.GOLD, false),
    COMMISSION_COMPLETED(0xE67E22, ChatFormatting.GOLD, false),
    RAID_REQUESTED(0x8E44AD, ChatFormatting.LIGHT_PURPLE, false),
    RAID_CONFIRMED(0x8E44AD, ChatFormatting.LIGHT_PURPLE, true),
    RAID_OPENED(0x000000, ChatFormatting.DARK_PURPLE, true),
    RAID_CLOSED(0x34495E, ChatFormatting.DARK_GRAY, true),
    PACK_CHANGED(0x2ECC71, ChatFormatting.GREEN, false);

    private final int color;
    private final ChatFormatting chatColor;
    private final boolean highSalience;

    AnnounceType(int color, ChatFormatting chatColor, boolean highSalience) {
        this.color = color;
        this.chatColor = chatColor;
        this.highSalience = highSalience;
    }

    public int color() {
        return color;
    }

    public ChatFormatting chatColor() {
        return chatColor;
    }

    /** High-salience events also get a screen title, not just a chat line. */
    public boolean highSalience() {
        return highSalience;
    }
}
