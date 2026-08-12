package gg.gokublack.crown.core;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Name/UUID resolution that keeps working for offline and departed players. */
public final class Players {

    private Players() {
    }

    public static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            Optional<GameProfile> profile = cache.get(id);
            if (profile.isPresent() && profile.get().getName() != null) {
                return profile.get().getName();
            }
        }
        return id.toString().substring(0, 8);
    }

    @Nullable
    public static UUID uuidOf(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            Optional<GameProfile> profile = cache.get(name);
            if (profile.isPresent()) {
                return profile.get().getId();
            }
        }
        return null;
    }

    /**
     * Everyone the server considers a member: the whitelist when one is enforced, otherwise
     * everyone seen this term plus everyone currently online.
     */
    public static Map<UUID, String> roster(MinecraftServer server, CrownState state) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (String name : server.getPlayerList().getWhiteListNames()) {
            UUID id = uuidOf(server, name);
            if (id != null) {
                out.put(id, name);
            }
        }
        for (UUID id : state.activeThisTerm()) {
            out.putIfAbsent(id, nameOf(server, id));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            out.putIfAbsent(player.getUUID(), player.getGameProfile().getName());
        }
        return out;
    }
}
