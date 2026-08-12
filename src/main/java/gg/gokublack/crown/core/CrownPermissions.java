package gg.gokublack.crown.core;

import gg.gokublack.crown.integration.LuckPermsBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.util.UUID;

/**
 * The single authorization facade (spec 3.3). Whether LuckPerms is installed or not, every
 * Crown permission decision comes through here, so the fallback is one code path rather than a
 * branch sprinkled across the command tree.
 *
 * <p>Crown's own commands never consult LuckPerms for the monarch check — the term record is the
 * source of truth. LuckPerms is used only to reflect that status outward, into prefixes and any
 * permissions the group carries for other mods.
 */
public final class CrownPermissions {

    /** Operator level required for the {@code /crown admin} subtree (spec 7.1). */
    public static final int ADMIN_LEVEL = 3;

    private CrownPermissions() {
    }

    public static boolean luckPermsPresent() {
        return ModList.get().isLoaded("luckperms");
    }

    public static boolean isMonarch(CrownState state, UUID player) {
        return state.isMonarch(player);
    }

    public static boolean isMonarch(CrownState state, CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && state.isMonarch(player.getUUID());
    }

    public static boolean isAdmin(CommandSourceStack source) {
        return source.hasPermission(ADMIN_LEVEL);
    }

    /**
     * Powers are usable only by the sitting monarch, and only while the term is not frozen for
     * the succession election (spec 4.1).
     */
    public static boolean canUsePowers(CrownState state, CommandSourceStack source) {
        return isMonarch(state, source)
                && state.currentTerm() != null
                && !state.currentTerm().powersFrozen();
    }

    public static void onMonarchInstalled(UUID player, long termEndsAt) {
        if (luckPermsPresent()) {
            LuckPermsBridge.grantMonarch(player, CrownConfig.LUCKPERMS_GROUP.get(), termEndsAt);
        }
    }

    public static void onMonarchRevoked(UUID player) {
        if (luckPermsPresent()) {
            LuckPermsBridge.revokeMonarch(player, CrownConfig.LUCKPERMS_GROUP.get());
        }
    }

    public static void onTitleGranted(UUID player, String title) {
        if (luckPermsPresent()) {
            LuckPermsBridge.setTitleSuffix(player, title);
        }
    }
}
