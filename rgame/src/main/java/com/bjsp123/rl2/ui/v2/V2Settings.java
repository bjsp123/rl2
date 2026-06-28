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

    private enum Tab { GAMEPLAY, GRAPHICS, LOG, AUDIO, ACCESS }
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

    /** Toggle widgets for boolean settings. Iterated alongside {@link #buttons}
     *  in the input handlers so a tap on a pill flips the value. */
    private final List<Toggle> toggles = new ArrayList<>();

    /** Slider widgets for discrete-choice scalar settings. */
    private final List<Slider> sliders = new ArrayList<>();

    /** Slider currently capturing pointer drag, or {@code null}. Set on
     *  touchDown-in-track, cleared on touchUp. */
    private Slider activeSlider;

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
        Tab[] tabs = { Tab.GAMEPLAY, Tab.GRAPHICS, Tab.LOG, Tab.AUDIO, Tab.ACCESS };
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
        float rowX = winX + WIN_PAD;
        switch (currentTab) {
            case GRAPHICS -> {
                yCursor = addUiScaleRow(rowX, yCursor);
                yCursor = addUiFontScaleRow(rowX, yCursor);
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
                yCursor = addToggleRow(rowX, yCursor, TextCatalog.get("ui.settings.meleePreview"),
                        Settings::meleePreview, Settings::setMeleePreview);
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
            case ACCESS -> {
                yCursor = addColorblindRow(rowX, yCursor);
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

    private float addUiFontScaleRow(float x, float yTop) {
        return addSliderRow(x, yTop, TextCatalog.get("ui.settings.uiFontSize"),
                Settings.UI_FONT_SCALE_CHOICES, Settings.uiFontScale(),
                v -> { Settings.setUiFontScale(v); ctx.applyFontScale(); },
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
        for (Toggle t : toggles) t.drawShape(ctx);
        for (Slider s : sliders) s.drawShape(ctx);
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
        for (Toggle t : toggles) t.drawText(ctx);
        for (Slider s : sliders) s.drawText(ctx);
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
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
        return false;
    }

    @Override
    protected boolean onTouchDragged(float vx, float vy) {
        if (activeSlider != null) {
            activeSlider.updateFromPointer(vx);
            return true;
        }
        return false;
    }

    @Override
    protected boolean onTouchUpInBody(float vx, float vy) {
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

    private static String label(Tab t) {
        return switch (t) {
            case GAMEPLAY -> TextCatalog.get("ui.settings.tab.play");
            case GRAPHICS -> TextCatalog.get("ui.settings.tab.graphics");
            case LOG      -> TextCatalog.get("ui.settings.tab.log");
            case AUDIO    -> TextCatalog.get("ui.settings.tab.audio");
            case ACCESS   -> TextCatalog.get("ui.settings.tab.access");
        };
    }

    /** Map each tab to a glyph in the shared UI icon sheet. */
    private static IconSprites.Icon iconFor(Tab t) {
        return switch (t) {
            case GAMEPLAY -> IconSprites.Icon.GAME;
            case GRAPHICS -> IconSprites.Icon.VIDEO;
            case LOG      -> IconSprites.Icon.BOOK;
            case AUDIO    -> IconSprites.Icon.SOUND;
            case ACCESS   -> IconSprites.Icon.GAME;
        };
    }

    private float addColorblindRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        Settings.ColorblindPreset current = Settings.colorblindPreset();
        for (Settings.ColorblindPreset p : Settings.ColorblindPreset.values()) {
            final Settings.ColorblindPreset chosen = p;
            choices.add(new ChoiceSpec(colorblindLabel(p), 80f,
                    current == p,
                    () -> { Settings.setColorblindPreset(chosen); show(); }));
        }
        return addRow(TextCatalog.get("ui.settings.colorblind"), x, yTop, choices);
    }

    private static String colorblindLabel(Settings.ColorblindPreset p) {
        return switch (p) {
            case NONE   -> TextCatalog.get("ui.settings.colorblind.none");
            case DEUTER -> TextCatalog.get("ui.settings.colorblind.deuter");
            case PROTAN -> TextCatalog.get("ui.settings.colorblind.protan");
            case TRITAN -> TextCatalog.get("ui.settings.colorblind.tritan");
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
