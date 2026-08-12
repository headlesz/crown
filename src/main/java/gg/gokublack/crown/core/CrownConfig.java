package gg.gokublack.crown.core;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config (spec 11). Everything is hot-reloadable through {@code /crown admin reload}
 * except {@link #GATED_DIMENSION}, which is read once when the gate arms.
 */
public final class CrownConfig {
    public static final ModConfigSpec SPEC;

    // [term]
    public static final ModConfigSpec.IntValue TERM_LENGTH_DAYS;
    public static final ModConfigSpec.IntValue ELECTION_WINDOW_HOURS;
    public static final ModConfigSpec.IntValue ELECTION_EXTENSION_HOURS;
    public static final ModConfigSpec.IntValue MAX_ELECTION_EXTENSIONS;
    public static final ModConfigSpec.IntValue MONARCH_AFK_DAYS;

    // [election]
    public static final ModConfigSpec.DoubleValue QUORUM_FRACTION;
    public static final ModConfigSpec.BooleanValue ALLOW_SELF_VOTE;
    public static final ModConfigSpec.BooleanValue ALLOW_ALL_WHITELISTED_VOTERS;

    // [powers]
    public static final ModConfigSpec.IntValue MAX_ACTIVE_DECREES;
    public static final ModConfigSpec.IntValue TITLES_PER_TERM;
    public static final ModConfigSpec.IntValue EVENT_COUNTDOWN_MINUTES;

    // [endgate]
    public static final ModConfigSpec.ConfigValue<String> GATED_DIMENSION;
    public static final ModConfigSpec.IntValue MAX_RAIDS_PER_SEASON;
    public static final ModConfigSpec.IntValue RAID_WINDOW_MAX_HOURS;

    // [pack]
    public static final ModConfigSpec.BooleanValue PACK_FORCED;
    public static final ModConfigSpec.LongValue PACK_MAX_BYTES;
    public static final ModConfigSpec.IntValue PACK_PUSH_JOIN_DELAY_TICKS;

    // [integration]
    public static final ModConfigSpec.ConfigValue<String> LUCKPERMS_GROUP;

    // [discord]
    public static final ModConfigSpec.ConfigValue<String> WEBHOOK_URL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("term");
        TERM_LENGTH_DAYS = b
                .comment("Length of a monarch's term, in real days.")
                .defineInRange("term_length_days", 9, 1, 365);
        ELECTION_WINDOW_HOURS = b
                .comment("How long voting stays open. Overlaps the tail of the sitting term.")
                .defineInRange("election_window_hours", 48, 1, 24 * 30);
        ELECTION_EXTENSION_HOURS = b
                .comment("Extra voting time granted when a tally fails (no quorum / unresolved tie).")
                .defineInRange("election_extension_hours", 24, 1, 24 * 14);
        MAX_ELECTION_EXTENSIONS = b
                .comment("Extensions allowed before the server falls into INTERREGNUM.")
                .defineInRange("max_election_extensions", 2, 0, 10);
        MONARCH_AFK_DAYS = b
                .comment("Days of monarch absence before a warning is announced. Never auto-removes.")
                .defineInRange("monarch_afk_days", 5, 1, 365);
        b.pop();

        b.push("election");
        QUORUM_FRACTION = b
                .comment("Fraction of the electorate that must cast a ballot for a tally to stand.")
                .defineInRange("quorum_fraction", 0.5D, 0.0D, 1.0D);
        ALLOW_SELF_VOTE = b
                .comment("A vote is a judgement of others' contributions; leave this false.")
                .define("allow_self_vote", false);
        ALLOW_ALL_WHITELISTED_VOTERS = b
                .comment("Let every whitelisted player vote, not just those who logged in this term.")
                .define("allow_all_whitelisted_voters", false);
        b.pop();

        b.push("powers");
        MAX_ACTIVE_DECREES = b.defineInRange("max_active_decrees", 3, 0, 32);
        TITLES_PER_TERM = b
                .comment("Scarcity is what keeps titles meaningful.")
                .defineInRange("titles_per_term", 3, 0, 32);
        EVENT_COUNTDOWN_MINUTES = b
                .comment("How long before a scheduled event the countdown boss bar appears.")
                .defineInRange("event_countdown_minutes", 60, 1, 24 * 60);
        b.pop();

        b.push("endgate");
        GATED_DIMENSION = b
                .comment("Dimension Crown gates entry to. Restart-only; changing this live logs a warning.")
                .define("gated_dimension", "minecraft:the_end");
        MAX_RAIDS_PER_SEASON = b.defineInRange("max_raids_per_season", 1, 0, 16);
        RAID_WINDOW_MAX_HOURS = b
                .comment("Upper clamp on a monarch-proposed raid window.")
                .defineInRange("raid_window_max_hours", 12, 1, 24 * 7);
        b.pop();

        b.push("pack");
        PACK_FORCED = b
                .comment("Leave this false. Forcing a pack locks out players on weak connections.")
                .define("pack_forced", false);
        PACK_MAX_BYTES = b.defineInRange("pack_max_bytes", 104857600L, 1L, Long.MAX_VALUE);
        PACK_PUSH_JOIN_DELAY_TICKS = b
                .comment("Some clients race the join screen; delay the pack push by this many ticks.")
                .defineInRange("pack_push_join_delay_ticks", 40, 0, 20 * 60);
        b.pop();

        b.push("integration");
        LUCKPERMS_GROUP = b
                .comment("LuckPerms group granted to the sitting monarch, if LuckPerms is installed.")
                .define("luckperms_group", "monarch");
        b.pop();

        b.push("discord");
        WEBHOOK_URL = b
                .comment("Discord webhook URL. Empty disables the Discord sink silently.")
                .define("webhook_url", "");
        b.pop();

        SPEC = b.build();
    }

    private CrownConfig() {
    }
}
