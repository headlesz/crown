package gg.gokublack.crown.powers;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import gg.gokublack.crown.announce.AnnounceEvent;
import gg.gokublack.crown.announce.AnnounceType;
import gg.gokublack.crown.announce.Announcer;
import gg.gokublack.crown.core.CrownConfig;
import gg.gokublack.crown.core.CrownExporter;
import gg.gokublack.crown.core.CrownPermissions;
import gg.gokublack.crown.core.CrownState;
import gg.gokublack.crown.core.CrownTime;
import gg.gokublack.crown.core.Players;
import gg.gokublack.crown.election.Election;
import gg.gokublack.crown.election.ElectionManager;
import gg.gokublack.crown.endgate.RaidManager;
import gg.gokublack.crown.endgate.RaidState;
import gg.gokublack.crown.pack.PackManager;
import gg.gokublack.crown.prestige.LedgerEntry;
import gg.gokublack.crown.prestige.PrestigeCommands;
import gg.gokublack.crown.term.Term;
import gg.gokublack.crown.term.TermManager;
import gg.gokublack.crown.term.TermPhase;
import gg.gokublack.crown.term.TransitionCause;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The monarch and admin command surface (spec 7).
 *
 * <p>Note what is absent and must stay absent: there is no {@code /crown gamerule}, no kick, no
 * ban, no world-border or difficulty command, and nothing that touches another player's blocks.
 * The only world-state change in this whole tree is the End gate, and even that takes an
 * operator's confirmation. A pull request adding hard power here is a design violation, not a
 * feature.
 */
public final class CrownCommands {

    /** {@code /crown resign} must be typed twice inside this window. */
    private static final long RESIGN_CONFIRM_WINDOW_MS = 30_000L;
    private static final Map<UUID, Long> PENDING_RESIGN = new HashMap<>();

    private CrownCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("crown")
                .executes(CrownCommands::showStatus)

                .then(Commands.literal("name")
                        .then(Commands.argument("term-name", StringArgumentType.greedyString())
                                .executes(ctx -> nameTerm(ctx, StringArgumentType.getString(ctx, "term-name")))))

