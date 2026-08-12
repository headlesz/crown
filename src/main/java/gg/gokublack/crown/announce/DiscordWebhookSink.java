package gg.gokublack.crown.announce;

import gg.gokublack.crown.Crown;
import gg.gokublack.crown.core.CrownConfig;
import gg.gokublack.crown.core.Json;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fire-and-forget Discord sink (spec 9). A webhook, never a bot: Discord Integration already owns
 * the bot and the chat bridge, and Crown only emits.
 *
 * <p>Every call is off-thread with a 5s timeout and no retries beyond the one attempt. Failures
 * are logged at most once per event type per hour so a dead webhook cannot spam the console, and
 * never propagate to the server thread.
 */
public final class DiscordWebhookSink {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long FAILURE_LOG_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);

    private static final Map<AnnounceType, Long> LAST_FAILURE_LOG = new EnumMap<>(AnnounceType.class);

    private static volatile ExecutorService executor;
    private static volatile HttpClient client;

    private DiscordWebhookSink() {
    }

    private static synchronized void ensureStarted() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "crown-discord-webhook");
                t.setDaemon(true);
                return t;
            });
            client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        }
    }

    public static void send(AnnounceEvent event) {
        String url = CrownConfig.WEBHOOK_URL.get();
        if (url == null || url.isBlank()) {
            return; // Sink silently disabled (spec 9).
        }
        ensureStarted();
        ExecutorService ex = executor;
        if (ex == null || ex.isShutdown()) {
            return;
        }
        ex.execute(() -> post(url, event));
    }

    private static void post(String url, AnnounceEvent event) {
        try {
            String payload = buildEmbed(event);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Crown/1.0 (Goku Black v2)")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                logFailure(event.type(), "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            logFailure(event.type(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static synchronized void logFailure(AnnounceType type, String detail) {
        long now = System.currentTimeMillis();
        Long last = LAST_FAILURE_LOG.get(type);
        if (last == null || now - last >= FAILURE_LOG_INTERVAL_MS) {
            LAST_FAILURE_LOG.put(type, now);
            Crown.LOGGER.warn("Discord webhook failed for {} ({}). Further {} failures muted for an hour.",
                    type, detail, type);
        }
    }

    private static String buildEmbed(AnnounceEvent event) {
        return "{\"embeds\":[{"
                + "\"title\":\"" + escape(event.discordTitle()) + "\","
                + "\"description\":\"" + escape(event.discordBody()) + "\","
                + "\"color\":" + event.type().color()
                + "}]}";
    }

    private static String escape(String raw) {
        return Json.escape(raw);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ExecutorService ex = executor;
        if (ex != null) {
            ex.shutdown();
            try {
                if (!ex.awaitTermination(5, TimeUnit.SECONDS)) {
                    ex.shutdownNow();
                }
            } catch (InterruptedException e) {
                ex.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }
}
