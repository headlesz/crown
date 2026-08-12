package gg.gokublack.crown.pack;

import gg.gokublack.crown.Crown;
import gg.gokublack.crown.announce.AnnounceEvent;
import gg.gokublack.crown.announce.AnnounceType;
import gg.gokublack.crown.announce.Announcer;
import gg.gokublack.crown.core.CrownConfig;
import gg.gokublack.crown.core.CrownState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The per-term server resource pack (spec 8).
 *
 * <p>Crown owns exactly one pack slot, identified by {@link #CROWN_PACK_ID}, and drives it with
 * the runtime push/pop packets. It never writes {@code server.properties}, and it never pushes
 * with {@code required=true} unless an operator has deliberately overridden the default — a
 * forced pack locks out players on weak connections, which the design will not accept.
 */
public final class PackManager {

    /** Stable slot id, so a new pack replaces the old one instead of stacking. */
    public static final UUID CROWN_PACK_ID = UUID.fromString("c0000000-c0de-4a11-b0b0-000000000001");

    private static final Pattern SHA1 = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    /** Join pushes waiting out {@code pack_push_join_delay_ticks}. */
    private static final ConcurrentLinkedQueue<PendingPush> PENDING = new ConcurrentLinkedQueue<>();

    private record PendingPush(UUID player, long dueAtTick) {
    }

    private PackManager() {
    }

    // ------------------------------------------------------------------ validation

    /**
     * Validates the URL off-thread (HEAD, 2xx, size cap, SHA-1 shape) and hands the verdict back
     * on the server thread.
     *
     * @param onResult receives {@code null} on success, or an actionable error message
     */
    public static void validateAsync(MinecraftServer server, String url, String sha1, Consumer<String> onResult) {
        if (!SHA1.matcher(sha1).matches()) {
            onResult.accept("that SHA-1 doesn't look right — it must be exactly 40 hex characters.");
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                onResult.accept("the pack URL must be http or https.");
                return;
            }
        } catch (IllegalArgumentException e) {
            onResult.accept("that URL is malformed.");
            return;
        }

        Thread worker = new Thread(() -> {
            String error = null;
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(HTTP_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(HTTP_TIMEOUT)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    error = "the host answered HTTP " + status + " for that URL.";
                } else {
                    long max = CrownConfig.PACK_MAX_BYTES.get();
                    long length = response.headers().firstValueAsLong("content-length").orElse(-1L);
                    if (length > max) {
                        error = "that pack is " + (length / 1_048_576L) + " MB, over the "
                                + (max / 1_048_576L) + " MB limit.";
                    }
                }
            } catch (Exception e) {
                error = "couldn't reach that URL (" + e.getClass().getSimpleName() + ").";
            }
            String finalError = error;
            server.execute(() -> onResult.accept(finalError));
        }, "crown-pack-validate");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------ push / pop

    public static void applyPack(MinecraftServer server, CrownState state, String url, String sha1) {
        state.setPack(url, sha1);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            pop(player);
            push(player, url, sha1);
        }
        Announcer.emit(server, AnnounceEvent.of(
                AnnounceType.PACK_CHANGED,
                Component.literal("A new server resource pack is up for this term. "
                        + "You can decline it — nothing breaks if you do.")
                        .withStyle(AnnounceType.PACK_CHANGED.chatColor()),
                "Resource pack changed",
                "The term's pack was set. Declining is allowed."));
    }

    public static void clearForAll(MinecraftServer server, CrownState state) {
        state.setPack("", "");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            pop(player);
        }
    }

    public static void clearForAllQuietly(MinecraftServer server, CrownState state) {
        clearForAll(server, state);
        Announcer.emit(server, AnnounceEvent.of(
                AnnounceType.PACK_CHANGED,
                Component.literal("The term resource pack was cleared.")
                        .withStyle(AnnounceType.PACK_CHANGED.chatColor()),
                "Resource pack cleared",
                "Back to the default pack."));
    }

    public static void queueJoinPush(MinecraftServer server, ServerPlayer player) {
        long delay = Math.max(0, CrownConfig.PACK_PUSH_JOIN_DELAY_TICKS.get());
        PENDING.add(new PendingPush(player.getUUID(), server.getTickCount() + delay));
    }

    /** Drains join pushes whose delay has elapsed. Called once per tick by the scheduler. */
    public static void processQueue(MinecraftServer server, CrownState state) {
        if (PENDING.isEmpty() || !state.hasPack()) {
            if (!state.hasPack()) {
                PENDING.clear();
            }
            return;
        }
        long tick = server.getTickCount();
        List<PendingPush> ready = new ArrayList<>();
        for (PendingPush pending : PENDING) {
            if (tick >= pending.dueAtTick()) {
                ready.add(pending);
            }
        }
        for (PendingPush pending : ready) {
            PENDING.remove(pending);
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
            if (player != null) {
                push(player, state.packUrl(), state.packSha1());
            }
        }
    }

    private static void push(ServerPlayer player, String url, String sha1) {
        boolean forced = CrownConfig.PACK_FORCED.get();
        if (forced) {
            Crown.LOGGER.warn("crown.pack_forced is enabled; players who decline will be kicked. "
                    + "This is not the recommended setting.");
        }
        try {
            player.connection.send(new ClientboundResourcePackPushPacket(
                    CROWN_PACK_ID,
                    url,
                    sha1.toLowerCase(Locale.ROOT),
                    forced,
                    Optional.of(Component.literal("This term's resource pack. Declining is fine."))));
        } catch (Exception e) {
            Crown.LOGGER.warn("Failed to push the term pack to {}", player.getGameProfile().getName(), e);
        }
    }

    private static void pop(ServerPlayer player) {
        try {
            player.connection.send(new ClientboundResourcePackPopPacket(Optional.of(CROWN_PACK_ID)));
        } catch (Exception e) {
            Crown.LOGGER.warn("Failed to pop the term pack for {}", player.getGameProfile().getName(), e);
        }
    }
}
