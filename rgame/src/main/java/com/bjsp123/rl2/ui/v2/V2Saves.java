package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.save.SaveMetadata;
import com.bjsp123.rl2.save.SaveSystem;
import com.bjsp123.rl2.ui.v2.stage.V2PopupActor;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 saved-games picker. {@link SaveSystem#SLOTS} cards stacked vertically;
 * each card is itself the action button - tap a filled card to continue
 * the run, tap an empty card to start a new one. A small X glyph at the
 * top-right of a filled card opens a shared confirmation modal on the V2 stage's
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
    private final ConfirmPopup deletePopup;
    private final V2PopupActor deletePopupActor;

    public V2Saves(Rl2Game game, UiCtx ctx) {
        super(ctx);
        this.game = game;
        this.deletePopup = new ConfirmPopup(ctx);
        this.deletePopupActor = new V2PopupActor(deletePopup);
    }

    @Override
    protected Rect modalWindow() {
        return deletePopup.isOpen() ? deletePopup.window() : null;
    }

    @Override
    public void show() {
        super.show();
        // Park the delete-confirm popup actor on the V2 stage's sub-popup
        // layer; hide() removes it again so a re-shown V2Saves doesn't
        // accumulate duplicates.
        ctx.v2Stage.remove(deletePopupActor);
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
        // Build from the BOTTOM card up (lower index -> higher screen y so the
        // first slot reads as topmost on screen).
        for (int i = 0; i < n; i++) {
            float cy = colY + (n - 1 - i) * (CARD_H + CARD_GAP);
            cards.add(buildCard(i, cardX, cy, cardW));
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

        // Card / X buttons stay live even with the modal open - the popup's
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
                    () -> openDeleteConfirm(slot));
            xBtn.icon = com.bjsp123.rl2.world.render.IconSprites
                    .regionFor(com.bjsp123.rl2.world.render.IconSprites.Icon.CANCEL);
            buttons.add(xBtn);
            // Whole card is the Continue affordance - no separate button,
            // no label (drawBodyText paints the save metadata over it).
            buttons.add(new Btn("", x, y, cardW, CARD_H, () -> resume(slot)));
        } else {
            // Empty slot - whole card is the New Game affordance.
            buttons.add(new Btn("", x, y, cardW, CARD_H, () -> newGame(slot)));
        }
        return c;
    }

    private void resume(int slot) {
        // Resume -> root the play screen so back from any in-game sub-screen
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
    }

    private void openDeleteConfirm(int slot) {
        pendingDeleteSlot = slot;
        deletePopup.configure(
                "Delete save?",
                "This removes the saved game in slot " + (slot + 1) + ".",
                "Delete",
                "Cancel",
                this::confirmDelete,
                this::cancelDelete);
        deletePopup.open();
    }

    @Override
    protected void onEscape() {
        if (deletePopup.isOpen()) {
            deletePopup.cancel();
            return;
        }
        super.onEscape();
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        for (SlotCard c : cards) {
            // Slot card chrome - same window primitive, smaller corner accent
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
                TextDraw.leftFit(ctx, ctx.fontHeader, UIVars.TEXT_BODY,
                        line1, textLeft, textTop, c.rect.right() - textLeft - CARD_PAD);
                TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        line2, textLeft,
                        textTop - ctx.fontHeader.getCapHeight() - 6f,
                        c.rect.right() - textLeft - CARD_PAD);
            } else {
                TextDraw.centre(ctx, ctx.fontHeader, UIVars.TEXT_BODY,
                        "New Game",
                        c.rect.cx(),
                        c.rect.cy() + ctx.fontHeader.getCapHeight() * 0.5f);
            }
        }
    }

    /** Per-slot card state - read by drawBodyShape (rect) and drawBodyText
     *  (metadata + filled). Buttons live in {@link #buttons}. */
    private static final class SlotCard {
        boolean filled;
        SaveMetadata metadata;
        final Rect rect = new Rect();
    }

}
