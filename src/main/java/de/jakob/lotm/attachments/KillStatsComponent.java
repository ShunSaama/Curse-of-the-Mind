package de.jakob.lotm.attachments;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Lifetime kill statistics of a player.
 * <p>
 * {@code playerKills} counts killed players, {@code totalKills} counts every living entity killed.
 * Used by the Abyss "Curse of the Mind" ability to find curse-worthy targets
 * and to scale the target's loss-of-control chance.
 */
public class KillStatsComponent {

    private int playerKills = 0;
    private int totalKills = 0;

    public KillStatsComponent() {}

    public int getPlayerKills() {
        return playerKills;
    }

    public int getTotalKills() {
        return totalKills;
    }

    public void incrementPlayerKills() {
        playerKills++;
    }

    public void incrementTotalKills() {
        totalKills++;
    }

    public static final IAttachmentSerializer<CompoundTag, KillStatsComponent> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public KillStatsComponent read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider lookup) {
                    KillStatsComponent component = new KillStatsComponent();
                    component.playerKills = tag.getInt("playerKills");
                    component.totalKills = tag.getInt("totalKills");
                    return component;
                }

                @Override
                public CompoundTag write(KillStatsComponent component, HolderLookup.Provider lookup) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("playerKills", component.playerKills);
                    tag.putInt("totalKills", component.totalKills);
                    return tag;
                }
            };
}
