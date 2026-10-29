# Curse of the Mind — Abyss, Sequence 0

A new ability for **LotMCraft** (NeoForge, Minecraft 1.21.1), written to match the mod's existing conventions and helper APIs.

## Overview

A Sequence 0 Abyss ability that curses a guilty player and lets the Abyss slowly devour their mind.

- **Cost:** 5,000 spirituality · **Cooldown:** 10 minutes · **Duration:** 2.5 minutes
- **Not** copyable, replicable, shareable, or usable by NPCs

## Mechanics

**Target selection.** Using the ability opens the mod's shared player selection GUI (the same menu used by player divination). Only online "sinners" are listed — players with **10 or more player kills** (tracked per player, persists across sessions and respawns). If no sinner is online, the cast is refunded (spirituality + cooldown) with an action bar message.

**Sanity drain.** Every 10 seconds the victim loses sanity scaled by **their own sequence**: `5% × (1 + victim sequence)` — 5% at seq 0, 10% at seq 1, 15% at seq 2, and so on. The drain bypasses per-sequence sanity resistance (`increaseSanityAndSyncIgnoreSequence`), as befits a Sequence 0 curse.

**Growing hunger.** Every kill the victim commits *while cursed* raises the drain by **+1%** for the rest of the curse. Kills count regardless of source: melee, any pathway's abilities, projectiles, explosions — attribution is handled by a shared `resolveKiller` helper (see below).

**Loss of control.** Every drain tick rolls a chance of **1% per kill the victim ever committed** (any creature, lifetime), capped at 75%, applying the mod's `LOOSING_CONTROL` effect for 6 seconds (amplifier scales with kill count, max 3).

**Final strike.** The instant the 2.5 minutes expire, the victim loses an additional **20% of max sanity**. If they are offline at that moment, the strike is deferred and lands when they rejoin.

**Victim experience.** The cursed player receives short, mysterious chat messages (dark red italic) when the curse lands, when it breaks, and on the final strike — never revealing who cursed them or why. Visuals: blood-red dust (Abyss pathway color, brighter than Malice Seed's) plus black note particles on application, every drain tick, and the final strike.

**Breaking.** The curse ends when the victim dies — scheduled tasks are cancelled and no final strike is applied. The kill counter accumulated during the curse resets with it.

## Kill attribution

A single helper (`KillStatEventHandler.resolveKiller`) resolves the player responsible for any death:

1. direct damage source entity,
2. direct entity (player),
3. projectile owner (`Projectile.getOwner()`).

Both the global kill statistics (menu threshold) and the curse's kill counter use it. Two vanilla explosions that previously passed `null` as their source (`AirBulletAbility`, `FireRavenEntity`) now pass the caster, so explosion kills are attributed as well.

*Known limitation:* kills made by summoned creatures (spirits, golems, avatars) are not attributed to their summoner — the damage source carries no reference to the owner.

## Files

**New files**

| Path | Purpose |
|---|---|
| `src/main/java/de/jakob/lotm/beyonders/abilities/abyss/CurseOfTheMindAbility.java` | The ability itself |
| `src/main/java/de/jakob/lotm/attachments/KillStatsComponent.java` | Persistent kill statistics (`playerKills`, `totalKills`), serialized with `copyOnDeath()` |
| `src/main/java/de/jakob/lotm/events/KillStatEventHandler.java` | Global kill tracker + `resolveKiller` attribution helper |
| `src/main/resources/assets/lotmcraft/textures/abilities/curse_of_the_mind_ability.png` | 32×32 ability icon |

**Modified files**

| Path | Change |
|---|---|
| `attachments/ModAttachments.java` | Registers `KILL_STATS_COMPONENT` |
| `util/data/PlayerSelectionWorkType.java` | Adds `CURSE_OF_THE_MIND` to the enum |
| `network/packets/toServer/PlayerDivinationSelectedPacket.java` | New case: `CURSE_OF_THE_MIND -> CurseOfTheMindAbility.performCurseOnSelected(...)` |
| `beyonders/abilities/core/AbilityHandler.java` | Registers the ability in the ABYSS block |

**Lang entries** (`assets/lotmcraft/lang/en_us.json`):

```json
"lotmcraft.curse_of_the_mind_ability": "Curse of the Mind",
"lotmcraft.curse_of_the_mind_ability.description": "Lay the judgment of the Abyss upon a guilty mind. Unseen whispers drink its reason, drop by drop — and when they are done, they feast.",
"ability.lotmcraft.curse_of_the_mind.no_targets": "The Abyss finds no guilt worth judging",
"ability.lotmcraft.curse_of_the_mind.already_cursed": "This mind already belongs to the whispers",
"ability.lotmcraft.curse_of_the_mind.cursed": "Whispers crawl into your mind. They will not stop.",
"ability.lotmcraft.curse_of_the_mind.applied": "The whispers settle upon %s",
"ability.lotmcraft.curse_of_the_mind.final_strike": "The whispers feast upon your mind",
"ability.lotmcraft.curse_of_the_mind.broken_by_death": "Death tears the whispers from your mind... for now."
```

## Status

Compiled and verified with `gradlew build` (BUILD SUCCESSFUL) and tested in game: sinner filtering, drain scaling by sequence, drain growth per kill, loss-of-control rolls, death break, offline final-strike deferral, and refund on failed casts all work as described.
