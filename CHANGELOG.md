# Changelog

All notable changes to the **Curse of the Mind** ability are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [2.0.0] - 2026-10-29 — Blood-Based Redesign

The targeting design was rebuilt from scratch after playtesting: instead of listing online players by kill statistics, the curse now follows the blood of the prey — matching how the existing Demoness Curse works, but on a much larger scale.

### Added

- **Blood-based targeting** — the curse requires the target's blood in the offhand (same mechanic as the Demoness Curse). The blood of the target's marionettes, avatars or virtual personas works as well and curses the entire vessel group.
- **Group curse** — the blood owner is resolved to the root of its vessel group; the curse applies simultaneously to the root player, all their marionettes, and all their avatars / virtual personas (`AvatarEntity`). Offline or unloaded vessels are skipped.
- **Hollowing** — player vessels have their max sanity reduced by 20% for 10 minutes. Implemented in `ActingCapHelper` as a persistent-data expiry that survives relogs, stacks on top of the acting cap, and also applies to Sequence 0 players.
- Fail-safe casting: a cast that never curses anyone (no blood, unresolvable owner) refunds spirituality and cooldown.

### Changed

- **Duration** reduced from 2.5 minutes to **60 seconds** (drain ticks unchanged: 5% of max sanity every 10 seconds, bypassing per-sequence sanity resistance).
- The final strike now lands on **every cursed vessel**, not just the main target.
- **Removed the player-kill threshold** — any target with blood can be cursed, closing the gap where valid victims never appeared in the menu.

### Removed

- The sinner-list menu targeting and the kill-statistics tracker (`KillStatsComponent` / `KillStatEventHandler`) that powered it — the shared `PlayerSelectionWorkType` enum and packet were restored to their stock state. No changes to shared packets or GUIs remain.

## [1.0.0] - 2026-10-24 — Initial Prototype

### Added

- Sequence 0 Abyss curse ability (cost 5,000 spirituality, 10-minute cooldown, 2.5-minute duration).
- Player selection menu (shared divination GUI) listing online "sinners" with 10+ player kills, tracked by a persistent `KillStatsComponent`.
- Sanity drain of 5% per 10 seconds, scaling with the victim's sequence and deepening by +1% per kill committed under the curse.
- Loss-of-control rolls (1% per lifetime kill, capped at 75%).
- Final sanity strike of 20% at expiry, deferred until rejoin if the victim was offline.
- Curse breaking on the victim's death; mystical chat notifications and blood-red dust particles.

## Technical Notes

- Only two shared-code touch points: `AbilityHandler` (one registration line) and `ActingCapHelper` (the cap-reduction hook).
- Built and verified with `gradlew build` (BUILD SUCCESSFUL); tested in game on a NeoForge 1.21.1 (21.1.217) server.
