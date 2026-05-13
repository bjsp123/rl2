package com.bjsp123.rl2.ui.v2;

import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.ui.skin.AnimationSpeed;
import com.bjsp123.rl2.ui.skin.LogFontScale;
import com.bjsp123.rl2.ui.skin.LogPreferences;
import com.bjsp123.rl2.ui.skin.MobOutline;
import com.bjsp123.rl2.ui.skin.UiFontScale;
import com.bjsp123.rl2.ui.skin.UiScale;
import com.bjsp123.rl2.world.render.IconSprites;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 settings screen — tab strip across the top of a window, content panel
 * below. Each tab shows a vertical stack of "labelled rows", each row a
 * setting name plus a horizontal mini-button choice grid.
 *
 * <p>The tab strip itself is a row of {@link Btn}s with sticky-checked state.
 * Switching tabs rebuilds the body buttons (settings are tab-specific and
 * each tab's chooser button rects depend on the tab content's height).
 */
public final class V2Settings extends V2Screen {

    private enum Tab { GAMEPLAY, GRAPHICS, LOG, DANGER }
    private static Tab currentTab = Tab.GRAPHICS;
    /** Tracks whether the user's first tap on "Clear Hall of Fame" is pending
     *  confirmation. Resets when the tab is rebuilt (i.e. on any navigation). */
    private boolean clearHofPending = false;

    private final Rl2Game game;
    private final Rect window = new Rect();

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
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override
    protected void buildLayout() {
        rowLabels.clear();
        tabBtns.clear();

        // Outer window — caps at the design width so the panel doesn't
        // stretch into a wide letterbox on widescreen displays. Centred
        // horizontally on the viewport so the surrounding world breathes
        // evenly on either side. Top reserved for the screen header;
        // bottom reserved for the back/burger overlay row.
        float winW = Math.min(UIVars.VIRTUAL_W - 2 * SCREEN_MARGIN,
                ctx.worldW() - 2 * SCREEN_MARGIN);
        float winH = Math.min(UIVars.VIRTUAL_H - 110f, ctx.worldH() - 110f);
        float winX = (ctx.worldW() - winW) * 0.5f;
        float winY = (ctx.worldH() - winH) * 0.5f;
        window.set(winX, winY, winW, winH);

        // Reset confirm state when layout rebuilds.
        clearHofPending = false;

        // Tab strip — anchored at the TOP of the window's interior. Tab
        // widths are computed so all tabs FIT within the inner width — no
        // tab strip ever overflows the panel. Total tab strip width =
        // n*tabW + (n-1)*TAB_GAP = innerW; solve for tabW.
        float tabsY = winY + winH - WIN_PAD - TAB_H;
        float tabsX = winX + WIN_PAD;
        Tab[] tabs = { Tab.GAMEPLAY, Tab.GRAPHICS, Tab.LOG, Tab.DANGER };
        float innerW = winW - 2 * WIN_PAD;
        float tabW   = (innerW - (tabs.length - 1) * TAB_GAP) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            final Tab t = tabs[i];
            Btn b = new Btn(label(t),
                    tabsX + i * (tabW + TAB_GAP), tabsY, tabW, TAB_H,
                    () -> { currentTab = t; show(); });
            b.checked = (t == currentTab);
            // Icon sourced from the shared UI sheet — text label fallback
            // kicks in if the sheet failed to load.
            b.icon = IconSprites.regionFor(iconFor(t));
            tabBtns.add(b);
            buttons.add(b);
        }

