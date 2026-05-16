package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.ui.skin.Settings;
import com.bjsp123.rl2.ui.v2.stage.V2PopupActor;
import com.bjsp123.rl2.world.render.IconSprites;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 settings screen - tab strip across the top of a window, content panel
 * below. Each tab shows a vertical stack of "labelled rows", each row a
 * setting name plus a horizontal mini-button choice grid.
 *
 * <p>The tab strip itself is a row of {@link Btn}s with sticky-checked state.
 * Switching tabs rebuilds the body buttons (settings are tab-specific and
 * each tab's chooser button rects depend on the tab content's height).
 */
public final class V2Settings extends V2Screen {

    private enum Tab { GAMEPLAY, GRAPHICS, LOG }
    private static Tab currentTab = Tab.GRAPHICS;

    private final Rl2Game game;
    private final Rect window = new Rect();
    private final ConfirmPopup clearHallPopup;
    private final V2PopupActor clearHallPopupActor;

    private static final float WIN_PAD     = 12f;
    /** Horizontal gap between tabs in the tab strip. */
    private static final float TAB_GAP     = 4f;
    private static final float TAB_H       = 44f;
    private static final float ROW_GAP     = 12f;
    private static final float ROW_LABEL_H = 18f;
    private static final float CHOOSER_H   = 40f;
    private static final float CHOOSER_GAP = 4f;
    /** Side margin from the viewport edge to the window. Window stretches to
     *  fill the rest of the available virtual width so tab strips and chooser
     *  rows sit within the panel even on the narrowest viewport. */
    private static final float SCREEN_MARGIN = 12f;

    /** Per-row content area, computed in {@link #buildLayout()} so
     *  {@link #drawBodyText} can paint each row's section label without
     *  re-computing the layout. */
    private final List<RowLabel> rowLabels = new ArrayList<>();

    /** Tab buttons, kept separately from {@link #buttons} so we can
     *  highlight the active tab without iterating. */
    private final List<Btn> tabBtns = new ArrayList<>();

