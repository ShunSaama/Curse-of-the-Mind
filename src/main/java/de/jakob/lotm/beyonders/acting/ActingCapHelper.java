package de.jakob.lotm.beyonders.acting;

import de.jakob.lotm.LOTMCraft;
import de.jakob.lotm.gamerule.ModGameRules;
import de.jakob.lotm.network.PacketHandler;
import de.jakob.lotm.network.packets.toClient.SyncActingCapPacket;
import de.jakob.lotm.util.BeyonderData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

public class ActingCapHelper {

    public static final String CAP_REDUCTION_KEY = "lotm_acting_cap_reduction";
    public static final String MISSED_ACTING_KEY = "lotm_missed_acting";
    public static final String MIND_CURSE_CAP_KEY = "lotm_mind_curse_cap_expiry";

    // Max sanity multiplier while the Curse of the Mind cap effect is active (-20%)
    private static final float MIND_CURSE_CAP_MULTIPLIER = 0.8f;

    static final float[] INCREMENTS = {0.07f, 0.05f, 0.03f};
    public static final float CAP_PER_SEQUENCE_UP = 0.15f;

    public static boolean skipNextCapApplication = false;

    public static boolean isCapSuppressed(ServerLevel level) {
        return !level.getGameRules().getBoolean(ModGameRules.APPLY_NOT_ACTING_PENALTY);
    }

    /**
     * Reduces the player's sanity cap by the Curse of the Mind amount for the given duration.
     * Stored as an absolute expiry timestamp in persistent data so it survives relogs.
     */
    public static void applyMindCurseCapReduction(Player player, long durationMs) {
        player.getPersistentData().putLong(MIND_CURSE_CAP_KEY, System.currentTimeMillis() + durationMs);
    }

    public static float getMindCurseCapMultiplier(Player player) {
        long expiry = player.getPersistentData().getLong(MIND_CURSE_CAP_KEY);
        if (expiry <= 0) return 1f;
        if (System.currentTimeMillis() >= expiry) {
            player.getPersistentData().remove(MIND_CURSE_CAP_KEY);
            return 1f;
        }
        return MIND_CURSE_CAP_MULTIPLIER;
    }

    public static float getCapReduction(Player player) {
        return player.getPersistentData().getFloat(CAP_REDUCTION_KEY);
    }

    static void setCapReduction(Player player, float amount) {
        // Snap to 0.1% steps: the increments are whole percents, but float residue from
        // 0.15 - 0.07 - 0.05 - 0.03 otherwise leaves the cap fractionally below 100%
        float rounded = Math.round(amount * 1000f) / 1000f;
        player.getPersistentData().putFloat(CAP_REDUCTION_KEY, Math.clamp(rounded, 0f, 1f));
    }

    public static float getEffectiveCap(Player player) {
        // The mind curse clamps the cap for every sequence, including the seq 0 exemption
        if (BeyonderData.getSequence(player) == 0) return getMindCurseCapMultiplier(player);
        if (player.isCreative()) return 1f;
        if (player.level() instanceof ServerLevel serverLevel && isCapSuppressed(serverLevel)) {
            return getMindCurseCapMultiplier(player);
        }
        return Math.max(0f, (1f - getCapReduction(player)) * getMindCurseCapMultiplier(player));
    }

    public static float getEffectiveCap(LivingEntity entity) {
        if (entity instanceof Player player) return getEffectiveCap(player);
        return 1f;
    }

    public static CompoundTag getMissedActing(Player player) {
        return player.getPersistentData().getCompound(MISSED_ACTING_KEY);
    }

    public static void clearCap(Player player) {
        player.getPersistentData().remove(CAP_REDUCTION_KEY);
        player.getPersistentData().remove(MISSED_ACTING_KEY);
        syncToClient(player);
    }

