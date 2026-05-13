package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.save.SaveMetadata;
import com.bjsp123.rl2.save.SaveSystem;
import com.bjsp123.rl2.ui.v2.stage.Scrim;
import com.bjsp123.rl2.ui.v2.stage.V2Popup;
import com.bjsp123.rl2.ui.v2.stage.V2PopupActor;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 saved-games picker. {@link SaveSystem#SLOTS} cards stacked vertically;
 * each card is itself the action button — tap a filled card to continue
 * the run, tap an empty card to start a new one. A small X glyph at the
 * top-right of a filled card opens a "Delete save? Y / N" confirmation
 * modal — implemented as a {@link DeleteConfirmPopup} on the V2 stage's
 * sub-popup layer so its scrim cleanly hides the cards / titles below
 * without the screen needing per-popup suppression flags.
 */
public final class V2Saves extends V2Screen {

    private final Rl2Game game;
    private final List<SlotCard> cards = new ArrayList<>();

    private static final float CARD_W       = 360f;
    private static final float CARD_H       = 76f;
    private static final float CARD_GAP     = 22f;
    private static final float CARD_PAD     = 10f;
    private static final float X_BTN_SIZE   = 28f;

    /** Slot pending a delete confirmation; {@code -1} when no modal is up. */
    private int pendingDeleteSlot = -1;
    /** Modal window rect (only laid out when {@link #pendingDeleteSlot} ≥ 0). */
    private final Rect deleteModal = new Rect();

    /** Sub-popup rendering the modal. Wired into the V2 stage so its scrim
     *  hides the card layer behind it; its own {@link #renderSelf} also
     *  paints its Y/N buttons (chrome + labels) so they sit above the
     *  scrim. Built once on construction; its {@link V2Popup#isOpen}
     *  reads {@link #pendingDeleteSlot}. */
    private final DeleteConfirmPopup deletePopup = new DeleteConfirmPopup();
    private final V2PopupActor       deletePopupActor = new V2PopupActor(deletePopup);

    public V2Saves(Rl2Game game, UiCtx ctx) {
        super(ctx);
        this.game = game;
    }

    @Override
    protected Rect modalWindow() {
        return pendingDeleteSlot >= 0 ? deleteModal : null;
    }

    @Override
    public void show() {
        super.show();
        // Park the delete-confirm popup actor on the V2 stage's sub-popup
        // layer; hide() removes it again so a re-shown V2Saves doesn't
        // accumulate duplicates.
        ctx.v2Stage.addToSubPopup(deletePopupActor);
        // Chain the delete-popup's input processor ahead of the base
        // V2Screen input so the modal's Y/N buttons get first dibs on
        // touch events while it's open.
        Gdx.input.setInputProcessor(new InputMultiplexer(
                deletePopup.input(), baseInput()));
    }

    @Override
    public void hide() {
        super.hide();
        // Pull the popup actor back out of the shared stage so it doesn't
        // linger if this screen is disposed.
        ctx.v2Stage.remove(deletePopupActor);
    }

    @Override
    protected void buildLayout() {
        cards.clear();

        // Card width clamps to the viewport so a UiScale-shrunk world doesn't
        // overflow horizontally. Centre the card column on the viewport;
        // total height = SLOTS cards + (SLOTS-1) gaps. Top of column reserved
        // by burger; bottom by back.
        float cardW = Math.min(CARD_W, ctx.worldW() - 24f);
        int n = SaveSystem.SLOTS;
        float colH = n * CARD_H + (n - 1) * CARD_GAP;
        float top  = ctx.worldH() - 96f;              // below burger
        float colY = top - colH;
        if (colY < 88f) colY = 88f;                   // above back button

        float cardX = (ctx.worldW() - cardW) * 0.5f;
        // Build from the BOTTOM card up (lower index → higher screen y so the
        // first slot reads as topmost on screen).
        for (int i = 0; i < n; i++) {
            float cy = colY + (n - 1 - i) * (CARD_H + CARD_GAP);
            cards.add(buildCard(i, cardX, cy, cardW));
        }

        if (pendingDeleteSlot >= 0) {
            deletePopup.layoutFor(pendingDeleteSlot);
        }

        back   = new BackBtn(ctx, game::popScreen);
        burger = makeBurger();
        addStandardBurgerItems(game);
    }

    private SlotCard buildCard(int slot, float x, float y, float cardW) {
        SlotCard c = new SlotCard();
        c.rect.set(x, y, cardW, CARD_H);
        c.metadata = game.saveSystem.metadata(slot);
        c.filled   = game.saveSystem.exists(slot);

        // Card / X buttons stay live even with the modal open — the popup's
        // own input processor sits at the head of the multiplexer and
        // consumes every touch while it's up, so these buttons just don't
        // get hit.
        if (c.filled) {
            // Cancel-icon delete button at top-right of card. Added FIRST
            // so it wins the hit test against the card-wide button below.
            float xSz = X_BTN_SIZE;
            float xX  = x + cardW - CARD_PAD - xSz;
            float xY  = y + CARD_H - CARD_PAD - xSz;
            Btn xBtn = new Btn("", xX, xY, xSz, xSz,
                    () -> { pendingDeleteSlot = slot; show(); });
            xBtn.icon = com.bjsp123.rl2.world.render.IconSprites
                    .regionFor(com.bjsp123.rl2.world.render.IconSprites.Icon.CANCEL);
            buttons.add(xBtn);
            // Whole card is the Continue affordance — no separate button,
            // no label (drawBodyText paints the save metadata over it).
            buttons.add(new Btn("", x, y, cardW, CARD_H, () -> resume(slot)));
        } else {
            // Empty slot — whole card is the New Game affordance.
            buttons.add(new Btn("", x, y, cardW, CARD_H, () -> newGame(slot)));
        }
        return c;
    }

    private void resume(int slot) {
        // Resume → root the play screen so back from any in-game sub-screen
        // (settings, map, level info) returns to play, and the menu chain
        // that brought us here can't be popped back into.
        if (game.currentPlay != null && game.currentPlay.saveSlot == slot) {
            game.setRootScreen(game.currentPlay);
            return;
        }
        if (game.currentPlay != null) {
            game.currentPlay.dispose();
            game.currentPlay = null;
        }
        com.bjsp123.rl2.model.World loaded = game.saveSystem.load(slot);
        if (loaded == null) return;
        game.setRootScreen(
                new com.bjsp123.rl2.screen.PlayScreen(game, slot, loaded));
    }

    private void newGame(int slot) {
        if (game.currentPlay != null && game.currentPlay.saveSlot == slot) {
            game.currentPlay.dispose();
            game.currentPlay = null;
        }
        game.saveSystem.clear(slot);
        game.pushScreen(new V2CharacterSelect(game, slot));
    }

    private void confirmDelete() {
        int slot = pendingDeleteSlot;
        pendingDeleteSlot = -1;
        if (slot < 0) { show(); return; }
        if (game.currentPlay != null && game.currentPlay.saveSlot == slot) {
            game.currentPlay.dispose();
            game.currentPlay = null;
        }
        game.saveSystem.clear(slot);
        show();   // rebuild the card column
    }

    private void cancelDelete() {
        pendingDeleteSlot = -1;
        show();
    }

    @Override
    protected void onEscape() {
        if (pendingDeleteSlot >= 0) {
            cancelDelete();
            return;
        }
        super.onEscape();
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        for (SlotCard c : cards) {
            // Slot card chrome — same window primitive, smaller corner accent
            // so cards don't compete visually with the screen-level windows
            // we'll draw on other screens.
            Window.drawShape(ctx, c.rect.x, c.rect.y, c.rect.w, c.rect.h);
        }
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, "Saved Games",
                ctx.worldW() * 0.5f, ctx.worldH() - 24f);
        for (SlotCard c : cards) {
            float textTop  = c.rect.top() - CARD_PAD - 4f;
            float textLeft = c.rect.x + CARD_PAD;
            if (c.filled) {
                String line1 = c.metadata.charClass + "   Lvl " + c.metadata.characterLevel;
                String line2 = "Depth " + c.metadata.depth;
                TextDraw.left(ctx, ctx.fontHeader, UIVars.TEXT_BODY,
                        line1, textLeft, textTop);
                TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        line2, textLeft,
                        textTop - ctx.fontHeader.getCapHeight() - 6f);
            } else {
                TextDraw.centre(ctx, ctx.fontHeader, UIVars.TEXT_BODY,
                        "New Game",
                        c.rect.cx(),
                        c.rect.cy() + ctx.fontHeader.getCapHeight() * 0.5f);
            }
        }
    }

    /** Per-slot card state — read by drawBodyShape (rect) and drawBodyText
     *  (metadata + filled). Buttons live in {@link #buttons}. */
    private static final class SlotCard {
        boolean filled;
        SaveMetadata metadata;
        final Rect rect = new Rect();
    }

    /** "Delete save? Y / N" modal. Lives on the V2 stage's sub-popup
     *  layer; its {@link #renderSelf} paints a {@link Scrim} first, then
     *  the modal chrome, then its own Y/N buttons (chrome + labels) on
     *  top — so the popup is fully self-contained and there's no need to
     *  suppress underlying screen text from V2Saves's draw methods.
     *
     *  <p>Input is owned by this popup too — its processor sits at the
     *  head of V2Saves's input multiplexer and hit-tests Y/N first, then
     *  consumes any other tap as a tap-outside-to-cancel. */
    private final class DeleteConfirmPopup implements V2Popup {

        private final Rect yesBtn = new Rect();
        private final Rect noBtn  = new Rect();
        private boolean yesPressed, noPressed;

        @Override
        public boolean isOpen() { return pendingDeleteSlot >= 0; }

        /** Position the modal + its Y/N buttons. Called from
         *  {@link V2Saves#buildLayout} when the modal opens. */
        void layoutFor(int slot) {
            float vw = ctx.worldW();
            float vh = ctx.worldH();
            float mw = Math.min(280f, vw - 32f);
            float mh = 140f;
            float mx = (vw - mw) * 0.5f;
            float my = (vh - mh) * 0.5f;
            deleteModal.set(mx, my, mw, mh);

            // Y / N buttons across the bottom of the modal.
            float btnH   = 44f;
            float btnGap = 12f;
            float btnW   = (mw - 2 * CARD_PAD - btnGap) * 0.5f;
            float btnY   = my + CARD_PAD;
            yesBtn.set(mx + CARD_PAD, btnY, btnW, btnH);
            noBtn .set(mx + CARD_PAD + btnW + btnGap, btnY, btnW, btnH);
        }

        @Override
        public void renderSelf() {
            // Re-layout each frame so window resize / UiScale changes
            // flow through without needing a separate rebuild trigger.
            layoutFor(pendingDeleteSlot);

            // Modal dim layer covering the cards behind. The popup
            // composites entirely on top of V2Screen.render, so the
            // scrim alone is enough to read the cards as backgrounded —
            // no need for a separate solid-black backdrop.
            Scrim.draw(ctx);

            // Chrome pass — modal window + Y/N button frames.
            ctx.applyProjection();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            ShapeRenderer s = ctx.shapes;
            s.begin(ShapeRenderer.ShapeType.Filled);
            // Solid black backdrop UNDER the translucent window fill so
            // the modal interior reads as opaque rather than letting the
            // dimmed-but-visible cards bleed through.
            s.setColor(0f, 0f, 0f, 1f);
            s.rect(deleteModal.x, deleteModal.y, deleteModal.w, deleteModal.h);
            Window.drawShape(ctx,
                    deleteModal.x, deleteModal.y, deleteModal.w, deleteModal.h);
            drawBtnChrome(s, yesBtn, yesPressed);
            drawBtnChrome(s, noBtn,  noPressed);
            s.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);

            // Text pass — title + Y/N labels.
            ctx.batch.begin();
            TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                    "Delete save?",
                    deleteModal.cx(),
                    deleteModal.top() - 28f);
            TextDraw.centre(ctx, ctx.fontHeader,
                    yesPressed ? UIVars.ACCENT : UIVars.TEXT_BODY,
                    "Yes", yesBtn.cx(), yesBtn.cy() + 8f);
            TextDraw.centre(ctx, ctx.fontHeader,
                    noPressed ? UIVars.ACCENT : UIVars.TEXT_BODY,
                    "No",  noBtn.cx(),  noBtn.cy() + 8f);
            ctx.batch.end();
        }

        private void drawBtnChrome(ShapeRenderer s, Rect r, boolean pressed) {
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
            s.setColor(pressed ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
            s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                    r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
        }

        /** InputProcessor that consumes ALL events while the popup is up.
         *  Hit-tests Y/N first; any other touch outside the modal cancels;
         *  any touch inside the modal but outside Y/N is consumed as a
         *  no-op so it doesn't fall through to the cards behind. */
        InputProcessor input() {
            return new InputAdapter() {
                @Override
                public boolean touchDown(int sx, int sy, int pointer, int button) {
                    if (!isOpen()) return false;
                    float vx = ctx.unprojectX(sx, sy);
                    float vy = ctx.unprojectY(sx, sy);
                    if (yesBtn.contains(vx, vy)) { yesPressed = true; return true; }
                    if (noBtn .contains(vx, vy)) { noPressed  = true; return true; }
                    return true;   // consume anything else inside or outside
                }

                @Override
                public boolean touchUp(int sx, int sy, int pointer, int button) {
                    if (!isOpen()) return false;
                    float vx = ctx.unprojectX(sx, sy);
                    float vy = ctx.unprojectY(sx, sy);
                    if (yesPressed) {
                        yesPressed = false;
                        if (yesBtn.contains(vx, vy)) confirmDelete();
                        return true;
                    }
                    if (noPressed) {
                        noPressed = false;
                        if (noBtn.contains(vx, vy)) cancelDelete();
                        return true;
                    }
                    // Tap outside the modal cancels (matches the V2 popup
                    // tap-outside-to-close convention).
                    if (!deleteModal.contains(vx, vy)) {
                        cancelDelete();
                        return true;
                    }
                    return true;
                }

                @Override
                public boolean keyDown(int keycode) {
                    if (!isOpen()) return false;
                    if (keycode == Input.Keys.ESCAPE
                            || keycode == Input.Keys.BACK) {
                        cancelDelete();
                        return true;
                    }
                    return false;
                }
            };
        }
    }
}
