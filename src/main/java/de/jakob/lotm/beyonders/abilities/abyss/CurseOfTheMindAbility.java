package de.jakob.lotm.beyonders.abilities.abyss;

import de.jakob.lotm.LOTMCraft;
import de.jakob.lotm.attachments.KillStatsComponent;
import de.jakob.lotm.attachments.ModAttachments;
import de.jakob.lotm.beyonders.abilities.core.Ability;
import de.jakob.lotm.beyonders.abilities.core.AbilityUsedEvent;
import de.jakob.lotm.effect.ModEffects;
import de.jakob.lotm.events.KillStatEventHandler;
import de.jakob.lotm.network.PacketHandler;
import de.jakob.lotm.network.packets.toClient.OpenPlayerDivinationScreenPacket;
import de.jakob.lotm.particle.ModParticles;
import de.jakob.lotm.util.BeyonderData;
import de.jakob.lotm.util.data.PlayerInfo;
import de.jakob.lotm.util.data.PlayerSelectionWorkType;
import de.jakob.lotm.util.helper.AbilityUtil;
import de.jakob.lotm.util.helper.ParticleUtil;
import de.jakob.lotm.util.scheduling.ServerScheduler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sequence 0 Abyss ability: Curse of the Mind.
 * <p>
 * Opens the shared player selection GUI listing online sinners
 * (players with at least {@link #MIN_PLAYER_KILLS} player kills). The cursed victim:
 * <ul>
 *   <li>loses sanity every 10 seconds for 2.5 minutes, scaling with its own sequence:
 *       5% of max sanity at seq 0, 10% at seq 1, 15% at seq 2 and so on — and each kill it
 *       commits while cursed (mobs, players, anything alive) raises that drain by another 1%,</li>
 *   <li>rolls for loss of control with a chance of 1% per kill it ever committed,</li>
 *   <li>loses a final 20% sanity the instant the curse expires.</li>
 * </ul>
 * The curse breaks when the victim dies.
 */
@EventBusSubscriber(modid = LOTMCraft.MOD_ID)
public class CurseOfTheMindAbility extends Ability {

    // --- balance constants ---
    private static final int COOLDOWN_SECONDS = 10 * 60;
    private static final float SPIRITUALITY_COST = 5000;
    private static final int DURATION_TICKS = 20 * 150;       // 2.5 minutes
    private static final int DRAIN_INTERVAL_TICKS = 20 * 10;  // every 10 seconds
    private static final float BASE_DRAIN_PER_INTERVAL = 0.05f; // 5% of max sanity
    private static final float DRAIN_GROWTH_PER_KILL = 0.01f;   // +1% drain per kill while cursed
    private static final float FINAL_SANITY_LOSS = 0.2f;        // 20% of max sanity
    private static final int MIN_PLAYER_KILLS = 10;
    private static final float CONTROL_LOSS_PER_KILL = 0.01f;
    private static final float CONTROL_LOSS_CHANCE_CAP = 0.75f; // cap so veteran killers aren't perma-stunned

    // Abyss blood red, brighter than Malice Seed's dark dust so the two curses read differently
    private static final DustParticleOptions CURSE_DUST =
            new DustParticleOptions(new Vector3f(0.64f, 0.03f, 0.05f), 1.8f);

    private static final int ABYSS_COLOR = 0xFFa3070c;

    // Server curse state: cursed target UUID -> caster UUID / scheduler task id
    private static final Map<UUID, UUID> targetToCaster = new HashMap<>();
    private static final Map<UUID, UUID> targetToTask = new HashMap<>();
    // Kills the victim commits while cursed; each one deepens the sanity drain
    private static final Map<UUID, Integer> killsWhileCursed = new HashMap<>();

    // Targets whose curse expired while offline; the final strike lands on rejoin
    private static final Set<UUID> pendingFinalStrikes = new HashSet<>();

    public CurseOfTheMindAbility(String id) {
        super(id, COOLDOWN_SECONDS, "curse", "corruption");

        canBeUsedByNPC = false;
        canBeCopied = false;
        canBeReplicated = false;
        canBeShared = false;
        hasOptimalDistance = false;
        postsUsedAbilityEventManually = true; // the event fires when the curse actually lands
    }

    @Override
    public Map<String, Integer> getRequirements() {
        return new HashMap<>(Map.of("abyss", 0));
    }

    @Override
    protected float getSpiritualityCost() {
        return SPIRITUALITY_COST;
    }

    @Override
    public void onAbilityUse(Level level, LivingEntity entity) {
        if (!(level instanceof ServerLevel serverLevel) || !(entity instanceof ServerPlayer caster)) {
            return;
        }

        List<PlayerInfo> sinners = getEligibleSinners(serverLevel.getServer(), caster.getUUID());
        if (sinners.isEmpty()) {
            refundFailedCast(caster);
            AbilityUtil.sendActionBar(caster, Component.translatable("ability.lotmcraft.curse_of_the_mind.no_targets").withColor(ABYSS_COLOR));
            return;
        }

        // Open the shared player selection GUI; the choice returns via PlayerDivinationSelectedPacket
        PacketHandler.sendToPlayer(caster, new OpenPlayerDivinationScreenPacket(sinners, PlayerSelectionWorkType.CURSE_OF_THE_MIND));
    }

    /**
     * Entry point after the player picked a sinner in the selection GUI.
     * Cost and cooldown were already charged by the framework, so invalid picks refund both.
     */
    public static void performCurseOnSelected(ServerPlayer caster, UUID targetUUID) {
        ServerLevel level = caster.serverLevel();
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetUUID);

        if (target == null || isBelowKillThreshold(target)) {
            refundFailedCast(caster);
            AbilityUtil.sendActionBar(caster, Component.translatable("ability.lotmcraft.curse_of_the_mind.no_targets").withColor(ABYSS_COLOR));
            return;
        }

        if (targetToCaster.containsKey(targetUUID)) {
            refundFailedCast(caster);
            AbilityUtil.sendActionBar(caster, Component.translatable("ability.lotmcraft.curse_of_the_mind.already_cursed").withColor(ABYSS_COLOR));
            return;
        }

        if (LOTMCraft.abilityHandler.getById(getStaticId()) instanceof CurseOfTheMindAbility ability) {
            ability.applyCurse(level, caster, target);
        }
    }

    private void applyCurse(ServerLevel level, ServerPlayer caster, ServerPlayer target) {
        targetToCaster.put(target.getUUID(), caster.getUUID());
        killsWhileCursed.remove(target.getUUID());

        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.5f);
        ParticleUtil.spawnParticles(level, CURSE_DUST, target.getEyePosition(), 40, 0.75, 0);
        ParticleUtil.spawnParticles(level, ModParticles.BLACK_NOTE.get(), target.getEyePosition(), 12, 0.5, 0.05);

        // The victim is told briefly and mysteriously — never who, never why
        target.sendSystemMessage(Component.translatable("ability.lotmcraft.curse_of_the_mind.cursed")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        AbilityUtil.sendActionBar(caster, Component.translatable("ability.lotmcraft.curse_of_the_mind.applied", target.getName()).withColor(ABYSS_COLOR));

        UUID taskId = ServerScheduler.scheduleForDuration(
                20, DRAIN_INTERVAL_TICKS, DURATION_TICKS,
                () -> drainTick(level, target.getUUID()),
                () -> finishCurse(level, target.getUUID()),
                level, () -> 1.0);
        targetToTask.put(target.getUUID(), taskId);

        NeoForge.EVENT_BUS.post(new AbilityUsedEvent(level, target.position(), caster, target, this,
                interactionFlags, interactionRadius, interactionCacheTicks));
    }

    private void drainTick(ServerLevel level, UUID targetUUID) {
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetUUID);
        if (target == null) {
            return; // curse keeps running while the target is offline
        }

        // The curse strips more the lower the victim stands on the sequence ladder:
        // 5% of max sanity at seq 0, 10% at seq 1, 15% at seq 2 and so on
        int victimSequence = BeyonderData.getSequence(target);
        float drain = BASE_DRAIN_PER_INTERVAL * (1 + victimSequence)
                + killsWhileCursed.getOrDefault(targetUUID, 0) * DRAIN_GROWTH_PER_KILL;

        // Bypasses per-sequence resistance: a Sequence 0 curse cannot be shrugged off
        target.getData(ModAttachments.SANITY_COMPONENT)
                .increaseSanityAndSyncIgnoreSequence(-drain, target);

        // Loss-of-control chance: +1% per kill ever committed by the target, capped
        KillStatsComponent killStats = target.getData(ModAttachments.KILL_STATS_COMPONENT);
        float controlLossChance = Math.min(killStats.getTotalKills() * CONTROL_LOSS_PER_KILL, CONTROL_LOSS_CHANCE_CAP);
        if (random.nextFloat() < controlLossChance) {
            int amplifier = Math.min(3, killStats.getTotalKills() / 30);
            target.addEffect(new MobEffectInstance(ModEffects.LOOSING_CONTROL, 20 * 6, amplifier, false, true));
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 0.7f, 0.4f);
        }

        ParticleUtil.spawnParticles(level, CURSE_DUST, target.getEyePosition(), 8, 0.6, 0.02);
        ParticleUtil.spawnParticles(level, ModParticles.BLACK_NOTE.get(), target.getEyePosition(), 3, 0.3, 0.03);
    }

    private void finishCurse(ServerLevel level, UUID targetUUID) {
        targetToCaster.remove(targetUUID);
        targetToTask.remove(targetUUID);
        killsWhileCursed.remove(targetUUID);

        ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetUUID);
        if (target == null) {
            pendingFinalStrikes.add(targetUUID); // deliver the final strike on rejoin
            return;
        }

        applyFinalStrike(level, target);
    }

    private static void applyFinalStrike(ServerLevel level, ServerPlayer target) {
        target.getData(ModAttachments.SANITY_COMPONENT)
                .increaseSanityAndSyncIgnoreSequence(-FINAL_SANITY_LOSS, target);

        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 2.0f, 0.4f);
        ParticleUtil.spawnParticles(level, CURSE_DUST, target.getEyePosition(), 60, 2.0, 0);
        ParticleUtil.spawnParticles(level, ModParticles.BLACK_NOTE.get(), target.getEyePosition(), 30, 1.0, 0.1);
        target.sendSystemMessage(Component.translatable("ability.lotmcraft.curse_of_the_mind.final_strike")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
    }

    // The curse dies with its victim; kills committed by a cursed player deepen the drain
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }

        // Any kill by a cursed player counts — mobs, players, anything alive,
        // including kills dealt through projectiles and explosions
        ServerPlayer killer = KillStatEventHandler.resolveKiller(event.getSource());
        if (killer != null && targetToCaster.containsKey(killer.getUUID())) {
            killsWhileCursed.merge(killer.getUUID(), 1, Integer::sum);
        }

        if (event.getEntity() instanceof ServerPlayer victim && targetToCaster.containsKey(victim.getUUID())) {
            breakCurse(victim.serverLevel(), victim);
        }
    }

    private static void breakCurse(ServerLevel level, ServerPlayer victim) {
        UUID victimUUID = victim.getUUID();

        UUID taskId = targetToTask.remove(victimUUID);
        if (taskId != null) {
            ServerScheduler.cancel(taskId); // also cancels the linked final-strike task
        }
        targetToCaster.remove(victimUUID);
        killsWhileCursed.remove(victimUUID);
        pendingFinalStrikes.remove(victimUUID);

        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 1.0f, 0.3f);
        ParticleUtil.spawnParticles(level, CURSE_DUST, victim.getEyePosition(), 20, 0.8, 0.03);
        victim.sendSystemMessage(Component.translatable("ability.lotmcraft.curse_of_the_mind.broken_by_death")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
    }

    private static boolean isBelowKillThreshold(ServerPlayer player) {
        return player.getData(ModAttachments.KILL_STATS_COMPONENT).getPlayerKills() < MIN_PLAYER_KILLS;
    }

    private static List<PlayerInfo> getEligibleSinners(MinecraftServer server, UUID excludeUUID) {
        List<PlayerInfo> sinners = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(excludeUUID)) {
                continue;
            }
            if (player.getData(ModAttachments.KILL_STATS_COMPONENT).getPlayerKills() >= MIN_PLAYER_KILLS) {
                sinners.add(new PlayerInfo(player.getUUID(), player.getName().getString()));
            }
        }
        return sinners;
    }

    /**
     * The framework charges cost and cooldown before {@code onAbilityUse} is reached,
     * so a cast that never curses anyone hands both back.
     */
    private static void refundFailedCast(ServerPlayer caster) {
        // Creative players never paid in the first place — nothing to hand back
        if (!caster.hasInfiniteMaterials()) {
            BeyonderData.incrementSpirituality(caster, SPIRITUALITY_COST);
        }
        caster.getData(ModAttachments.COOLDOWN_COMPONENT).setCooldown(getStaticId(), 0);
    }

    private static String getStaticId() {
        return "curse_of_the_mind_ability";
    }

    // Deferred final strikes for targets whose curse expired while offline
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (pendingFinalStrikes.remove(player.getUUID())) {
            applyFinalStrike(player.serverLevel(), player);
        }
    }
}