    public static void onSequenceUp(LivingEntity entity, String oldPathway, int oldSeq) {
        if (!(entity instanceof Player player)) return;
        if (player.isCreative()) {
            // Creative players are exempt from the acting cap; drop any cap they carried over
            clearCap(player);
            return;
        }

        if (skipNextCapApplication) {
            skipNextCapApplication = false;
            return;
        }

        if (player.level() instanceof ServerLevel serverLevel && isCapSuppressed(serverLevel)) {
            syncToClient(player);
            return;
        }

        setCapReduction(player, getCapReduction(player) + CAP_PER_SEQUENCE_UP);

        List<ActingTask> tasks = ActingTaskRegistry.getTasksFor(oldPathway, oldSeq);
        if (!tasks.isEmpty()) {
            int completedCount = 0;
            for (ActingTask task : tasks) {
                if (completedCount >= INCREMENTS.length) break;
                if (ActingHelper.isTriggerUnlocked(oldPathway, oldSeq, player, task.getId())) {
                    completedCount++;
                }
            }

            int maxMissed = INCREMENTS.length - completedCount;
            CompoundTag tasksTag = new CompoundTag();
            int missedCount = 0;
            for (ActingTask task : tasks) {
                if (missedCount >= maxMissed) break;
                if (!ActingHelper.isTriggerUnlocked(oldPathway, oldSeq, player, task.getId())) {
                    tasksTag.putString(task.getId(), "");
                    missedCount++;
                }
            }

            if (!tasksTag.isEmpty()) {
                CompoundTag group = new CompoundTag();
                group.putInt("startIndex", completedCount);
                group.putInt("initialCount", tasksTag.size());
                group.put("tasks", tasksTag);

                CompoundTag missed = getMissedActing(player);
                missed.put(oldPathway + "/" + oldSeq, group);
                player.getPersistentData().put(MISSED_ACTING_KEY, missed);
            }
        }

        syncToClient(player);
    }

    public static void onActingUnlocked(Player player, String pathway, int sequence, String taskId) {
        if (player.isCreative()) return;

        if (player.level() instanceof ServerLevel serverLevel && isCapSuppressed(serverLevel)) return;

        List<ActingTask> tasks = ActingTaskRegistry.getTasksFor(pathway, sequence);

        int totalUnlocked = 0;
        for (ActingTask task : tasks) {
            if (ActingHelper.isTriggerUnlocked(pathway, sequence, player, task.getId())) {
                totalUnlocked++;
            }
        }

        int position = totalUnlocked - 1;
        if (position >= 0 && position < INCREMENTS.length) {
            setCapReduction(player, Math.max(0f, getCapReduction(player) - INCREMENTS[position]));
            syncToClient(player);
        }
    }

    public static boolean tryCompleteMissedActing(Player player, String taskId) {
        CompoundTag missed = getMissedActing(player);
        if (missed.isEmpty()) return false;

        String playerPathway = BeyonderData.getPathway(player);
        boolean found = false;
        float totalRestore = 0f;

        for (String groupKey : missed.getAllKeys()) {
            String[] parts = groupKey.split("/", 2);
            if (parts.length != 2 || !parts[0].equals(playerPathway)) continue;

            CompoundTag group = missed.getCompound(groupKey);
            CompoundTag tasks = group.getCompound("tasks");

            if (!tasks.contains(taskId)) continue;

            int startIndex = group.getInt("startIndex");
            int initialCount = group.getInt("initialCount");
            int retroCompleted = initialCount - tasks.size();
            int restorationIndex = startIndex + retroCompleted;

            if (restorationIndex < INCREMENTS.length) {
                totalRestore += INCREMENTS[restorationIndex];
            }

            tasks.remove(taskId);
            if (tasks.isEmpty()) {
                missed.remove(groupKey);
            } else {
                group.put("tasks", tasks);
                missed.put(groupKey, group);
            }

            found = true;
            break;
        }

        if (found) {
            player.getPersistentData().put(MISSED_ACTING_KEY, missed);
            if (totalRestore > 0f) {
                setCapReduction(player, Math.max(0f, getCapReduction(player) - totalRestore));
            }
            syncToClient(player);
        }

        return found;
    }

    /**
     * Applies a fresh cap for the player's current sequence, discarding all previous
     * cap and missed-acting data. Used on death, where the acting-method unlocks are
     * also reset — the player restores the cap by redoing the methods of their current
     * sequence, so nothing needs to be tracked as missed.
     */
    public static void reinstateCapForCurrentSequence(Player player) {
        player.getPersistentData().remove(CAP_REDUCTION_KEY);
        player.getPersistentData().remove(MISSED_ACTING_KEY);

        if (!BeyonderData.isBeyonder(player) || BeyonderData.getSequence(player) == 0
                || player.isCreative()
                || (player.level() instanceof ServerLevel serverLevel && isCapSuppressed(serverLevel))) {
            syncToClient(player);
            return;
        }

        setCapReduction(player, CAP_PER_SEQUENCE_UP);
        syncToClient(player);
    }

    public static void syncToClient(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            float capReduction = getCapReduction(player);

            if (player.isCreative()
                    || (player.level() instanceof ServerLevel serverLevel && isCapSuppressed(serverLevel))) {
                capReduction = 0f;
            }

            PacketHandler.sendToPlayer(serverPlayer, new SyncActingCapPacket(
                    capReduction,
                    getMissedActing(player)
            ));
        }
    }
}