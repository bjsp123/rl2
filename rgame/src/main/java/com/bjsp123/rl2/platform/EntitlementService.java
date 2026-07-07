package com.bjsp123.rl2.platform;

/**
 * Platform capability: paid-content entitlement. The only entitlement today is
 * the one-time full-game unlock. Web backs this with a Supabase
 * {@code entitlements} row written by the Stripe webhook; desktop/android use
 * {@link NoEntitlementService}, which grants everything (nothing is gated on
 * those platforms).
 */
public interface EntitlementService {

    /** True when the player owns the full game (or the platform doesn't gate). */
    boolean ownsFullGame();

    /** Re-fetch entitlement state from the backing store, if any. */
    void refresh();

    /** Start the purchase flow. On web this redirects to Stripe Checkout. */
    void beginPurchase();

    /** Register a listener invoked (on the GL thread) when entitlement state
     *  changes - e.g. after {@link #refresh()} or returning from checkout. */
    void addListener(Runnable onEntitlementChanged);

    /** Default implementation for platforms without a store: everything owned. */
    final class NoEntitlementService implements EntitlementService {
        @Override public boolean ownsFullGame() { return true; }
        @Override public void refresh() {}
        @Override public void beginPurchase() {}
        @Override public void addListener(Runnable onEntitlementChanged) {}
    }
}
