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
 * below. Each tab shows a vertical stack of rows: snap-to-tick {@link Slider}
 * rows for scalar settings, {@link Toggle} pill rows for booleans, and
 * full-width action buttons (e.g. clear hall of fame) at the bottom.
 *
 * <p>The tab strip is the shared {@link TabStrip} widget. Switching tabs
 * rebuilds the body rows (settings are tab-specific and each tab's row
 * rects depend on the tab content's height).
 */
public final class V2Settings extends V2Screen {

    private enum Tab { GAMEPLAY, GRAPHICS, LOG, AUDIO }
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
    /** Side margin from the viewport edge to the window. Window stretches to
     *  fill the rest of the available virtual width so tab strips and chooser
     *  rows sit within the panel even on the narrowest viewport. */
    private static final float SCREEN_MARGIN = 12f;

    /** Per-row content area, computed in {@link #buildLayout()} so
     *  {@link #drawBodyText} can paint each row's section label without
     *  re-computing the layout. */
    private final List<RowLabel> rowLabels = new ArrayList<>();

    /** Shared tab-strip widget - layout, chrome, and press state. */
    private final TabStrip tabs = new TabStrip(Tab.values().length);

    /** Toggle widgets for boolean settings. Iterated alongside {@link #buttons}
     *  in the input handlers so a tap on a pill flips the value. */
    private final List<Toggle> toggles = new ArrayList<>();

    /** Slider widgets for discrete-choice scalar settings. */
    private final List<Slider> sliders = new ArrayList<>();

    /** Slider currently capturing pointer drag, or {@code null}. Set on
     *  touchDown-in-track, cleared on touchUp. */
    private Slider activeSlider;

    /** Scrollable band for the tab body. Tab content can exceed the window on
     *  short / heavily-scaled viewports (the window clamps to the viewport
     *  height), which used to silently hide the bottom rows - now they scroll.
     *  The scroller persists across {@link #buildLayout()} rebuilds; rows are
     *  laid out shifted by its offset and clipped to the band. */
    private final ScrollBand band = new ScrollBand();
    /** Total height of the current tab's rows, for max-scroll + scrollbar. */
    private float tabContentH;

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
        toggles.clear();
        sliders.clear();
        activeSlider = null;

        // Outer window - caps at the design width so the panel doesn't
        // stretch into a wide letterbox on widescreen displays. Centred
        // horizontally on the viewport so the surrounding world breathes
        // evenly on either side. Top reserved for the screen header;
        // bottom reserved for the back/burger overlay row.
        PanelLayout.centered(window, ctx,
                UIVars.VIRTUAL_W - 2 * SCREEN_MARGIN,
                UIVars.VIRTUAL_H - 110f,
                SCREEN_MARGIN, 55f);
        float winX = window.x;
        float winY = window.y;
        float winH = window.h;

        // Tab strip - anchored at the TOP of the window's interior. The
        // shared TabStrip computes tab widths so all tabs FIT within the
        // inner width - no tab strip ever overflows the panel.
        float tabsY = winY + winH - WIN_PAD - TAB_H;
        tabs.layout(window, WIN_PAD, tabsY, TAB_H, TAB_GAP);
        tabs.setActive(currentTab.ordinal());

        // Tab content - vertical stack of "labelled rows" beneath the tab
        // strip. Each row is a section label (drawn by drawBodyText) plus a
        // horizontal chooser of mini-buttons (drawn by the parent button
        // pass). We lay out from the top of the content area downward; since
        // libGDX is y-up, "top" is high y and we decrement.
        band.set(winX + WIN_PAD, winY + WIN_PAD,
                window.w - 2 * WIN_PAD, (tabsY - ROW_GAP) - (winY + WIN_PAD));
        float contentTop = band.top() + band.scroller.scrollY();
        float yCursor    = contentTop;
        float rowX = winX + WIN_PAD;
        switch (currentTab) {
            case GRAPHICS -> {
                // Fast graphics leads the tab - it's the one most users come
                // here for on the web build, so it must be visible pre-scroll.
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.fastGraphics"),
                        Settings::fastGraphics, Settings::setFastGraphics);
                yCursor = addUiScaleRow(rowX, yCursor);
                yCursor = addOutlineWidthRow(rowX, yCursor);
                yCursor = addOutlineDarknessRow(rowX, yCursor);
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.outlineSmoothing"),
                        Settings::mobOutlineSmooth, Settings::setMobOutlineSmooth);
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.lowResRender"),
                        Settings::lowResRender, Settings::setLowResRender);
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.perfOverlay"),
                        Settings::showPerfOverlay, Settings::setShowPerfOverlay);
            }
            case GAMEPLAY -> {
                yCursor = addAnimationSpeedRow(rowX, yCursor);
                yCursor = addQuickslotCountRow(rowX, yCursor);
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.instantActions"),
                        Settings::instantActions, Settings::setInstantActions);
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.queueAcceleration"),
                        Settings::queueAccelEnabled, Settings::setQueueAccelEnabled);
                // Destructive actions live at the bottom of the tab so the
                // settings flow always reads "set values, then take any
                // one-shot action".
                yCursor = addButtonRow(rowX, yCursor, TextCatalog.get("ui.settings.eraseTips"),
                        com.bjsp123.rl2.ui.v2.TipSystem::reset, /*warn=*/false);
                yCursor = addButtonRow(rowX, yCursor, TextCatalog.get("ui.settings.clearHall"));
            }
            case LOG -> {
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.showEventLog"),
                        Settings::logOn, Settings::setLogOn);
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.lowPriorityLines"),
                        Settings::showLowPriority, Settings::setShowLowPriority);
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.mobVsMobLines"),
                        Settings::showNonPlayer, Settings::setShowNonPlayer);
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.expandedLog"),
                        Settings::logExpanded, Settings::setLogExpanded);
                yCursor = addLogFontScaleRow(rowX, yCursor);
            }
            case AUDIO -> {
                yCursor = addSfxVolumeRow(rowX, yCursor);
                yCursor = addMusicVolumeRow(rowX, yCursor);
            }
        }

        // Rows consumed (contentTop - yCursor) includes the trailing ROW_GAP;
        // close enough for max-scroll + scrollbar proportions.
        tabContentH = contentTop - yCursor;
        band.update(tabContentH);

        // Back button at bottom-right - pops the navigation stack so the
        // user returns to wherever Settings was opened from.
        back = new BackBtn(ctx, game::popScreen);
        // Burger top-right.
        burger = makeBurger();
        addStandardBurgerItems(game);
    }

    @Override
    protected void onEscape() { game.popScreen(); }

    private float addButtonRow(float x, float yTop, String label) {
        return addButtonRow(x, yTop, label, this::openClearHallPopup, /*warn=*/true);
    }

    private float addButtonRow(float x, float yTop, String label, Runnable onClick,
                               boolean warn) {
        float winW = Math.min(UIVars.VIRTUAL_W - 2 * SCREEN_MARGIN,
                ctx.worldW() - 2 * SCREEN_MARGIN);
        float innerW = winW - 2 * WIN_PAD;
        Btn b = new Btn(label, x, yTop - CHOOSER_H, innerW, CHOOSER_H, onClick);
        b.warn = warn;
        buttons.add(b);
        return yTop - CHOOSER_H - ROW_GAP;
    }

    private float addUiScaleRow(float x, float yTop) {
        return addSliderRow(x, yTop, TextCatalog.get("ui.settings.uiScale"),
                Settings.UI_SCALE_CHOICES, Settings.uiScale(),
                v -> { Settings.setUiScale(v); ctx.applyUiScale(); },
                v -> formatNumber(v) + "x");
    }

    private float addOutlineWidthRow(float x, float yTop) {
        return addSliderRow(x, yTop, TextCatalog.get("ui.settings.outlineWidth"),
                Settings.MOB_OUTLINE_WIDTH_CHOICES, Settings.mobOutlineWidth(),
                Settings::setMobOutlineWidth,
                V2Settings::formatNumber);
    }

    private float addOutlineDarknessRow(float x, float yTop) {
        return addSliderRow(x, yTop, TextCatalog.get("ui.settings.outlineDarkness"),
                Settings.MOB_OUTLINE_DARKNESS_CHOICES, Settings.mobOutlineDarkness(),
                Settings::setMobOutlineDarkness,
                V2Settings::formatNumber);
    }

    private float addAnimationSpeedRow(float x, float yTop) {
        return addSliderRow(x, yTop, TextCatalog.get("ui.settings.animationSpeed"),
                Settings.ANIMATION_SPEED_CHOICES, Settings.framesPerRender(),
                Settings::setFramesPerRender,
                v -> formatNumber(v) + "x");
    }

    private float addQuickslotCountRow(float x, float yTop) {
        // Int choices threaded through the float slider - values are whole
        // numbers so the cast is exact.
        float[] ticks = new float[Settings.QUICKSLOT_COUNT_CHOICES.length];
        for (int i = 0; i < ticks.length; i++) ticks[i] = Settings.QUICKSLOT_COUNT_CHOICES[i];
        return addSliderRow(x, yTop, TextCatalog.get("ui.settings.quickslots"),
                ticks, Settings.quickslotCount(),
                v -> Settings.setQuickslotCount(Math.round(v)),
                v -> Integer.toString(Math.round(v)));
    }

    private float addSfxVolumeRow(float x, float yTop) {
        return addSliderRow(x, yTop, TextCatalog.get("ui.settings.sfxVolume"),
                Settings.VOLUME_CHOICES, Settings.sfxVolume(),
                Settings::setSfxVolume,
                v -> formatNumber(v * 100f) + "%");
    }

    private float addMusicVolumeRow(float x, float yTop) {
        return addSliderRow(x, yTop, TextCatalog.get("ui.settings.musicVolume"),
                Settings.VOLUME_CHOICES, Settings.musicVolume(),
                v -> {
                    Settings.setMusicVolume(v);
                    if (game.music != null) game.music.applyVolume();
                },
                v -> formatNumber(v * 100f) + "%");
    }

    private float addLogFontScaleRow(float x, float yTop) {
        return addSliderRow(x, yTop, TextCatalog.get("ui.settings.logFontSize"),
                Settings.LOG_FONT_SCALE_CHOICES, Settings.logFontScale(),
                Settings::setLogFontScale,
                v -> formatNumber(v) + "x");
    }

    /** Build a snap-to-tick {@link Slider} row. The slider sits below the
     *  section label, spans the full inner row width minus the value-label
     *  gutter the slider draws internally, and lives in {@link #sliders}
     *  for input + render iteration. */
    private float addSliderRow(float x, float yTop, String label,
                               float[] ticks, float currentValue,
                               java.util.function.Consumer<Float> setter,
                               java.util.function.Function<Float, String> fmt) {
        rowLabels.add(new RowLabel(label, x, yTop));
        float winW = Math.min(UIVars.VIRTUAL_W - 2 * SCREEN_MARGIN,
                ctx.worldW() - 2 * SCREEN_MARGIN);
        float innerW = winW - 2 * WIN_PAD;
        float by = yTop - ROW_LABEL_H - CHOOSER_H;
        int initialIdx = Slider.nearestIndex(ticks, currentValue);
        Slider sl = new Slider(x, by, innerW, CHOOSER_H,
                ticks, initialIdx,
                idx -> setter.accept(ticks[idx]),
                fmt);
        sliders.add(sl);
        return by - ROW_GAP;
    }

    /** Build a single-{@link Toggle} row - caption on the LEFT, oval pill
     *  on the right edge, both on the same baseline. Toggle reads its
     *  value live from the supplier, so no rebuild is needed when the
     *  value flips. Row consumes ~{@link #TOGGLE_ROW_H} of vertical space
     *  vs. the ~70 px a slider/chooser row needs (ROW_LABEL_H + CHOOSER_H +
     *  ROW_GAP) - those draw on their own line because the value label needs
     *  the horizontal real estate. */
    private float addToggleRow(float x, float yTop, String label,
                               java.util.function.BooleanSupplier getter,
                               java.util.function.Consumer<Boolean> setter) {
        float winW = Math.min(UIVars.VIRTUAL_W - 2 * SCREEN_MARGIN,
                ctx.worldW() - 2 * SCREEN_MARGIN);
        float innerW = winW - 2 * WIN_PAD;
        float toggleW = 44f;
        float toggleH = 20f;
        float tx = x + innerW - toggleW;
        float ty = yTop - TOGGLE_ROW_H + (TOGGLE_ROW_H - toggleH) * 0.5f;
        // Label baseline sits inside the row, vertically aligned with the
        // pill's centre.
        float labelY = yTop - (TOGGLE_ROW_H - ctx.lineH()) * 0.5f;
        rowLabels.add(new RowLabel(label, x, labelY));
        Toggle t = new Toggle(tx, ty, toggleW, toggleH, getter, setter);
        toggles.add(t);
        return yTop - TOGGLE_ROW_H - ROW_GAP;
    }

    /** Vertical band consumed by one toggle row (caption + pill on the
     *  same baseline). Smaller than the {@code CHOOSER_H + ROW_LABEL_H}
     *  used by sliders. */
    private static final float TOGGLE_ROW_H = 28f;

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
        tabs.drawShapes(ctx.shapes);
        band.clip(ctx, () -> {
            for (Toggle t : toggles) t.drawShape(ctx);
            for (Slider s : sliders) s.drawShape(ctx);
        });
        band.drawScrollbar(ctx.shapes, tabContentH);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        // Window title bar - a header label drawn ABOVE the window for now,
        // since our window content is fully consumed by tabs + choosers.
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, TextCatalog.get("ui.settings.title"),
                ctx.worldW() * 0.5f, ctx.worldH() - 24f);

        // Tab icons from the shared UI sheet.
        Tab[] tabVals = Tab.values();
        var tabIcons = new com.badlogic.gdx.graphics.g2d.TextureRegion[tabVals.length];
        for (int i = 0; i < tabVals.length; i++) {
            tabIcons[i] = IconSprites.regionFor(iconFor(tabVals[i]));
        }
        tabs.drawIcons(ctx, tabIcons);

        // App version / build, bottom-right corner (bottom-left is the
        // BackBtn) - matches the title screen's bottom-left stamp.
        TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                "v" + com.bjsp123.rl2.util.AppVersion.label(),
                ctx.worldW() - 10f, 10f + ctx.lineH());

        band.clip(ctx, () -> {
            for (RowLabel rl : rowLabels) {
                TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, rl.text, rl.x, rl.y);
            }
            for (Toggle t : toggles) t.drawText(ctx);
            for (Slider s : sliders) s.drawText(ctx);
        });
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        // Tab strip.
        if (tabs.touchDown(vx, vy) >= 0) return true;
        // Toggles fire on tap-down release; we mark pressed here so the
        // pill highlights while held.
        for (Toggle t : toggles) {
            if (t.hit(vx, vy)) { t.pressed = true; return true; }
        }
        // Sliders: tap on the track snaps to the nearest tick immediately
        // AND captures the drag, so a tap-then-drag works without a touchUp
        // round-trip.
        for (Slider s : sliders) {
            if (s.hitTrack(vx, vy)) {
                s.dragging = true;
                s.updateFromPointer(vx);
                activeSlider = s;
                return true;
            }
        }
        // Empty band area: seed a potential scroll drag.
        bandTouchActive = band.touchDown(vx, vy);
        return bandTouchActive;
    }

    /** True while the current touch started on empty band space - gates the
     *  drag-to-scroll path so a drag that began on a widget (whose touchDown
     *  consumed the event before the band was seeded) can't reuse a stale
     *  drag anchor and jump the scroll. */
    private boolean bandTouchActive;

    @Override
    protected boolean onTouchDragged(float vx, float vy) {
        if (activeSlider != null) {
            activeSlider.updateFromPointer(vx);
            return true;
        }
        if (bandTouchActive && band.touchDragged(vy)) {
            buildLayout();   // re-place rows at the new scroll offset
            return true;
        }
        return false;
    }

    @Override
    protected boolean onScrolled(float amountY) {
        if (band.scroller.maxScroll() <= 0f) return false;
        band.scrolled(amountY);
        buildLayout();   // re-place rows at the new scroll offset
        return true;
    }

    @Override
    protected boolean onTouchUpInBody(float vx, float vy) {
        bandTouchActive = false;
        band.scroller.onTouchUp();
        if (tabs.hasPressed()) {
            int i = tabs.touchUp(vx, vy);
            if (i >= 0 && currentTab != Tab.values()[i]) {
                currentTab = Tab.values()[i];
                band.scroller.resetTop();   // new tab starts at its first row
                show();   // rebuild the tab-specific body rows
            }
            return true;
        }
        boolean consumed = false;
        if (activeSlider != null) {
            activeSlider.dragging = false;
            activeSlider = null;
            consumed = true;
        }
        for (Toggle t : toggles) {
            if (t.pressed) {
                t.pressed = false;
                if (t.hit(vx, vy)) t.click();
                consumed = true;
            }
        }
        return consumed;
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

    /** Map each tab to a glyph in the shared UI icon sheet. */
    private static IconSprites.Icon iconFor(Tab t) {
        return switch (t) {
            case GAMEPLAY -> IconSprites.Icon.GAME;
            case GRAPHICS -> IconSprites.Icon.VIDEO;
            case LOG      -> IconSprites.Icon.BOOK;
            case AUDIO    -> IconSprites.Icon.SOUND;
        };
    }

    /** Drop trailing zeros on a float so 1.0 reads as "1" and 0.55 stays as "0.55". */
    private static String formatNumber(float v) {
        if (v == (int) v) return Integer.toString((int) v);
        String s = com.bjsp123.rl2.util.Fmt.of("%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Section-label position (drawn between row choosers). */
    private record RowLabel(String text, float x, float y) {}
}