        // Tab content — vertical stack of "labelled rows" beneath the tab
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
            }
            case GAMEPLAY -> {
                yCursor = addAnimationSpeedRow(winX + WIN_PAD, yCursor);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, "Queue Acceleration",
                        AnimationSpeed::queueAccelEnabled, AnimationSpeed::setQueueAccelEnabled);
                yCursor = addQuickslotCountRow(winX + WIN_PAD, yCursor);
                yCursor = addButtonRow(winX + WIN_PAD, yCursor, "Clear Hall of Fame");
            }
            case LOG -> {
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, "Show Event Log",
                        LogPreferences::logOn, LogPreferences::setLogOn);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, "Low-Priority Lines",
                        LogPreferences::showLowPriority, LogPreferences::setShowLowPriority);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, "Mob-vs-Mob Lines",
                        LogPreferences::showNonPlayer, LogPreferences::setShowNonPlayer);
                yCursor = addBoolRow(winX + WIN_PAD, yCursor, "Expanded Log",
                        LogPreferences::expanded, LogPreferences::setExpanded);
                yCursor = addLogFontScaleRow(winX + WIN_PAD, yCursor);
            }
            case DANGER -> addDangerTabContent(winX + WIN_PAD, contentTop, innerW);
        }

        // Back button at bottom-right — pops the navigation stack so the
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
        // Section label text — drawn by drawBodyText. yTop is the top of the
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
        Btn b = new Btn(label, x, yTop - CHOOSER_H, innerW, CHOOSER_H, () -> {
            game.hallOfFame.entries.clear();     
            show();
        });
        b.warn = true;
        buttons.add(b);
        return yTop - CHOOSER_H - ROW_GAP;
    }

    private float addUiScaleRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float s : UiScale.CHOICES) {
            final float chosen = s;
            choices.add(new ChoiceSpec(formatNumber(s) + "x", 50f,
                    Math.abs(UiScale.scale() - s) < 0.001f,
                    () -> {
                        UiScale.set(chosen);
                        ctx.applyUiScale();
                        show();
                    }));
        }
        return addRow("UI Scale", x, yTop, choices);
    }

    private float addUiFontScaleRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float s : UiFontScale.CHOICES) {
            final float chosen = s;
            choices.add(new ChoiceSpec(formatNumber(s) + "x", 56f,
                    Math.abs(UiFontScale.scale() - s) < 0.001f,
                    () -> {
                        UiFontScale.set(chosen);
                        ctx.applyFontScale();
                        show();
                    }));
        }
        return addRow("UI Font Size", x, yTop, choices);
    }

    private float addOutlineWidthRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float w : MobOutline.WIDTH_CHOICES) {
            final float chosen = w;
            choices.add(new ChoiceSpec(formatNumber(w), 50f,
                    Math.abs(MobOutline.width() - w) < 0.0001f,
                    () -> { MobOutline.setWidth(chosen); show(); }));
        }
        return addRow("Mob Outline Width", x, yTop, choices);
    }

    private float addOutlineDarknessRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float a : MobOutline.DARKNESS_CHOICES) {
            final float chosen = a;
            choices.add(new ChoiceSpec(formatNumber(a), 50f,
                    Math.abs(MobOutline.darkness() - a) < 0.0001f,
                    () -> { MobOutline.setDarkness(chosen); show(); }));
        }
        return addRow("Mob Outline Darkness", x, yTop, choices);
    }

    private float addOutlineSmoothRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        choices.add(new ChoiceSpec("Smooth", 88f, MobOutline.smooth(),
                () -> { MobOutline.setSmooth(true);  show(); }));
        choices.add(new ChoiceSpec("Pixel",  88f, !MobOutline.smooth(),
                () -> { MobOutline.setSmooth(false); show(); }));
        return addRow("Mob Outline Smoothing", x, yTop, choices);
    }

    private float addAnimationSpeedRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float n : AnimationSpeed.CHOICES) {
            final float chosen = n;
            choices.add(new ChoiceSpec(n + "x", 56f,
                    AnimationSpeed.framesPerRender() == n,
                    () -> { AnimationSpeed.setFramesPerRender(chosen); show(); }));
        }
        return addRow("Animation Speed", x, yTop, choices);
    }

    private float addQuickslotCountRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (int n : com.bjsp123.rl2.ui.skin.QuickslotCount.CHOICES) {
            final int chosen = n;
            choices.add(new ChoiceSpec(String.valueOf(n), 56f,
                    com.bjsp123.rl2.ui.skin.QuickslotCount.count() == n,
                    () -> { com.bjsp123.rl2.ui.skin.QuickslotCount.set(chosen); show(); }));
        }
        return addRow("Quickslots", x, yTop, choices);
    }

    private float addLogFontScaleRow(float x, float yTop) {
        List<ChoiceSpec> choices = new ArrayList<>();
        for (float f : LogFontScale.CHOICES) {
            final float chosen = f;
            choices.add(new ChoiceSpec(formatNumber(f) + "x", 50f,
                    Math.abs(LogFontScale.scale() - f) < 0.001f,
                    () -> { LogFontScale.set(chosen); show(); }));
        }
        return addRow("Log Font Size", x, yTop, choices);
    }

    private float addBoolRow(float x, float yTop, String label,
                             java.util.function.BooleanSupplier getter,
                             java.util.function.Consumer<Boolean> setter) {
        List<ChoiceSpec> choices = new ArrayList<>();
        choices.add(new ChoiceSpec("On", 70f, getter.getAsBoolean(),
                () -> { setter.accept(true);  show(); }));
        choices.add(new ChoiceSpec("Off", 70f, !getter.getAsBoolean(),
                () -> { setter.accept(false); show(); }));
        return addRow(label, x, yTop, choices);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        // Window title bar — a header label drawn ABOVE the window for now,
        // since our window content is fully consumed by tabs + choosers.
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, "Settings",
                ctx.worldW() * 0.5f, ctx.worldH() - 24f);

        for (RowLabel rl : rowLabels) {
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, rl.text, rl.x, rl.y);
        }
    }

    private void addDangerTabContent(float x, float yTop, float innerW) {
        String btnLabel = clearHofPending
                ? "Confirm — this cannot be undone"
                : "Clear Hall of Fame";
        Btn b = new Btn(btnLabel, x, yTop - CHOOSER_H, innerW, CHOOSER_H, () -> {
            if (!clearHofPending) {
                clearHofPending = true;
            } else {
                game.hallOfFame.entries.clear();
                com.bjsp123.rl2.save.HallOfFameStore.save(game.persistence, game.hallOfFame);
                clearHofPending = false;
                show();
            }
        });
        b.warn = true;
        buttons.add(b);
    }

    private static String label(Tab t) {
        return switch (t) {
            case GAMEPLAY -> "Play";
            case GRAPHICS -> "Graphics";
            case LOG      -> "Log";
            case DANGER   -> "Danger";
        };
    }

    /** Map each tab to a glyph in the shared UI icon sheet. */
    private static IconSprites.Icon iconFor(Tab t) {
        return switch (t) {
            case GAMEPLAY -> IconSprites.Icon.GAME;
            case GRAPHICS -> IconSprites.Icon.VIDEO;
            case LOG      -> IconSprites.Icon.BOOK;
            case DANGER   -> IconSprites.Icon.CANCEL;
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
