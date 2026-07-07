# rl2

**rl2** (working title) is a chunky, tap-friendly roguelike about descending into a vast, failing tomb. Explore procedurally generated floors, light the ancient beacon network, craft strange magics at gem hearths, and — if you make it deep enough — face the Great Wraith at the bottom.

It plays differently from most roguelikes in one important way: **you don't get XP for killing things.** Experience comes from exploring new ground, so every fight is a choice, not a chore. Sneak past, set the room on fire, shove something into a chasm, or just run.

Built in Java with [libGDX](https://libgdx.com/); runs on Windows desktop and Android, with a web build in progress.

## Download — playtest candidate

| Platform | Download |
|---|---|
| Windows (x64) | [rl2-windows-x64.zip](https://github.com/bjsp123/rl2/releases/latest/download/rl2-windows-x64.zip) — unzip and run `rl2.exe`, no install needed |
| Android | [rl2.apk](https://github.com/bjsp123/rl2/releases/latest/download/rl2.apk) — sideload (you may need to allow "install unknown apps") |

All releases live on the [Releases page](https://github.com/bjsp123/rl2/releases).

## Thank you, playtesters!

If you're reading this because you agreed to playtest: **thank you.** Every run you play, every death you grumble about, and every "this felt weird" you report makes the game better. Nothing is sacred — if something confused you, bored you, or killed you unfairly, we want to hear about it. Please file thoughts, bugs, and screenshots as [GitHub issues](https://github.com/bjsp123/rl2/issues).

### Three tips before you dive in

1. **Don't fight everything.** Kills give no XP — you level up by exploring and grabbing XP balls. If a corridor full of kobolds isn't guarding anything you want, walk the other way.
2. **Wands and jade items don't recharge on their own.** Charges come from charge pills found while exploring, so top up before a big fight — and don't hoard eight charges of "wand of fire" all the way to your death screen. Use the toys.
3. **Beacons are a bargain with teeth.** Each one you light becomes a fast-travel point, is worth a lot of score, and brings you closer to a perfect run — but the deeper floors get nastier for every beacon lit. Decide what kind of run you're having.

## Building from source

```
./gradlew :desktop:run          # run the game on desktop
./gradlew :desktop:jar          # build a runnable fat jar (desktop/build/libs)
./gradlew :android:assembleDebug # build an installable Android APK
./gradlew :rlib:test            # run the logic test suite
```

Requires JDK 17+ (the Gradle build pins its own JVM; see `gradle.properties`). Module layout: `rlib` is the pure game model/logic, `rgame` is rendering and UI, `desktop`/`android`/`web` are launchers, and `rai` contains the autoplay/regression harness.
