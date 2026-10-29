package de.jakob.lotm.beyonders.abilities.abyss;

import de.jakob.lotm.LOTMCraft;
import de.jakob.lotm.attachments.MarionetteComponent;
import de.jakob.lotm.attachments.ModAttachments;
import de.jakob.lotm.beyonders.abilities.core.Ability;
import de.jakob.lotm.beyonders.abilities.core.AbilityUsedEvent;
import de.jakob.lotm.beyonders.acting.ActingCapHelper;
import de.jakob.lotm.data.ModDataComponents;
import de.jakob.lotm.entity.custom.AvatarEntity;
import de.jakob.lotm.item.ModItems;
import de.jakob.lotm.particle.ModParticles;
import de.jakob.lotm.util.BeyonderData;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
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
 * Requires the target's blood in the offhand; the blood of the target's marionettes,
 * avatars or virtual personas works as well. The curse seizes the blood owner and every
 * vessel belonging to them at once. Each cursed vessel:
 * <ul>
 *   <li>loses 5% of its max sanity every 10 seconds for 60 seconds,</li>
 *   <li>loses a final 20% of max sanity the instant the curse expires,</li>
 *   <li>player vessels also have their max sanity reduced by 20% for 10 minutes.</li>
 * </ul>
 * The curse on a vessel breaks when that vessel dies.
 */
@EventBusSubscriber(modid = LOTMCraft.MOD_ID)
public class CurseOfTheMindAbility extends Ability {

    // --- balance constants ---
    private static final int COOLDOWN_SECONDS = 10 * 60;
    private static final float SPIRITUALITY_COST = 5000;
    private static final int DURATION_TICKS = 20 * 60;        // 60 seconds
    private static final int DRAIN_INTERVAL_TICKS = 20 * 10;  // every 10 seconds
    private static final float DRAIN_PER_INTERVAL = 0.05f;    // 5% of max sanity
    private static final float FINAL_SANITY_LOSS = 0.2f;      // 20% of max sanity
    private static final long CAP_REDUCTION_DURATION_MS = 10L * 60 * 1000; // 10 minutes

    // Abyss blood red, brighter than Malice Seed's dark dust so the two curses read differently
    private static final DustParticleOptions CURSE_DUST =
            new DustParticleOptions(new Vector3f(0.64f, 0.03f, 0.05f), 1.8f);

    private static final int ABYSS_COLOR = 0xFFa3070c;

    // Server curse state: cursed vessel UUID -> caster UUID / scheduler task id
    private static final Map<UUID, UUID> vesselToCaster = new HashMap<>();
    private static final Map<UUID, UUID> vesselToTask = new HashMap<>();

    // Vessels whose curse expired while offline; the final strike lands on rejoin
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

        // The Abyss requires blood: the target's, or the blood of one of their vessels
        ItemStack offHandItem = caster.getItemInHand(InteractionHand.OFF_HAND);
        if (!offHandItem.is(ModItems.BLOOD)) {
            refundFailedCast(caster);
            AbilityUtil.sendActionBar(caster, Component.translatable("ability.lotmcraft.curse.no_blood").withColor(ABYSS_COLOR));
            return;
        }

        String ownerStr = offHandItem.getOrDefault(ModDataComponents.BLOOD_OWNER, "");
        UUID bloodOwnerUUID = ownerStr.isEmpty() ? null : UUID.fromString(ownerStr);
        if (bloodOwnerUUID == null) {
            offHandItem.consume(1, caster);
            refundFailedCast(caster);
            AbilityUtil.sendActionBar(caster, Component.translatable("ability.lotmcraft.curse.no_target").withColor(ABYSS_COLOR));
            return;
        }

        UUID rootUUID = resolveRoot(serverLevel, bloodOwnerUUID);
        List<LivingEntity> vessels = gatherVessels(serverLevel, rootUUID);
        if (vessels.isEmpty()) {
            offHandItem.consume(1, caster);
            refundFailedCast(caster);
            AbilityUtil.sendActionBar(caster, Component.translatable("ability.lotmcraft.curse.no_target").withColor(ABYSS_COLOR));
            return;
        }

