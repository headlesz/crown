package gg.gokublack.crown.endgate;

import gg.gokublack.crown.Crown;
import gg.gokublack.crown.core.CrownConfig;
import gg.gokublack.crown.core.CrownState;
import gg.gokublack.crown.core.CrownTime;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Gates entry to the End (spec 6.1).
 *
 * <p>Crown never touches {@code allow-end} or the level settings: the dimension exists and stays
 * generated, and only player transit is intercepted. That is what lets the finale window be
 * flipped open and shut without a restart.
 *
 * <p>Two surfaces are covered — portal transit and eye-of-ender frame activation — so the gate
 * holds whether a player walks into a live portal or tries to light one.
 */
public final class EndGate {

    private EndGate() {
    }

    private static ResourceKey<Level> gatedDimension() {
        ResourceLocation id = ResourceLocation.tryParse(CrownConfig.GATED_DIMENSION.get());
        if (id == null) {
            return Level.END;
        }
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
    }

    public static boolean isOpen(CrownState state) {
        return state.raid().gateOpen(CrownTime.now());
    }

    /**
     * Blocks transit into the gated dimension while the gate is shut. Non-player entities are
     * deliberately exempt so thrown items and projectiles keep working, and exit is never
     * blocked — players inside walk out through the return portal.
     */
    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!event.getDimension().equals(gatedDimension())) {
            return;
        }
        // Leaving the gated dimension is always allowed; the gate blocks entry, not exit.
        if (player.level().dimension().equals(gatedDimension())) {
            return;
        }
        CrownState state = CrownState.get(player.server);
        if (isOpen(state)) {
            return;
        }
        event.setCanceled(true);
        bounce(player);
    }

    /** Also refuses eye-of-ender insertion, so a sealed portal cannot even be lit. */
    @SubscribeEvent
    public static void onEyeInsert(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!event.getItemStack().is(Items.ENDER_EYE)) {
            return;
        }
        BlockState blockState = player.level().getBlockState(event.getPos());
        if (!(blockState.getBlock() instanceof EndPortalFrameBlock)) {
            return;
        }
        if (blockState.getValue(EndPortalFrameBlock.HAS_EYE)) {
            return;
        }
        CrownState state = CrownState.get(player.server);
        if (isOpen(state)) {
            return;
        }
        event.setCanceled(true);
        bounce(player);
    }

    private static void bounce(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("The End is sealed until the season's finale.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        try {
            player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.6F, 1.4F);
        } catch (Exception e) {
            Crown.LOGGER.debug("Could not play the End-gate bounce sound", e);
        }
    }
}
