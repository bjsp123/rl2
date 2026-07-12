---
name: verify
description: Verify an rl2 code or data change using the project's verification ladder (compile, tests, AI regression sweep, run the game). Use before committing any nontrivial change.
---

# Verify an rl2 change

Climb the ladder to the rung matching the blast radius (details in
CLAUDE.md "Verification ladder" and `docs/diagnostics.md`):

1. **Compile:** `./gradlew :rlib:compileJava :rgame:compileJava :rai:compileJava`
2. **Tests:** `./gradlew :rlib:test :rai:test :rgame:test` — required for any
   rlib/rai change or any `assets/data` CSV edit. `:rai:test` includes the
   seed-determinism smoke test; if it fails after a logic change, you almost
   certainly changed RNG draw order (all logic randomness must come from the
   `SimRng`-registered generators, e.g. `MobSystem.RANDOM`).
3. **AI/balance changes:** `./gradlew :rai:regression` — compare average
   depth and HP-lost-by-mechanism tables against a baseline run on
   unmodified `main`. Wins are rare (~0–1 per sweep); zero wins alone is
   NOT a regression signal.
4. **Rendering/UI/input changes:** `./gradlew :desktop:run` and play the
   changed flow — this is the only rung that covers rgame behavior.

Never run `:android:*` or `:web:*` builds as verification unless the user
asks.
