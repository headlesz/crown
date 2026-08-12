package gg.gokublack.crown.integration;

import gg.gokublack.crown.Crown;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;

import java.time.Duration;
import java.util.UUID;

/**
 * Every direct reference to the LuckPerms API lives in this class and nowhere else.
 *
 * <p>LuckPerms is a soft dependency (spec 3.3), so its classes may be absent at runtime. Callers
 * must go through {@link gg.gokublack.crown.core.CrownPermissions}, which checks the mod is
 * loaded before touching this class — that check is what stops the JVM from ever trying to
 * resolve these imports on a server without LuckPerms.
 */
public final class LuckPermsBridge {

    private LuckPermsBridge() {
    }

    /**
     * Grants the monarch group as a temporary node expiring an hour after the term ends, so a
     * crash or a missed transition still cannot leave a stale monarch permanently elevated.
     */
    public static void grantMonarch(UUID player, String group, long termEndsAt) {
        try {
            LuckPerms lp = LuckPermsProvider.get();
            long ttl = Math.max(60_000L, (termEndsAt - System.currentTimeMillis()) + Duration.ofHours(1).toMillis());
            lp.getUserManager().modifyUser(player, user -> {
                InheritanceNode node = InheritanceNode.builder(group)
                        .expiry(Duration.ofMillis(ttl))
                        .build();
                user.data().add(node);
            }).exceptionally(t -> {
                Crown.LOGGER.warn("LuckPerms grant of '{}' to {} failed", group, player, t);
                return null;
            });
        } catch (Exception e) {
            Crown.LOGGER.warn("LuckPerms grant failed for {}", player, e);
        }
    }

    public static void revokeMonarch(UUID player, String group) {
        try {
            LuckPerms lp = LuckPermsProvider.get();
            lp.getUserManager().modifyUser(player, user ->
                    user.data().clear(NodeType.INHERITANCE.predicate(
                            n -> n.getGroupName().equalsIgnoreCase(group)))
            ).exceptionally(t -> {
                Crown.LOGGER.warn("LuckPerms revoke of '{}' from {} failed", group, player, t);
                return null;
            });
        } catch (Exception e) {
            Crown.LOGGER.warn("LuckPerms revoke failed for {}", player, e);
        }
    }

    /** Best-effort suffix so a title shows up in chat where LuckPerms owns the display. */
    public static void setTitleSuffix(UUID player, String title) {
        try {
            LuckPerms lp = LuckPermsProvider.get();
            lp.getUserManager().modifyUser(player, (User user) ->
                    user.data().add(net.luckperms.api.node.types.SuffixNode
                            .builder(" " + title, 10)
                            .build())
            ).exceptionally(t -> {
                Crown.LOGGER.warn("LuckPerms suffix set for {} failed", player, t);
                return null;
            });
        } catch (Exception e) {
            Crown.LOGGER.warn("LuckPerms suffix failed for {}", player, e);
        }
    }
}