    public V2Settings(Rl2Game game, UiCtx ctx) {
        super(ctx);
        this.game = game;
        this.clearHallPopup = new ConfirmPopup(ctx);
        this.clearHallPopupActor = new V2PopupActor(clearHallPopup);
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override
    protected void buildLayout() {
        rowLabels.clear();
        tabBtns.clear();

        // Outer window - caps at the design width so the panel doesn't
        // stretch into a wide letterbox on widescreen displays. Centred
        // horizontally on the viewport so the surrounding world breathes
        // evenly on either side. Top reserved for the screen header;
        // bottom reserved for the back/burger overlay row.
        PanelLayout.centered(window, ctx,
                UIVars.VIRTUAL_W - 2 * SCREEN_MARGIN,
                UIVars.VIRTUAL_H - 110f,
                SCREEN_MARGIN, 55f);
        float winW = window.w;
        float winX = window.x;
        float winY = window.y;
        float winH = window.h;

        // Tab strip - anchored at the TOP of the window's interior. Tab
        // widths are computed so all tabs FIT within the inner width - no
        // tab strip ever overflows the panel. Total tab strip width =
        // n*tabW + (n-1)*TAB_GAP = innerW; solve for tabW.
        float tabsY = winY + winH - WIN_PAD - TAB_H;
        float tabsX = winX + WIN_PAD;
        Tab[] tabs = { Tab.GAMEPLAY, Tab.GRAPHICS, Tab.LOG };
        float innerW = winW - 2 * WIN_PAD;
        float tabW   = (innerW - (tabs.length - 1) * TAB_GAP) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            final Tab t = tabs[i];
            Btn b = new Btn(label(t),
                    tabsX + i * (tabW + TAB_GAP), tabsY, tabW, TAB_H,
                    () -> { currentTab = t; show(); });
            b.checked = (t == currentTab);
            // Icon sourced from the shared UI sheet - text label fallback
            // kicks in if the sheet failed to load.
            b.icon = IconSprites.regionFor(iconFor(t));
            tabBtns.add(b);
            buttons.add(b);
        }

        // Tab content - vertical stack of "labelled rows" beneath the tab
        // strip. Each row is a section label (drawn by drawBodyText) plus a
        // horizontal chooser of mini-buttons (drawn by the parent button
        // pass). We lay out from the top of the content area downward; since
        // libGDX is y-up, "top" is high y and we decrement.
        float contentTop = tabsY - ROW_GAP;
        float yCursor    = contentTop;
        switch (currentTab) {
            case GRAPHICS -> {
                yCursor = addUiScaleRow(winX + WIN_PAD, yCursor);
                yCursor = addUiFontScaleRow(winX + WIN_PAD, yCursor);
                yCursor = addOutlineWidthRow(winX + WIN_PAD, yCursor);
                yCursor = addOutlineDarknessRow(winX + WIN_PAD, yCursor);
                yCursor = addOutlineSmoothRow(winX + WIN_PAD, yCursor);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, TextCatalog.get("ui.settings.lowResRender"),
                        Settings::lowResRender, Settings::setLowResRender);
            }
            case GAMEPLAY -> {
                yCursor = addAnimationSpeedRow(winX + WIN_PAD, yCursor);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, TextCatalog.get("ui.settings.instantActions"),
                        Settings::instantActions, Settings::setInstantActions);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, TextCatalog.get("ui.settings.queueAcceleration"),
                        Settings::queueAccelEnabled, Settings::setQueueAccelEnabled);
                yCursor = addQuickslotCountRow(winX + WIN_PAD, yCursor);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, TextCatalog.get("ui.settings.perfOverlay"),
                        Settings::showPerfOverlay, Settings::setShowPerfOverlay);
                yCursor = addButtonRow(winX + WIN_PAD, yCursor, TextCatalog.get("ui.settings.clearHall"));
            }
            case LOG -> {
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, TextCatalog.get("ui.settings.showEventLog"),
                        Settings::logOn, Settings::setLogOn);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, TextCatalog.get("ui.settings.lowPriorityLines"),
                        Settings::showLowPriority, Settings::setShowLowPriority);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, TextCatalog.get("ui.settings.mobVsMobLines"),
                        Settings::showNonPlayer, Settings::setShowNonPlayer);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, TextCatalog.get("ui.settings.expandedLog"),
                        Settings::logExpanded, Settings::setLogExpanded);
                yCursor = addLogFontScaleRow(winX + WIN_PAD, yCursor);
            }
        }

        // Back button at bottom-right - pops the navigation stack so the
        // user returns to wherever Settings was opened from.
        back = new BackBtn(ctx, game::popScreen);
        // Burger top-right.
        burger = makeBurger();
        addStandardBurgerItems(game);
    }

    @Override
    protected void onEscape() { game.popScreen(); }

    /** Add one labelled row of choice buttons. Returns the new y-cursor
     *  (one row below the current one). */
    private float addRow(String labelText, float x, float yTop, List<ChoiceSpec> choices) {
        // Section label text - drawn by drawBodyText. yTop is the top of the
        // label (libGDX baseline conventions handled in TextDraw).
        rowLabels.add(new RowLabel(labelText, x, yTop));
        // Chooser buttons sit just below the label.
        float by = yTop - ROW_LABEL_H - CHOOSER_H;
        float bx = x;
        for (ChoiceSpec c : choices) {
            Btn b = new Btn(c.label, bx, by, c.width, CHOOSER_H, c.onClick);
            b.checked = c.checked;
            buttons.add(b);
            bx += c.width + CHOOSER_GAP;
        }
        return by - ROW_GAP;
    }

    private float addButtonRow(float x, float yTop, String label) {
        float winW = Math.min(UIVars.VIRTUAL_W - 2 * SCREEN_MARGIN,
                ctx.worldW() - 2 * SCREEN_MARGIN);
        float innerW = winW - 2 * WIN_PAD;
        Btn b = new Btn(label, x, yTop - CHOOSER_H, innerW, CHOOSER_H,
                this::openClearHallPopup);
        b.warn = true;
        buttons.add(b);
        return yTop - CHOOSER_H - ROW_GAP;
    }

    private float addUiScaleRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float s : Settings.UI_SCALE_CHOICES) {
            final float chosen = s;
            choices.add(new ChoiceSpec(formatNumber(s) + "x", 50f,
                    Math.abs(Settings.uiScale() - s) < 0.001f,
                    () -> {
                        Settings.setUiScale(chosen);
                        ctx.applyUiScale();
                        show();
                    }));
        }
        return addRow(TextCatalog.get("ui.settings.uiScale"), x, yTop, choices);
    }

    private float addUiFontScaleRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float s : Settings.UI_FONT_SCALE_CHOICES) {
            final float chosen = s;
            choices.add(new ChoiceSpec(formatNumber(s) + "x", 56f,
                    Math.abs(Settings.uiFontScale() - s) < 0.001f,
                    () -> {
                        Settings.setUiFontScale(chosen);
                        ctx.applyFontScale();
                        show();
                    }));
        }
        return addRow(TextCatalog.get("ui.settings.uiFontSize"), x, yTop, choices);
    }

    private float addOutlineWidthRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float w : Settings.MOB_OUTLINE_WIDTH_CHOICES) {
            final float chosen = w;
            choices.add(new ChoiceSpec(formatNumber(w), 50f,
                    Math.abs(Settings.mobOutlineWidth() - w) < 0.0001f,
                    () -> { Settings.setMobOutlineWidth(chosen); show(); }));
        }
        return addRow(TextCatalog.get("ui.settings.outlineWidth"), x, yTop, choices);
    }

    private float addOutlineDarknessRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float a : Settings.MOB_OUTLINE_DARKNESS_CHOICES) {
            final float chosen = a;
            choices.add(new ChoiceSpec(formatNumber(a), 50f,
                    Math.abs(Settings.mobOutlineDarkness() - a) < 0.0001f,
                    () -> { Settings.setMobOutlineDarkness(chosen); show(); }));
        }
        return addRow(TextCatalog.get("ui.settings.outlineDarkness"), x, yTop, choices);
    }

    private float addOutlineSmoothRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        choices.add(new ChoiceSpec(TextCatalog.get("ui.settings.smooth"), 88f, Settings.mobOutlineSmooth(),
                () -> { Settings.setMobOutlineSmooth(true);  show(); }));
        choices.add(new ChoiceSpec(TextCatalog.get("ui.settings.pixel"),  88f, !Settings.mobOutlineSmooth(),
                () -> { Settings.setMobOutlineSmooth(false); show(); }));
        return addRow(TextCatalog.get("ui.settings.outlineSmoothing"), x, yTop, choices);
    }

    private float addAnimationSpeedRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float n : Settings.ANIMATION_SPEED_CHOICES) {
            final float chosen = n;
            choices.add(new ChoiceSpec(n + "x", 56f,
                    Settings.framesPerRender() == n,
                    () -> { Settings.setFramesPerRender(chosen); show(); }));
        }
        return addRow(TextCatalog.get("ui.settings.animationSpeed"), x, yTop, choices);
    }

    private float addQuickslotCountRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (int n : Settings.QUICKSLOT_COUNT_CHOICES) {
            final int chosen = n;
            choices.add(new ChoiceSpec(String.valueOf(n), 56f,
                    Settings.quickslotCount() == n,
                    () -> { Settings.setQuickslotCount(chosen); show(); }));
        }
        return addRow(TextCatalog.get("ui.settings.quickslots"), x, yTop, choices);
    }

    private float addLogFontScaleRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float f : Settings.LOG_FONT_SCALE_CHOICES) {
            final float chosen = f;
            choices.add(new ChoiceSpec(formatNumber(f) + "x", 50f,
                    Math.abs(Settings.logFontScale() - f) < 0.001f,
                    () -> { Settings.setLogFontScale(chosen); show(); }));
        }
        return addRow(TextCatalog.get("ui.settings.logFontSize"), x, yTop, choices);
    }

    private float addBoolRow(float x, float yTop, String label,
                             java.util.function.BooleanSupplier getter,
                             java.util.function.Consumer<Boolean> setter) {
        List<ChoiceSpec> choices = new ArrayList<>();
        choices.add(new ChoiceSpec(TextCatalog.get("ui.settings.on"), 70f, getter.getAsBoolean(),
                () -> { setter.accept(true);  show(); }));
        choices.add(new ChoiceSpec(TextCatalog.get("ui.settings.off"), 70f, !getter.getAsBoolean(),
                () -> { setter.accept(false); show(); }));
        return addRow(label, x, yTop, choices);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        // Window title bar - a header label drawn ABOVE the window for now,
        // since our window content is fully consumed by tabs + choosers.
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, TextCatalog.get("ui.settings.title"),
                ctx.worldW() * 0.5f, ctx.worldH() - 24f);

        for (RowLabel rl : rowLabels) {
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, rl.text, rl.x, rl.y);
        }
    }

    private void openClearHallPopup() {
        clearHallPopup.configure(
                TextCatalog.get("ui.settings.clearHallTitle"),
                TextCatalog.get("ui.settings.clearHallBody"),
                TextCatalog.get("ui.common.clear"),
                TextCatalog.get("ui.common.cancel"),
                () -> {
                    game.hallOfFame.entries.clear();
                    com.bjsp123.rl2.save.HallOfFameStore.save(
                            game.persistence, game.hallOfFame);
                    show();
                });
        clearHallPopup.open();
    }

    @Override
    public void show() {
        super.show();
        ctx.v2Stage.remove(clearHallPopupActor);
        ctx.v2Stage.addToSubPopup(clearHallPopupActor);
        Gdx.input.setInputProcessor(new InputMultiplexer(
                clearHallPopup.input(), baseInput()));
    }

    @Override
    public void hide() {
        ctx.v2Stage.remove(clearHallPopupActor);
        super.hide();
    }

    private static String label(Tab t) {
        return switch (t) {
            case GAMEPLAY -> TextCatalog.get("ui.settings.tab.play");
            case GRAPHICS -> TextCatalog.get("ui.settings.tab.graphics");
            case LOG      -> TextCatalog.get("ui.settings.tab.log");
        };
    }

    /** Map each tab to a glyph in the shared UI icon sheet. */
    private static IconSprites.Icon iconFor(Tab t) {
        return switch (t) {
            case GAMEPLAY -> IconSprites.Icon.GAME;
            case GRAPHICS -> IconSprites.Icon.VIDEO;
            case LOG      -> IconSprites.Icon.BOOK;
        };
    }

    /** Drop trailing zeros on a float so 1.0 reads as "1" and 0.55 stays as "0.55". */
    private static String formatNumber(float v) {
        if (v == (int) v) return Integer.toString((int) v);
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Captured chooser button spec: label / width / checked state / onClick. */
    private record ChoiceSpec(String label, float width, boolean checked, Runnable onClick) {}

    /** Section-label position (drawn between row choosers). */
    private record RowLabel(String text, float x, float y) {}
}
