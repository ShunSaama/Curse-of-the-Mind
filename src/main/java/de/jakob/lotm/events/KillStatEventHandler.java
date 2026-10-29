package de.jakob.lotm.events;

import de.jakob.lotm.LOTMCraft;
import de.jakob.lotm.attachments.KillStatsComponent;
import de.jakob.lotm.attachments.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Tracks every kill committed by a player into {@link KillStatsComponent}.
 * <p>
 * {@code totalKills} counts any living victim, {@code playerKills} only victims that are players.
 * Projectile and other indirect kills are attributed to their owner.
 */
@EventBusSubscriber(modid = LOTMCraft.MOD_ID)
public class KillStatEventHandler {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) {
            return;
        }

        ServerPlayer killer = resolveKiller(event.getSource());
        if (killer == null || killer.getUUID().equals(victim.getUUID())) {
            return;
        }

        KillStatsComponent killStats = killer.getData(ModAttachments.KILL_STATS_COMPONENT);
        killStats.incrementTotalKills();
        if (victim instanceof ServerPlayer) {
            killStats.incrementPlayerKills();
        }
    }

    /**
     * Resolves the player responsible for a death. Beyond direct kills this covers
     * projectiles that hurt their targets as themselves instead of in their owner's name.
     */
    public static ServerPlayer resolveKiller(DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        if (source.getDirectEntity() instanceof ServerPlayer player) {
            return player;
        }
        if (source.getDirectEntity() instanceof Projectile projectile
                && projectile.getOwner() instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }
}
