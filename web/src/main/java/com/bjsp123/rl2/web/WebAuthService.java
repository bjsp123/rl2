package com.bjsp123.rl2.web;

import com.badlogic.gdx.Gdx;
import com.bjsp123.rl2.platform.AuthService;
import java.util.ArrayList;
import java.util.List;

/**
 * Browser {@link AuthService} over Supabase Auth (via {@link JsBridge}).
 * Sign-in is a full-page OAuth redirect: {@link #signIn} navigates away, and
 * on return supabase-js restores the session before the game boots - so the
 * signed-in state is simply readable at any time, per the interface contract.
 */
public final class WebAuthService implements AuthService {

    private final List<Runnable> listeners = new ArrayList<>();

    public WebAuthService() {
        JsBridge.onAuth(() -> {
            // Defer to the GL loop so listeners never observe mid-frame state.
            if (Gdx.app != null) {
                Gdx.app.postRunnable(this::fireListeners);
            } else {
                fireListeners();
            }
        });
        JsBridge.init();
    }

    private void fireListeners() {
        for (Runnable r : listeners) r.run();
    }

    @Override public boolean isAvailable() { return JsBridge.available(); }

    @Override public String userId() { return JsBridge.getUserId(); }

    @Override public String displayName() { return JsBridge.getDisplayName(); }

    @Override
    public void signIn(Provider provider) {
        JsBridge.signIn(provider == Provider.FACEBOOK ? "facebook" : "google");
    }

    @Override public void signOut() { JsBridge.signOut(); }

    @Override public void addListener(Runnable onAuthStateChanged) {
        listeners.add(onAuthStateChanged);
    }
}
