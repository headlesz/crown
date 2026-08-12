package gg.gokublack.crown.powers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import gg.gokublack.crown.announce.Announcer;
import gg.gokublack.crown.core.CrownState;
import gg.gokublack.crown.core.CrownTime;
import gg.gokublack.crown.election.Election;
import gg.gokublack.crown.election.ElectionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/**
 * The voting surface (spec 5.2).
 *
 * <p>Ballots are secret. Announcements publish counts and nothing else — never who voted for
 * whom — and the only thing ever published about an individual is that they won.
 */
public final class VoteCommands {

    private static final SuggestionProvider<CommandSourceStack> CANDIDATES = (ctx, builder) -> {
        CrownState state = CrownState.get(ctx.getSource().getServer());
        return SharedSuggestionProvider.suggest(
                ElectionManager.candidates(ctx.getSource().getServer(), state).values(), builder);
    };

    private VoteCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vote")
                .executes(VoteCommands::showBallot)
                .then(Commands.literal("status").executes(VoteCommands::status))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(CANDIDATES)
                        .executes(VoteCommands::cast)));
    }

    /** The no-args ballot: every candidate rendered as a clickable button. */
    private static int showBallot(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        CrownState state = CrownState.get(server);
        Election election = state.election();

        if (election == null) {
            source.sendFailure(Component.literal("No vote is open right now."));
            return 0;
        }

        Map<UUID, String> candidates = ElectionManager.candidates(server, state);
        if (candidates.isEmpty()) {
            source.sendFailure(Component.literal("There are no eligible candidates."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Who did the most cool shit this term?")
                .withStyle(ChatFormatting.GOLD), false);

        MutableComponent row = Component.empty();
        boolean first = true;
        for (String name : candidates.values()) {
            if (!first) {
                row.append(Component.literal("  "));
            }
            first = false;
            row.append(Component.literal("[" + name + "]").withStyle(Style.EMPTY
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/vote " + name))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("Vote for the player who did the most cool shit this term.")))));
        }
        source.sendSuccess(() -> row, false);
        source.sendSuccess(() -> Component.literal("Closes in " + CrownTime.remaining(election.closesAt())
                + ". You can change your vote until then.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int cast(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer voter = source.getPlayer();
        if (voter == null) {
            source.sendFailure(Component.literal("Only players vote."));
            return 0;
        }
        CrownState state = CrownState.get(server);
        String name = StringArgumentType.getString(ctx, "player");

        UUID candidate = null;
        for (Map.Entry<UUID, String> entry : ElectionManager.candidates(server, state).entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                candidate = entry.getKey();
                break;
            }
        }
        if (candidate == null) {
            source.sendFailure(Component.literal("\"" + name + "\" isn't standing in this election."));
            return 0;
        }

        ElectionManager.CastResult result = ElectionManager.cast(server, state, voter.getUUID(), candidate);
        switch (result) {
            case OK -> {
                String candidateName = ElectionManager.candidates(server, state).get(candidate);
                // Private confirmation: the public only ever learns the count.
                source.sendSuccess(() -> Component.literal("Your ballot is cast for " + candidateName
                        + ". Nobody else will be told who you chose.").withStyle(ChatFormatting.GREEN), false);
                announceCount(server, state);
            }
            case NO_ELECTION -> source.sendFailure(Component.literal("No vote is open right now."));
            case NOT_ELIGIBLE_VOTER -> source.sendFailure(Component.literal(
                    "You weren't on the server during this term, so you're not on the roll."));
            case NOT_ELIGIBLE_CANDIDATE -> source.sendFailure(Component.literal(
                    "They can't stand in this election — the sitting monarch can't succeed themselves."));
            case SELF_VOTE_DISALLOWED -> source.sendFailure(Component.literal(
                    "The vote is a judgement of other people's work. Pick someone else."));
        }
        return result == ElectionManager.CastResult.OK ? 1 : 0;
    }

    private static void announceCount(MinecraftServer server, CrownState state) {
        Election election = state.election();
        if (election == null) {
            return;
        }
        Announcer.chat(server, Component.literal("[Crown] " + election.ballots().size() + "/"
                        + election.electorate().size() + " ballots cast.")
                .withStyle(ChatFormatting.AQUA));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CrownState state = CrownState.get(source.getServer());
        Election election = state.election();
        if (election == null) {
            source.sendFailure(Component.literal("No vote is open right now."));
            return 0;
        }
        int cast = election.ballots().size();
        int quorum = ElectionManager.quorumRequired(election);
        source.sendSuccess(() -> Component.literal(
                        cast + " of " + election.electorate().size() + " ballots cast — "
                                + (cast >= quorum ? "quorum met" : "quorum needs " + quorum))
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("Closes in "
                + CrownTime.remaining(election.closesAt())
                + (election.runoff() ? " (runoff)" : "")).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
