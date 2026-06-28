# How Shattered Pixel Dungeon (radar fork) handles "donations" on Android & Steam

Source examined: `C:\Users\hwach\shattered-pixel-dungeon-radar`
(libGDX game; modules: `SPD-classes`, `core`, `android`, `desktop`, `ios`, `services`.)

## TL;DR — the surprising part

**SPD does NOT do in-app billing.** There is **no Google Play Billing on Android and no
Steam IAP / Steamworks on desktop** anywhere in this source. "Donations" are handled by
opening an **external Patreon link in the system browser**, the *same way on every
platform*, gated only by a one-time "don't nag again" flag. There is no purchase, no
entitlement check, no "is this player a supporter?" query.

So the per-platform difference the question assumes (Android IAP vs Steam) doesn't exist
here — and that's itself the lesson: SPD deliberately sidesteps store billing (and the
Steam rule against external payment prompts) by never charging in-app at all.

## What actually exists

### 1. The platform abstraction (the only "platform-aware" layer)
`SPD-classes/.../com/watabou/utils/PlatformSupport.java` — abstract base, one instance per
backend, set as `Game.platform` by each launcher:

```java
public abstract class PlatformSupport {
    public abstract void updateDisplaySize();
    public abstract void updateSystemUI();
    public abstract boolean connectedToUnmeteredNetwork();
    public abstract boolean supportsVibration();
    public boolean openURI(String uri) { return Gdx.net.openURI(uri); }  // <-- the donation path
    // (+ font helpers)
}
```
Backends: `android/.../AndroidPlatformSupport.java`, `desktop/.../DesktopPlatformSupport.java`,
`ios/.../IOSPlatformSupport.java`. **None override anything billing-related** — there is no
`supportsInAppPurchases()` / `isDonor()` / `launchPurchase()` capability at all.

### 2. The "donation" UI = a Patreon link
- `core/.../windows/WndSupportPrompt.java` — one-time popup with a button that does:
  `ShatteredPixelDungeon.platform.openURI("https://www.patreon.com/ShatteredPixel?...")`
  then `SPDSettings.supportNagged(true)`.
- `core/.../scenes/SupporterScene.java` — full info page, same Patreon `openURI(...)`.
  Reachable from the victory screen (`WndVictoryCongrats`).
- Trigger: `core/.../items/keys/WornKey.java#doPickUp` shows `WndSupportPrompt` once,
  `if (!SPDSettings.supportNagged())`.

### 3. The "supporter state" = a single boolean nag flag
`core/.../SPDSettings.java`:
```java
public static void    supportNagged(boolean v){ put(KEY_SUPPORT_NAGGED, v); }
public static boolean supportNagged(){ return getBoolean(KEY_SUPPORT_NAGGED, false); }
```
That's the whole "did we already ask?" state. There is no persisted "has donated" entitlement.

### 4. No Steam, confirmed
- `grep -rni steam **/*.gradle` → nothing. No `steamworks4j` / `codedisaster` / `SteamAPI`.
- `desktop/build.gradle` is a plain libGDX LWJGL3 app (org.beryx.runtime for packaging),
  no Steam SDK, no steam build flavor.
- The Patreon link is shown identically on desktop; there is **no distribution/store gate**
  (e.g. no "hide donate link on Steam"). In a real Steam release this would matter — Steam's
  rules forbid soliciting external payments inside the app — but this fork doesn't address it.

## The pattern SPD *does* use for per-platform behaviour (the template to copy)

SPD swaps platform-specific service implementations **per build flavor via separate Gradle
sub-modules**, selected at dependency time. This is the mechanism you'd reuse to add real,
per-store billing.

`settings.gradle`:
```
include ':services:updates:debugUpdates'
include ':services:updates:githubUpdates'
include ':services:news:debugNews'
include ':services:news:shatteredNews'
```

`services/.../updates/UpdateService.java` — abstract service interface in a shared module:
```java
public abstract class UpdateService {
    public abstract boolean supportsUpdatePrompts();
    public abstract void checkForUpdate(...);
    public abstract boolean supportsReviews();      // closest analog to "supports donations"
    public abstract void openReviewURI();
}
```
Each flavor module provides a concrete `UpdateImpl` with `supportsUpdates()` +
`getUpdateService()`. `android/build.gradle` wires which flavor compiles in per build type:
```gradle
buildTypes {
  debug   { dependencies { debugImplementation   project(':services:updates:debugUpdates') } }
  release { dependencies { releaseImplementation project(':services:updates:githubUpdates') } }
}
```
The launcher then does `if (UpdateImpl.supportsUpdates()) Updates.service = UpdateImpl.getUpdateService();`.
**A billing feature would be added the same way** (a `services:billing:*` family), NOT by
forking core code.

## How you'd add real in-app donations to a libGDX game like this (and rl2)

rl2 already mirrors SPD's shape (it has `rgame` core + `android` + `desktop` launchers), so:

1. **Capability on the platform abstraction.** Add to rl2's platform hook (analogous to
   `PlatformSupport`) something like:
   ```java
   boolean supportsInAppDonations();   // false by default
   void    launchDonation(String productId, DonationCallback cb);
   boolean isDonor();                  // reads a persisted entitlement
   ```
2. **A `BillingService` abstraction + per-store flavor modules** (copy the Updates pattern):
   - `billing/googleBilling` → Google Play Billing (`com.android.billingclient:billing`):
     `BillingClient`, `queryProductDetailsAsync`, `launchBillingFlow`, a
     `PurchasesUpdatedListener`, then `acknowledgePurchase` (or `consume` for repeatable
     donations); persist the entitlement and surface it via `isDonor()`.
   - `billing/steamBilling` → Steamworks (steamworks4j) **OR** a no-op stub. Note: Steam
     micro-transactions go through Steam's own MicroTxn API and Steamworks overlay — you may
     **not** show an external Patreon/Stripe link in a Steam build. Most small games either
     stub donations off on Steam (sell the game itself) or use the Steam MicroTxn flow.
   - `billing/noBilling` → default stub returning `supportsInAppDonations() == false`.
3. **Gate the UI on the capability**, never on the platform name:
   ```java
   if (platform.supportsInAppDonations()) showDonateButton();
   else if (!platform.isSteam())          showExternalLink();   // Patreon-style, like SPD
   ```
4. **Wire per build** in `android/build.gradle` / `desktop/build.gradle` exactly like SPD wires
   `updates`/`news`, so the Play build pulls `googleBilling`, the Steam build pulls
   `steamBilling`, and a generic build pulls `noBilling`.

## One-line takeaway
SPD's whole "monetization" surface is `platform.openURI(patreonLink)` + a nag boolean; the
*reusable architecture* is its **PlatformSupport abstraction** plus the **per-flavor
`services/*` sub-module pattern** — that's the seam where you'd bolt on Google Play Billing on
Android and a Steam (or no-op) implementation on desktop, gated by a capability flag rather
than by hard-coding platform checks.
