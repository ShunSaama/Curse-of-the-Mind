# Curse of the Mind — Abyss, Sequence 0

A new ability for **LotMCraft** (NeoForge, Minecraft 1.21.1), written to match the mod's existing conventions and helper APIs.

## Overview

A Sequence 0 Abyss blood curse. Spill the blood of a guilty mind — or the blood of one of its vessels — and the Abyss seizes the whole group: the whispers slowly drink their reason, hollow out what remains, and feast when the curse expires.

- **Cost:** 5,000 spirituality · **Cooldown:** 10 minutes · **Duration:** 60 seconds
- **Not** copyable, replicable, shareable, or usable by NPCs

## Mechanics

**Blood requirement.** The caster must hold the target's **blood** in their offhand (same mechanic as the Demoness Curse: `ModItems.BLOOD` with a `BLOOD_OWNER` data component). The blood of the target's **marionettes, avatars or virtual personas** works as well — using any vessel's blood curses the entire group.

**Group curse.** The blood owner is resolved to the root of its vessel group (an avatar resolves to its original owner, a marionette to its controller). The curse then applies **simultaneously** to:

- the root player,
- every marionette belonging to them (`MARIONETTE_OWNER_COMPONENT` -> online marionette players),
- every avatar / virtual persona belonging to them (`VIRTUAL_PERSONAS.getAvatars()` -> `AvatarEntity`).

Offline or unloaded vessels are skipped. The blood is consumed on a successful cast; a failed cast (no blood / unresolvable owner) refunds spirituality and cooldown.

**Sanity drain.** Every 10 seconds each cursed vessel loses **5% of its max sanity** for 60 seconds, bypassing per-sequence sanity resistance (`increaseSanityAndSyncIgnoreSequence`) — a Sequence 0 curse cannot be shrugged off.

**Hollowing.** Player vessels also have their **max sanity reduced by 20% for 10 minutes**. This is implemented as a persistent-data expiry timestamp (`ActingCapHelper.applyMindCurseCapReduction`) that `getEffectiveCap` multiplies into the cap — it survives relogs, stacks on top of the acting cap, and also affects Sequence 0 players (the acting cap normally exempts them).

**Final strike.** The instant the 60 seconds expire, each vessel loses another **20% of max sanity**. If a vessel is offline at that moment, the strike is deferred and lands when they rejoin.

**Victim experience.** Cursed players receive short, mysterious chat messages (dark red italic) when the curse lands, when it breaks on their death, and on the final strike — never revealing who cursed them or why. Visuals: blood-red dust (Abyss pathway color, brighter than Malice Seed's) plus black note particles on application, every drain tick, and the final strike.

**Breaking.** The curse on a vessel ends when that vessel dies (its scheduled task is cancelled, no final strike for it). Other vessels stay cursed.

## Files

**New files**

| Path | Purpose |
|---|---|
| `src/main/java/de/jakob/lotm/beyonders/abilities/abyss/CurseOfTheMindAbility.java` | The ability itself |
| `src/main/resources/assets/lotmcraft/textures/abilities/curse_of_the_mind_ability.png` | 32×32 ability icon |

**Modified files**

| Path | Change |
|---|---|
| `beyonders/acting/ActingCapHelper.java` | Adds `MIND_CURSE_CAP_KEY` + `applyMindCurseCapReduction` / `getMindCurseCapMultiplier`; `getEffectiveCap` now factors the mind-curse multiplier in (including the seq 0 branch) |
| `attachments/ModAttachments.java` | *(no net change required — see note)* |
| `beyonders/abilities/core/AbilityHandler.java` | Registers the ability in the ABYSS block: `abilities.add(new CurseOfTheMindAbility("curse_of_the_mind_ability"));` |

*Note:* `ModAttachments.java` is included for completeness; this version of the ability needs no new attachment (the cap effect lives in player persistent data). No changes to shared packets, GUIs or enums are required — targeting is fully blood-based.

**Lang entries** (`assets/lotmcraft/lang/en_us.json`):

```json
"lotmcraft.curse_of_the_mind_ability": "Curse of the Mind",
"lotmcraft.curse_of_the_mind_ability.description": "Spill the blood of a guilty mind — or the blood of its puppets — and lay the judgment of the Abyss upon it. Unseen whispers drink its reason, drop by drop, and what remains of the vessel they hollow out.",
"ability.lotmcraft.curse_of_the_mind.already_cursed": "This mind already belongs to the whispers",
"ability.lotmcraft.curse_of_the_mind.cursed": "Whispers crawl into your mind. They will not stop.",
"ability.lotmcraft.curse_of_the_mind.applied": "The whispers settle upon your prey",
"ability.lotmcraft.curse_of_the_mind.final_strike": "The whispers feast upon your mind",
"ability.lotmcraft.curse_of_the_mind.broken_by_death": "Death tears the whispers from your mind... for now."
```

(The "no blood" / "no target" action bar messages reuse the existing `ability.lotmcraft.curse.no_blood` / `ability.lotmcraft.curse.no_target` keys of the Demoness Curse.)

## Status

Compiled and verified with `gradlew build` (BUILD SUCCESSFUL). Tested in game: blood-based targeting, group resolution across marionettes/avatars, drain, max-sanity reduction (persists across relogs and applies at seq 0), final strike, death break, offline final-strike deferral, and refund on failed casts.