        offHandItem.consume(1, caster);
        for (LivingEntity vessel : vessels) {
            applyCurse(serverLevel, caster, vessel);
        }
        AbilityUtil.sendActionBar(caster, Component.translatable("ability.lotmcraft.curse_of_the_mind.applied").withColor(ABYSS_COLOR));
    }

    /**
     * The blood owner leads to the root of the vessel group: an avatar belongs to its
     * original owner, a marionette to its controller, anything else is the root itself.
     */
    private static UUID resolveRoot(ServerLevel level, UUID bloodOwnerUUID) {
        Entity ownerEntity = findEntity(level, bloodOwnerUUID);
        if (ownerEntity instanceof AvatarEntity avatar) {
            return avatar.getOriginalOwner();
        }
        if (ownerEntity instanceof ServerPlayer player) {
            MarionetteComponent marionette = player.getData(ModAttachments.MARIONETTE_COMPONENT.get());
            if (marionette.isMarionette()) {
                try {
                    return UUID.fromString(marionette.getControllerUUID());
                } catch (IllegalArgumentException ignored) {
                    return player.getUUID();
                }
            }
            return player.getUUID();
        }
        return bloodOwnerUUID;
    }

    /**
     * The root player and every vessel belonging to them: their marionettes and their
     * avatars / virtual personas. Vessels that are offline or unloaded are skipped.
     */
    private static List<LivingEntity> gatherVessels(ServerLevel level, UUID rootUUID) {
        List<LivingEntity> vessels = new ArrayList<>();
        MinecraftServer server = level.getServer();

        ServerPlayer root = server.getPlayerList().getPlayer(rootUUID);
        if (root != null) {
            vessels.add(root);
            for (UUID marionetteUUID : root.getData(ModAttachments.MARIONETTE_OWNER_COMPONENT.get()).getMarionettes()) {
                ServerPlayer marionette = server.getPlayerList().getPlayer(marionetteUUID);
                if (marionette != null) {
                    vessels.add(marionette);
                }
            }
            for (UUID avatarUUID : root.getData(ModAttachments.VIRTUAL_PERSONAS.get()).getAvatars()) {
                if (findEntity(level, avatarUUID) instanceof AvatarEntity avatar) {
                    vessels.add(avatar);
                }
            }
        } else {
            // Root offline: curse the blood owner itself if its entity is still around
            if (findEntity(level, rootUUID) instanceof LivingEntity living) {
                vessels.add(living);
            }
        }
        return vessels;
    }

    private static Entity findEntity(ServerLevel level, UUID uuid) {
        Entity entity = level.getEntity(uuid);
        if (entity != null) {
            return entity;
        }
        // Vessels may dwell in other dimensions
        for (ServerLevel otherLevel : level.getServer().getAllLevels()) {
            entity = otherLevel.getEntity(uuid);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private void applyCurse(ServerLevel level, ServerPlayer caster, LivingEntity vessel) {
        vesselToCaster.put(vessel.getUUID(), caster.getUUID());

        level.playSound(null, vessel.getX(), vessel.getY(), vessel.getZ(),
                SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.5f);
        ParticleUtil.spawnParticles(level, CURSE_DUST, vessel.getEyePosition(), 40, 0.75, 0);
        ParticleUtil.spawnParticles(level, ModParticles.BLACK_NOTE.get(), vessel.getEyePosition(), 12, 0.5, 0.05);

        // The victim is told briefly and mysteriously — never who, never why
        if (vessel instanceof ServerPlayer vesselPlayer) {
            ActingCapHelper.applyMindCurseCapReduction(vesselPlayer, CAP_REDUCTION_DURATION_MS);
            vesselPlayer.sendSystemMessage(Component.translatable("ability.lotmcraft.curse_of_the_mind.cursed")
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        }

        UUID taskId = ServerScheduler.scheduleForDuration(
                20, DRAIN_INTERVAL_TICKS, DURATION_TICKS,
                () -> drainTick(level, vessel.getUUID()),
                () -> finishCurse(level, vessel.getUUID()),
                level, () -> 1.0);
        vesselToTask.put(vessel.getUUID(), taskId);

        NeoForge.EVENT_BUS.post(new AbilityUsedEvent(level, vessel.position(), caster, vessel, this,
                interactionFlags, interactionRadius, interactionCacheTicks));
    }

    private void drainTick(ServerLevel level, UUID vesselUUID) {
        LivingEntity vessel = resolveVessel(level, vesselUUID);
        if (vessel == null) {
            return; // curse keeps running while the vessel is offline or unloaded
        }
        if (!vessel.isAlive()) {
            breakCurse(level, vessel);
            return;
        }

        // Bypasses per-sequence resistance: a Sequence 0 curse cannot be shrugged off
        vessel.getData(ModAttachments.SANITY_COMPONENT)
                .increaseSanityAndSyncIgnoreSequence(-DRAIN_PER_INTERVAL, vessel);

        ParticleUtil.spawnParticles(level, CURSE_DUST, vessel.getEyePosition(), 8, 0.6, 0.02);
        ParticleUtil.spawnParticles(level, ModParticles.BLACK_NOTE.get(), vessel.getEyePosition(), 3, 0.3, 0.03);
    }

    private static LivingEntity resolveVessel(ServerLevel level, UUID vesselUUID) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(vesselUUID);
        if (player != null) {
            return player;
        }
        return findEntity(level, vesselUUID) instanceof LivingEntity living ? living : null;
    }

    private void finishCurse(ServerLevel level, UUID vesselUUID) {
        vesselToCaster.remove(vesselUUID);
        vesselToTask.remove(vesselUUID);

        LivingEntity vessel = resolveVessel(level, vesselUUID);
        if (vessel == null) {
            pendingFinalStrikes.add(vesselUUID); // deliver the final strike on rejoin
            return;
        }

        applyFinalStrike(level, vessel);
    }

    private static void applyFinalStrike(ServerLevel level, LivingEntity vessel) {
        vessel.getData(ModAttachments.SANITY_COMPONENT)
                .increaseSanityAndSyncIgnoreSequence(-FINAL_SANITY_LOSS, vessel);

        level.playSound(null, vessel.getX(), vessel.getY(), vessel.getZ(),
                SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 2.0f, 0.4f);
        ParticleUtil.spawnParticles(level, CURSE_DUST, vessel.getEyePosition(), 60, 2.0, 0);
        ParticleUtil.spawnParticles(level, ModParticles.BLACK_NOTE.get(), vessel.getEyePosition(), 30, 1.0, 0.1);
        if (vessel instanceof ServerPlayer vesselPlayer) {
            vesselPlayer.sendSystemMessage(Component.translatable("ability.lotmcraft.curse_of_the_mind.final_strike")
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        }
    }

    // The curse dies with its vessel
    @SubscribeEvent
    public static void onVesselDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }

        LivingEntity vessel = event.getEntity();
        if (!vesselToCaster.containsKey(vessel.getUUID())) {
            return;
        }

        breakCurse((ServerLevel) vessel.level(), vessel);
    }

    private static void breakCurse(ServerLevel level, LivingEntity vessel) {
        UUID vesselUUID = vessel.getUUID();

        UUID taskId = vesselToTask.remove(vesselUUID);
        if (taskId != null) {
            ServerScheduler.cancel(taskId); // also cancels the linked final-strike task
        }
        vesselToCaster.remove(vesselUUID);
        pendingFinalStrikes.remove(vesselUUID);

        level.playSound(null, vessel.getX(), vessel.getY(), vessel.getZ(),
                SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 1.0f, 0.3f);
        ParticleUtil.spawnParticles(level, CURSE_DUST, vessel.getEyePosition(), 20, 0.8, 0.03);
        if (vessel instanceof ServerPlayer vesselPlayer) {
            vesselPlayer.sendSystemMessage(Component.translatable("ability.lotmcraft.curse_of_the_mind.broken_by_death")
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        }
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

    // Deferred final strikes for vessels whose curse expired while offline
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