                .then(Commands.literal("genre")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> setGenre(ctx, StringArgumentType.getString(ctx, "text")))))

                .then(Commands.literal("decree")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> decree(ctx, StringArgumentType.getString(ctx, "text")))))

                .then(Commands.literal("event")
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("when", StringArgumentType.string())
                                                .executes(ctx -> createEvent(ctx, ""))
                                                .then(Commands.argument("description", StringArgumentType.greedyString())
                                                        .executes(ctx -> createEvent(ctx,
                                                                StringArgumentType.getString(ctx, "description")))))))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(CrownCommands::cancelEvent))))

                .then(Commands.literal("pack")
                        .then(Commands.literal("set")
                                .then(Commands.argument("url", StringArgumentType.string())
                                        .then(Commands.argument("sha1", StringArgumentType.string())
                                                .executes(CrownCommands::setPack))))
                        .then(Commands.literal("clear")
                                .executes(CrownCommands::clearPack)))

                .then(Commands.literal("title")
                        .then(Commands.literal("grant")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .then(Commands.argument("title-text", StringArgumentType.greedyString())
                                                .executes(CrownCommands::grantTitle)))))

                .then(Commands.literal("commission")
                        .then(Commands.literal("complete")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(CrownCommands::completeCommission)))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(CrownCommands::issueCommission))))

                .then(Commands.literal("endraid")
                        .then(Commands.literal("request")
                                .then(Commands.argument("start-time", StringArgumentType.string())
                                        .then(Commands.argument("duration-hours", IntegerArgumentType.integer(1, 24 * 7))
                                                .executes(CrownCommands::requestRaid)))))

                .then(Commands.literal("resign")
                        .executes(CrownCommands::resign))

                .then(adminTree()));

        PrestigeCommands.register(dispatcher);
        VoteCommands.register(dispatcher);
    }

    // ------------------------------------------------------------------ admin

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminTree() {
        return Commands.literal("admin")
                .requires(CrownPermissions::isAdmin)

                .then(Commands.literal("status").executes(CrownCommands::adminStatus))

                .then(Commands.literal("remove-monarch")
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(CrownCommands::removeMonarch)))

                .then(Commands.literal("election")
                        .then(Commands.literal("open").executes(ctx -> adminElectionOpen(ctx)))
                        .then(Commands.literal("close").executes(ctx -> adminElectionClose(ctx)))
                        .then(Commands.literal("extend").executes(ctx -> adminElectionExtend(ctx))))

                .then(Commands.literal("endraid")
                        .then(Commands.literal("confirm").executes(ctx -> adminRaid(ctx, "confirm", "")))
                        .then(Commands.literal("close-now").executes(ctx -> adminRaid(ctx, "close-now", "")))
                        .then(Commands.literal("deny")
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> adminRaid(ctx, "deny",
                                                StringArgumentType.getString(ctx, "reason"))))))

                .then(Commands.literal("pack")
                        .then(Commands.literal("force-clear").executes(CrownCommands::forceClearPack)))

                .then(Commands.literal("ledger")
                        .then(Commands.literal("adjust")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(CrownCommands::ledgerAdjust))))

                .then(Commands.literal("reload").executes(CrownCommands::reload));
    }

    // ------------------------------------------------------------------ monarch powers

    private static int nameTerm(CommandContext<CommandSourceStack> ctx, String name) {
        return withPowers(ctx, (state, term, player) -> {
            term.setName(name);
            state.setDirty();
            Announcer.emit(ctx.getSource().getServer(), AnnounceEvent.of(
                    AnnounceType.DECREE,
                    Component.literal("This term shall be known as \"" + name + "\".")
                            .withStyle(ChatFormatting.GOLD),
                    "Term named",
                    "Term " + term.index() + " is now \"" + name + "\"."));
            return 1;
        });
    }

    private static int setGenre(CommandContext<CommandSourceStack> ctx, String genre) {
        return withPowers(ctx, (state, term, player) -> {
            term.setGenre(genre);
            state.setDirty();
            Announcer.emit(ctx.getSource().getServer(), AnnounceEvent.of(
                    AnnounceType.DECREE,
                    Component.literal("The genre of this term: " + genre)
                            .withStyle(ChatFormatting.LIGHT_PURPLE),
                    "Genre set",
                    genre));
            return 1;
        });
    }

    private static int decree(CommandContext<CommandSourceStack> ctx, String text) {
        return withPowers(ctx, (state, term, player) -> {
            int max = CrownConfig.MAX_ACTIVE_DECREES.get();
            if (term.decrees().size() >= max) {
                fail(ctx, "You already have " + max + " decrees standing. A crown that says "
                        + "everything says nothing.");
                return 0;
            }
            term.decrees().add(text);
            state.setDirty();
            Announcer.emit(ctx.getSource().getServer(), AnnounceEvent.of(
                    AnnounceType.DECREE,
                    Component.literal("DECREE: ").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD)
                            .append(Component.literal(text).withStyle(ChatFormatting.WHITE)),
                    "Decree",
                    text + "\n\n— " + term.monarchName() + ", \"" + term.name() + "\""));
            return 1;
        });
    }

    private static int createEvent(CommandContext<CommandSourceStack> ctx, String description) {
        return withPowers(ctx, (state, term, player) -> {
            String name = StringArgumentType.getString(ctx, "name");
            String when = StringArgumentType.getString(ctx, "when");
            long startsAt = CrownTime.parseIso(when);
            if (startsAt < 0) {
                fail(ctx, "Couldn't read \"" + when + "\" as a date. Try 2026-08-20T19:00 "
                        + "(server time) or 2026-08-20T19:00:00Z.");
                return 0;
            }
            if (startsAt <= CrownTime.now()) {
                fail(ctx, "That time has already passed.");
                return 0;
            }
            for (ScheduledEvent existing : term.events()) {
                if (existing.name().equalsIgnoreCase(name)) {
                    fail(ctx, "An event called \"" + name + "\" is already scheduled this term.");
                    return 0;
                }
            }
            term.events().add(new ScheduledEvent(name, description, startsAt));
            state.setDirty();
            Announcer.emit(ctx.getSource().getServer(), AnnounceEvent.of(
                    AnnounceType.EVENT_SCHEDULED,
                    Component.literal("Event scheduled: \"" + name + "\" — "
                            + CrownTime.format(startsAt)).withStyle(ChatFormatting.GREEN),
                    "Event scheduled: " + name,
                    CrownTime.format(startsAt) + (description.isEmpty() ? "" : "\n" + description)));
            return 1;
        });
    }

    private static int cancelEvent(CommandContext<CommandSourceStack> ctx) {
        return withPowers(ctx, (state, term, player) -> {
            String name = StringArgumentType.getString(ctx, "name");
            boolean removed = term.events().removeIf(e -> e.name().equalsIgnoreCase(name));
            if (!removed) {
                fail(ctx, "No event called \"" + name + "\" this term.");
                return 0;
            }
            state.setDirty();
            Announcer.emit(ctx.getSource().getServer(), AnnounceEvent.of(
                    AnnounceType.EVENT_CANCELLED,
                    Component.literal("Event cancelled: \"" + name + "\".").withStyle(ChatFormatting.GRAY),
                    "Event cancelled: " + name,
                    "Called off by " + term.monarchName() + "."));
            return 1;
        });
    }

    private static int setPack(CommandContext<CommandSourceStack> ctx) {
        return withPowers(ctx, (state, term, player) -> {
            String url = StringArgumentType.getString(ctx, "url");
            String sha1 = StringArgumentType.getString(ctx, "sha1");
            MinecraftServer server = ctx.getSource().getServer();
            ctx.getSource().sendSuccess(() -> Component.literal("Checking that pack…")
                    .withStyle(ChatFormatting.GRAY), false);
            PackManager.validateAsync(server, url, sha1, error -> {
                if (error != null) {
                    ctx.getSource().sendFailure(Component.literal("Pack rejected: " + error));
                    return;
                }
                PackManager.applyPack(server, state, url, sha1);
                ctx.getSource().sendSuccess(() -> Component.literal("Pack set for this term.")
                        .withStyle(ChatFormatting.GREEN), false);
            });
            return 1;
        });
    }

    private static int clearPack(CommandContext<CommandSourceStack> ctx) {
        return withPowers(ctx, (state, term, player) -> {
            PackManager.clearForAllQuietly(ctx.getSource().getServer(), state);
            return 1;
        });
    }

    private static int grantTitle(CommandContext<CommandSourceStack> ctx) {
        return withPowers(ctx, (state, term, player) -> {
            GameProfile target = singleProfile(ctx);
            if (target == null) {
                return 0;
            }
            int max = CrownConfig.TITLES_PER_TERM.get();
            if (term.titlesGranted() >= max) {
                fail(ctx, "You've granted your " + max + " titles this term. Scarcity is the point.");
                return 0;
            }
            String title = StringArgumentType.getString(ctx, "title-text");
            term.incrementTitlesGranted();
            state.appendLedger(new LedgerEntry.TitleGranted(
                    target.getId(), target.getName(), title,
                    term.monarch(), term.monarchName(), term.index(), CrownTime.now()));
            CrownPermissions.onTitleGranted(target.getId(), title);

            Announcer.emit(ctx.getSource().getServer(), AnnounceEvent.of(
                    AnnounceType.TITLE_GRANTED,
                    Component.literal(target.getName() + " is named ").withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal("\"" + title + "\"").withStyle(ChatFormatting.GOLD)),
                    "Title granted",
                    target.getName() + " — \"" + title + "\"\nGranted by " + term.monarchName()));
            return 1;
        });
    }

    private static int issueCommission(CommandContext<CommandSourceStack> ctx) {
        return withPowers(ctx, (state, term, player) -> {
            GameProfile target = singleProfile(ctx);
            if (target == null) {
                return 0;
            }
            String text = StringArgumentType.getString(ctx, "text");
            state.openCommissions().put(target.getId(), text);
            state.appendLedger(new LedgerEntry.CommissionIssued(
                    target.getId(), target.getName(), text, term.index(), CrownTime.now()));

            Announcer.emit(ctx.getSource().getServer(), AnnounceEvent.of(
                    AnnounceType.COMMISSION_ISSUED,
                    Component.literal("COMMISSION: ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                            .append(Component.literal(target.getName() + " — " + text)
                                    .withStyle(ChatFormatting.WHITE)),
                    "Commission issued",
                    target.getName() + "\n" + text));
            return 1;
        });
    }

    private static int completeCommission(CommandContext<CommandSourceStack> ctx) {
        return withPowers(ctx, (state, term, player) -> {
            GameProfile target = singleProfile(ctx);
            if (target == null) {
                return 0;
            }
            String text = state.openCommissions().remove(target.getId());
            if (text == null) {
                fail(ctx, target.getName() + " has no open commission this term.");
                return 0;
            }
            state.appendLedger(new LedgerEntry.CommissionCompleted(
                    target.getId(), target.getName(), text, term.index(), CrownTime.now()));
            Announcer.emit(ctx.getSource().getServer(), AnnounceEvent.of(
                    AnnounceType.COMMISSION_COMPLETED,
                    Component.literal("Commission complete — " + target.getName() + " built: " + text)
                            .withStyle(ChatFormatting.GOLD),
                    "Commission completed",
                    target.getName() + " delivered: " + text));
            return 1;
        });
    }

    private static int requestRaid(CommandContext<CommandSourceStack> ctx) {
        return withPowers(ctx, (state, term, player) -> {
            String when = StringArgumentType.getString(ctx, "start-time");
            int hours = IntegerArgumentType.getInteger(ctx, "duration-hours");
            long startsAt = CrownTime.parseIso(when);
            if (startsAt < 0) {
                fail(ctx, "Couldn't read \"" + when + "\" as a date. Try 2026-08-20T19:00.");
                return 0;
            }
            String error = RaidManager.request(ctx.getSource().getServer(), state, player, startsAt, hours);
            if (error != null) {
                fail(ctx, "Can't schedule that: " + error);
                return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.literal(
                            "Proposed. An operator must confirm before the gate will open.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        });
    }

    private static int resign(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(ctx, "Only the monarch can resign, and only in-game.");
            return 0;
        }
        CrownState state = CrownState.get(source.getServer());
        if (!state.isMonarch(player.getUUID())) {
            fail(ctx, "You don't hold the crown.");
            return 0;
        }
        long now = CrownTime.now();
        Long pending = PENDING_RESIGN.get(player.getUUID());
        if (pending == null || now - pending > RESIGN_CONFIRM_WINDOW_MS) {
            PENDING_RESIGN.put(player.getUUID(), now);
            source.sendSuccess(() -> Component.literal(
                            "Type /crown resign again within 30 seconds to abdicate.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        PENDING_RESIGN.remove(player.getUUID());
        TermManager.vacateThrone(source.getServer(), state, TransitionCause.RESIGNATION,
                "they stepped down.");
        return 1;
    }

    // ------------------------------------------------------------------ admin handlers

    private static int removeMonarch(CommandContext<CommandSourceStack> ctx) {
        CrownState state = CrownState.get(ctx.getSource().getServer());
        if (state.currentTerm() == null) {
            fail(ctx, "There is no sitting monarch.");
            return 0;
        }
        String reason = StringArgumentType.getString(ctx, "reason");
        TermManager.vacateThrone(ctx.getSource().getServer(), state,
                TransitionCause.ADMIN_REMOVAL, reason);
        return 1;
    }

    private static int adminElectionOpen(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        CrownState state = CrownState.get(server);
        if (state.election() != null) {
            fail(ctx, "An election is already open.");
            return 0;
        }
        TermManager.openElection(server, state,
                CrownTime.now() + CrownTime.hours(CrownConfig.ELECTION_WINDOW_HOURS.get()));
        return 1;
    }

    private static int adminElectionClose(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        CrownState state = CrownState.get(server);
        if (state.election() == null) {
            fail(ctx, "No election is open.");
            return 0;
        }
        TermManager.closeElection(server, state);
        return 1;
    }

    private static int adminElectionExtend(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        CrownState state = CrownState.get(server);
        if (state.election() == null) {
            fail(ctx, "No election is open.");
            return 0;
        }
        TermManager.extendElection(server, state, "an operator extended the window.");
        return 1;
    }

    private static int adminRaid(CommandContext<CommandSourceStack> ctx, String action, String reason) {
        MinecraftServer server = ctx.getSource().getServer();
        CrownState state = CrownState.get(server);
        String error = switch (action) {
            case "confirm" -> RaidManager.confirm(server, state);
            case "deny" -> RaidManager.deny(server, state, reason);
            case "close-now" -> {
                if (state.raid().status() != RaidState.Status.OPEN) {
                    yield "the gate isn't open.";
                }
                RaidManager.closeWindow(server, state);
                yield null;
            }
            default -> "unknown action.";
        };
        if (error != null) {
            fail(ctx, "Can't do that: " + error);
            return 0;
        }
        return 1;
    }

    private static int forceClearPack(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        PackManager.clearForAllQuietly(server, CrownState.get(server));
        ctx.getSource().sendSuccess(() -> Component.literal("Pack force-cleared.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int ledgerAdjust(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        CrownState state = CrownState.get(server);
        String text = StringArgumentType.getString(ctx, "text");
        ServerPlayer player = ctx.getSource().getPlayer();
        UUID author = player == null ? new UUID(0L, 0L) : player.getUUID();
        String authorName = player == null ? "console" : player.getGameProfile().getName();
        // Corrections are appended. History is never rewritten (spec 7.1).
        state.appendLedger(new LedgerEntry.AdminNote(text, author, authorName,
                state.termIndex(), CrownTime.now()));
        CrownExporter.export(server, state);
        ctx.getSource().sendSuccess(() -> Component.literal("Noted in the ledger.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                        "Crown config reloaded. Note: gated_dimension is restart-only.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int adminStatus(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        CrownState state = CrownState.get(server);
        CommandSourceStack source = ctx.getSource();

        line(source, "phase", state.phase().name());
        line(source, "term index", String.valueOf(state.termIndex()));
        Term term = state.currentTerm();
        if (term == null) {
            line(source, "monarch", "(none)");
        } else {
            line(source, "monarch", term.monarchName() + " (" + term.monarch() + ")");
            line(source, "term name", term.name());
            line(source, "genre", term.genre().isEmpty() ? "(none)" : term.genre());
            line(source, "ends", CrownTime.format(term.endsAt()) + " (" + CrownTime.remaining(term.endsAt()) + ")");
            line(source, "powers", term.powersFrozen() ? "FROZEN (election open)" : "active");
            line(source, "decrees", String.valueOf(term.decrees().size()));
            line(source, "events", String.valueOf(term.events().size()));
            line(source, "titles granted", term.titlesGranted() + "/" + CrownConfig.TITLES_PER_TERM.get());
        }
        Election election = state.election();
        if (election != null) {
            line(source, "election for term", String.valueOf(election.forTermIndex()));
            line(source, "ballots", election.ballots().size() + "/"
                    + ElectionManager.quorumRequired(election) + " needed");
            line(source, "electorate", String.valueOf(election.electorate().size()));
            line(source, "closes", CrownTime.format(election.closesAt()));
            line(source, "extensions", election.extensions() + "/"
                    + CrownConfig.MAX_ELECTION_EXTENSIONS.get());
            line(source, "runoff", String.valueOf(election.runoff()));
        }
        RaidState raid = state.raid();
        line(source, "raid status", raid.status().name());
        if (raid.status() != RaidState.Status.NONE) {
            line(source, "raid window", CrownTime.format(raid.startsAt()) + " → "
                    + CrownTime.format(raid.endsAt()));
        }
        line(source, "raids used", state.raidsUsedThisCampaign() + "/"
                + CrownConfig.MAX_RAIDS_PER_SEASON.get());
        line(source, "pack", state.hasPack() ? state.packUrl() : "(default)");
        line(source, "ledger entries", String.valueOf(state.ledger().size()));
        line(source, "luckperms", CrownPermissions.luckPermsPresent() ? "present" : "absent (fallback)");
        if (TermManager.awaitingCampaignStart(state)) {
            source.sendSuccess(() -> Component.literal(
                            "No campaign started. Run /crown admin election open to call the first vote.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ shared

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        CrownState state = CrownState.get(ctx.getSource().getServer());
        CommandSourceStack source = ctx.getSource();
        Term term = state.currentTerm();

        if (term == null) {
            source.sendSuccess(() -> Component.literal("The throne stands empty.")
                    .withStyle(ChatFormatting.GRAY), false);
            if (TermManager.awaitingCampaignStart(state)) {
                source.sendSuccess(() -> Component.literal(
                                "No campaign has begun yet — an operator calls the first vote.")
                        .withStyle(ChatFormatting.DARK_GRAY), false);
            }
        } else {
            source.sendSuccess(() -> Component.literal("\"" + term.name() + "\" — "
                            + term.monarchName() + " reigns, "
                            + CrownTime.remaining(term.endsAt()) + " remaining.")
                    .withStyle(ChatFormatting.GOLD), false);
            for (String decree : term.decrees()) {
                source.sendSuccess(() -> Component.literal("  decree: " + decree)
                        .withStyle(ChatFormatting.LIGHT_PURPLE), false);
            }
        }
        if (state.phase() == TermPhase.ELECTION && state.election() != null) {
            source.sendSuccess(() -> Component.literal("A vote is open — /vote to cast a ballot.")
                    .withStyle(ChatFormatting.AQUA), false);
        }
        return 1;
    }

    /** Runs {@code action} only if the caller is the sitting monarch with unfrozen powers. */
    private static int withPowers(CommandContext<CommandSourceStack> ctx, PowerAction action) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        CrownState state = CrownState.get(source.getServer());

        if (player == null || !state.isMonarch(player.getUUID())) {
            fail(ctx, "That's a monarch's power, and you don't hold the crown.");
            return 0;
        }
        Term term = state.currentTerm();
        if (term == null) {
            fail(ctx, "There is no term in progress.");
            return 0;
        }
        if (term.powersFrozen()) {
            fail(ctx, "Your powers are frozen while the succession vote is open. "
                    + "What you've already declared still stands.");
            return 0;
        }
        try {
            return action.run(state, term, player);
        } catch (Exception e) {
            gg.gokublack.crown.Crown.LOGGER.error("Crown command failed", e);
            fail(ctx, "That didn't work — check the server log.");
            return 0;
        }
    }

    @FunctionalInterface
    private interface PowerAction {
        int run(CrownState state, Term term, ServerPlayer player) throws Exception;
    }

    /** Resolves the {@code player} argument to exactly one profile. */
    private static GameProfile singleProfile(CommandContext<CommandSourceStack> ctx) {
        try {
            Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
            if (profiles.size() != 1) {
                fail(ctx, "Name exactly one player.");
                return null;
            }
            return profiles.iterator().next();
        } catch (Exception e) {
            fail(ctx, "I don't know that player.");
            return null;
        }
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    private static void line(CommandSourceStack source, String key, String value) {
        source.sendSuccess(() -> Component.literal(key + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)), false);
    }

    /** Exposed for the vote command's candidate listing. */
    public static Map<UUID, String> candidates(MinecraftServer server, CrownState state) {
        return ElectionManager.candidates(server, state);
    }

    static String nameOf(MinecraftServer server, UUID id) {
        return Players.nameOf(server, id);
    }
}
