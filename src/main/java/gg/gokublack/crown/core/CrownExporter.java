package gg.gokublack.crown.core;

import gg.gokublack.crown.Crown;
import gg.gokublack.crown.election.Ballot;
import gg.gokublack.crown.election.Election;
import gg.gokublack.crown.powers.ScheduledEvent;
import gg.gokublack.crown.prestige.LedgerEntry;
import gg.gokublack.crown.term.Term;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Regenerates {@code world/crown/export.json} on every transition, for debugging and for the
 * group's own records (spec 3.1). Write-only: Crown never reads this file back, so hand-editing
 * it changes nothing.
 *
 * <p>Voter identities are hashed, never written in the clear — the export is meant to survive
 * being screen-shared without leaking who voted for whom (spec 5.2).
 */
public final class CrownExporter {

    private CrownExporter() {
    }

    public static void export(MinecraftServer server, CrownState state) {
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("crown");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("export.json"), build(server, state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Crown.LOGGER.warn("Failed to write the Crown JSON export", e);
        }
    }

    private static String build(MinecraftServer server, CrownState state) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"dataVersion\": ").append(CrownState.CURRENT_DATA_VERSION).append(",\n");
        sb.append("  \"generatedAt\": \"").append(CrownTime.format(CrownTime.now())).append("\",\n");
        sb.append("  \"phase\": \"").append(state.phase().name()).append("\",\n");
        sb.append("  \"termIndex\": ").append(state.termIndex()).append(",\n");
        sb.append("  \"raidsUsedThisCampaign\": ").append(state.raidsUsedThisCampaign()).append(",\n");

        sb.append("  \"currentTerm\": ");
        Term current = state.currentTerm();
        if (current == null) {
            sb.append("null");
        } else {
            appendTerm(sb, current, "  ");
        }
        sb.append(",\n");

        sb.append("  \"pastTerms\": [\n");
        List<Term> past = state.pastTerms();
        for (int i = 0; i < past.size(); i++) {
            sb.append("    ");
            appendTerm(sb, past.get(i), "    ");
            sb.append(i < past.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");

        sb.append("  \"election\": ");
        Election election = state.election();
        if (election == null) {
            sb.append("null");
        } else {
            sb.append("{\n");
            sb.append("    \"forTermIndex\": ").append(election.forTermIndex()).append(",\n");
            sb.append("    \"openedAt\": \"").append(CrownTime.format(election.openedAt())).append("\",\n");
            sb.append("    \"closesAt\": \"").append(CrownTime.format(election.closesAt())).append("\",\n");
            sb.append("    \"extensions\": ").append(election.extensions()).append(",\n");
            sb.append("    \"runoff\": ").append(election.runoff()).append(",\n");
            sb.append("    \"electorateSize\": ").append(election.electorate().size()).append(",\n");
            sb.append("    \"ballotsCast\": ").append(election.ballots().size()).append(",\n");
            // Voter identity is hashed; the candidate is not recorded per-ballot at all, so the
            // export can never be used to reconstruct an individual's vote.
            sb.append("    \"voterHashes\": [");
            List<String> hashes = new ArrayList<>();
            for (Ballot b : election.ballots().values()) {
                hashes.add("\"" + hash(b.voter()) + "\"");
            }
            sb.append(String.join(", ", hashes)).append("]\n");
            sb.append("  }");
        }
        sb.append(",\n");

        sb.append("  \"ledger\": [\n");
        List<LedgerEntry> ledger = state.ledger();
        for (int i = 0; i < ledger.size(); i++) {
            LedgerEntry e = ledger.get(i);
            sb.append("    {\"type\": \"").append(e.type()).append("\"")
                    .append(", \"termIndex\": ").append(e.termIndex())
                    .append(", \"at\": \"").append(CrownTime.format(e.at())).append("\"")
                    .append(", \"summary\": \"").append(esc(summarise(e))).append("\"}");
            sb.append(i < ledger.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendTerm(StringBuilder sb, Term term, String indent) {
        sb.append("{\n");
        sb.append(indent).append("  \"index\": ").append(term.index()).append(",\n");
        sb.append(indent).append("  \"name\": \"").append(esc(term.name())).append("\",\n");
        sb.append(indent).append("  \"monarch\": \"").append(esc(term.monarchName())).append("\",\n");
        sb.append(indent).append("  \"monarchId\": \"").append(term.monarch()).append("\",\n");
        sb.append(indent).append("  \"genre\": \"").append(esc(term.genre())).append("\",\n");
        sb.append(indent).append("  \"startedAt\": \"").append(CrownTime.format(term.startedAt())).append("\",\n");
        sb.append(indent).append("  \"endsAt\": \"").append(CrownTime.format(term.endsAt())).append("\",\n");
        sb.append(indent).append("  \"decrees\": [");
        List<String> decrees = new ArrayList<>();
        for (String d : term.decrees()) {
            decrees.add("\"" + esc(d) + "\"");
        }
        sb.append(String.join(", ", decrees)).append("],\n");
        sb.append(indent).append("  \"events\": [");
        List<String> events = new ArrayList<>();
        for (ScheduledEvent e : term.events()) {
            events.add("\"" + esc(e.name()) + " @ " + CrownTime.format(e.startsAt()) + "\"");
        }
        sb.append(String.join(", ", events)).append("]\n");
        sb.append(indent).append("}");
    }

    private static String summarise(LedgerEntry entry) {
        return switch (entry) {
            case LedgerEntry.ReignServed r -> r.monarchName() + " reigned in term " + r.termIndex();
            case LedgerEntry.TitleGranted t -> t.recipientName() + " named \"" + t.title()
                    + "\" by " + t.grantedByName();
            case LedgerEntry.CommissionIssued c -> c.builderName() + " commissioned: " + c.text();
            case LedgerEntry.CommissionCompleted c -> c.builderName() + " completed: " + c.text();
            case LedgerEntry.AdminNote a -> "note by " + a.authorName() + ": " + a.text();
        };
    }

    private static String hash(UUID id) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(id.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static String esc(String raw) {
        return Json.escape(raw);
    }
}
