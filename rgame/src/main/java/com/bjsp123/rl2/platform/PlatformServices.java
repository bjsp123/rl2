package com.bjsp123.rl2.platform;

import com.bjsp123.rl2.persistence.Persistence;

/**
 * Bundle of per-platform capabilities the launcher injects into the game -
 * the single seam between platform modules (desktop / android / web) and
 * {@code rgame}. Persistence is required; identity and entitlements default to
 * no-ops so existing launchers pass just a {@link Persistence} and behave
 * exactly as before.
 */
public final class PlatformServices {

    public final Persistence persistence;
    public final AuthService auth;
    public final EntitlementService entitlements;

    public PlatformServices(Persistence persistence,
                            AuthService auth,
                            EntitlementService entitlements) {
        this.persistence = persistence;
        this.auth = auth != null ? auth : new AuthService.NoAuthService();
        this.entitlements = entitlements != null
                ? entitlements : new EntitlementService.NoEntitlementService();
    }

    /** Persistence-only bundle: no identity, everything entitled. What the
     *  desktop and android launchers use today. */
    public static PlatformServices of(Persistence persistence) {
        return new PlatformServices(persistence, null, null);
    }
}
