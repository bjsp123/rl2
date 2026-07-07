package com.bjsp123.rl2.platform;

/**
 * Platform capability: user identity. Implemented per-launcher (web build backs
 * it with the Supabase JS SDK; desktop/android use {@link NoAuthService} until
 * they grow an OAuth flow). Same seam pattern as
 * {@link com.bjsp123.rl2.persistence.Persistence}: constructed by the launcher,
 * handed to the game via {@link PlatformServices}, never platform-checked.
 *
 * <p>Sign-in may involve a full page redirect on web - the app can be reloaded
 * mid-flow - so implementations must support "already signed in at boot":
 * callers read {@link #userId()} at any time rather than waiting on a
 * completion callback from {@link #signIn}.
 */
public interface AuthService {

    enum Provider { GOOGLE, FACEBOOK }

    /** True when this platform can sign users in at all. UI hides account
     *  affordances entirely when false. */
    boolean isAvailable();

    /** Stable unique id of the signed-in user, or null when signed out. */
    String userId();

    /** Human-readable name/email of the signed-in user, or null. */
    String displayName();

    /** Begin an interactive sign-in. On web this navigates away to the OAuth
     *  provider and reloads the app on return. */
    void signIn(Provider provider);

    void signOut();

    /** Register a listener invoked (on the GL thread) whenever the signed-in
     *  state changes - including the initial session restore after boot. */
    void addListener(Runnable onAuthStateChanged);

    /** Default no-op implementation for platforms without identity. */
    final class NoAuthService implements AuthService {
        @Override public boolean isAvailable() { return false; }
        @Override public String userId() { return null; }
        @Override public String displayName() { return null; }
        @Override public void signIn(Provider provider) {}
        @Override public void signOut() {}
        @Override public void addListener(Runnable onAuthStateChanged) {}
    }
}
