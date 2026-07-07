package com.bjsp123.rl2.ui.v2;

import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.platform.AuthService;

/**
 * V2 account screen - sign in / sign out plus a cloud-save status line.
 * Only reachable when the platform's {@link AuthService#isAvailable()} (the
 * web build with Supabase configured); desktop/android never show the entry
 * button, so this screen never renders there.
 *
 * <p>Sign-in is a full-page OAuth redirect on web: tapping a provider button
 * navigates away and the app reloads signed-in on return - so this screen
 * makes no attempt to show an in-flight state. Auth changes that DO happen
 * while the screen is up (e.g. session restore finishing) rebuild the layout
 * via the auth listener.
 */
public final class V2Account extends V2Screen {

    private static final float BTN_H   = 42f;
    private static final float BTN_GAP = 8f;
    private static final float WINDOW_PAD = 10f;

    private final Rl2Game game;
    private final Rect window = new Rect();
    private boolean listenerAdded = false;

    public V2Account(Rl2Game game, UiCtx ctx) {
        super(ctx);
        this.game = game;
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override
    protected void buildLayout() {
        AuthService auth = game.services.auth;
        if (!listenerAdded) {
            listenerAdded = true;
            // Session restore can complete after the screen is shown; rebuild
            // so the signed-in name/buttons appear without a manual refresh.
            auth.addListener(() -> { if (isVisible()) buildLayout(); });
        }
        buttons.clear();

        boolean signedIn = auth.userId() != null;
        float btnW = Math.min(240f, Math.max(140f, ctx.worldW() - 32f));
        int n = signedIn ? 1 : 2;
        float headerH = ctx.fontHeader.getCapHeight() + 24f;
        float statusH = ctx.lineH() * 3f;
        float colH = n * BTN_H + (n - 1) * BTN_GAP;
        float winW = btnW + WINDOW_PAD * 2;
        float winH = WINDOW_PAD * 2 + headerH + statusH + colH + 12f;
        window.set((ctx.worldW() - winW) * 0.5f, (ctx.worldH() - winH) * 0.5f, winW, winH);

        float btnX = window.x + WINDOW_PAD;
        float y = window.y + WINDOW_PAD;
        if (signedIn) {
            buttons.add(new Btn(TextCatalog.get("ui.account.signOut"),
                    btnX, y, btnW, BTN_H, () -> { auth.signOut(); buildLayout(); }).header());
        } else {
            buttons.add(new Btn(TextCatalog.get("ui.account.signInFacebook"),
                    btnX, y, btnW, BTN_H,
                    () -> auth.signIn(AuthService.Provider.FACEBOOK)).header());
            y += BTN_H + BTN_GAP;
            buttons.add(new Btn(TextCatalog.get("ui.account.signInGoogle"),
                    btnX, y, btnW, BTN_H,
                    () -> auth.signIn(AuthService.Provider.GOOGLE)).header());
        }

        back = new BackBtn(ctx, game::popScreen);
        burger = makeBurger();
        addStandardBurgerItems(game);
    }

    /** True while this screen is the one being rendered (guards the listener's
     *  rebuild against firing after navigation away). */
    private boolean isVisible() {
        return game.getScreen() == this;
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawInfoShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        AuthService auth = game.services.auth;
        float cx = window.cx();
        float top = window.top() - ctx.headerLineH();
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get("ui.account.title"), cx, top);
        top -= ctx.headerLineH() * 1.6f;

        boolean signedIn = auth.userId() != null;
        if (signedIn) {
            String name = auth.displayName();
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    TextCatalog.format("ui.account.signedInAs",
                            TextCatalog.vars("name", name != null ? name : "?")),
                    cx, top);
            top -= ctx.lineH();
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    TextCatalog.get("ui.account.syncOn"), cx, top);
        } else {
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    TextCatalog.get("ui.account.signedOut"), cx, top);
            top -= ctx.lineH();
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    TextCatalog.get("ui.account.syncOff"), cx, top);
        }
    }
}
