# Diagnostic & balance harnesses

All are headless Gradle `JavaExec` tasks (group `diagnostics`) that load
the real `assets/data` CSVs via `ArenaHarness`. None are pass/fail gates —
they emit CSV/stdout for human comparison against a baseline (typically a
run of the same task on unmodified `main`).

## AI / autoplay (`rai`)

- `./gradlew :rai:regression [--args=N]` — `RegressionRunMain`. SMART agent
  × 3 classes × difficulty levels (SUPEREASY…HARD), N runs per cell
  (default 3), 100k-tick timeout. Reports W/D/T per cell plus
  HP-lost-by-mechanism and by-attacker tables; writes `regression.csv`.
  **Interpretation:** wins are RARE — measured 2026-07-03, ~0–1 wins per
  45-run sweep; most runs end in death or timeout, top killers
  GREAT_WRAITH / ENV / mirror-match enemy players. **Zero wins in a sweep
  is NOT by itself a regression** — compare average depth and the
  death/mechanism tables against a baseline run instead. Known residual
  timeout cause: exploration stall when the stairs are behind a
  chasm/one-time door the agent won't cross.
- `./gradlew :rai:autoplay [--args=N]` — `AutoplayRunMain`. Full-game
  autoplay, always starts at character level 1, so ~0% wins **by design**;
  one row per run to `autoplay.csv`.
- `./gradlew :rai:runSmartAgent [--args=level]` — single headless run to
  death/timeout/win; `smart_agent_run.csv`.
- `./gradlew :rai:rankSmartArena` — MOB-brain vs SMART-brain win-rate
  deltas across the NPC field.

## World generation / loot (`rlib`)

- `./gradlew :rlib:objectTable [--args=N]` — `WorldObjectTableMain`.
  Generates N worlds, tabulates items by type/enchant/depth/drop-kind
  (`level`, `mob`, `unique-mob`, `unique-room`) → `results/world_objects.csv`.
  Excludes gems (reported separately) and walk-over POWERUP pills; counts
  mob loot only for mobs that actually drop. Used for loot rebalancing —
  owner's rough per-world targets: equipment ~25, wands ~8, bombs ~50,
  gems ~50, potions+food ~30, tools ~5. Rebalance by editing the CSVs
  (items/mobs/themedrooms/config), not by adding code knobs.
- `./gradlew :rlib:dumpWorld [--args=seed]` — one world as YAML
  (`world_dump.yaml`).
- `./gradlew :rlib:lootStats` — average loot per level by category.
- `./gradlew :rlib:dumpRecipes` — validates `recipes.csv`, prints
  gem-economy balance report.
- `./gradlew :rlib:rankPower` / `rankPowerFull` / `rank1vN` /
  `bombLoadoutArena` — arena win-rate rankings (`arena_*.csv`,
  `bomb_loadout_arena.csv`).

## Other

- `./gradlew :rgame:debugSave` — `SaveLoadDebugMain`: round-trips an
  on-disk save to surface load exceptions offline.
- `./gradlew :rgame:dumpItemLore` — item lore text dump.
- `./gradlew :desktop:profileRun` / `profileHotMethods` /
  `profileAllocations` — JFR profiling of a desktop session.
