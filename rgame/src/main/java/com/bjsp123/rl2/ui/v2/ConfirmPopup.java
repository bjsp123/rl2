package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.TextCatalog;

/**
 * Reusable two-button confirmation modal for destructive or high-friction
 * actions. The popup owns its chrome, text, and first-priority input so
 * screens do not need to mutate button labels or duplicate modal plumbing.
 */
public final class ConfirmPopup extends BasePopup {

    private final Rect confirmBtn = new Rect();
    private final Rect cancelBtn = new Rect();

    private String title = "";
    private String message = "";
    private String confirmLabel = "";
    private String cancelLabel = TextCatalog.get("ui.common.cancel");
    private Runnable onConfirm;
    private Runnable onCancel;
    private boolean confirmPressed;
    private boolean cancelPressed;

    public ConfirmPopup(UiCtx ctx) {
        super(ctx);
    }

    public void configure(String title, String message,
                          String confirmLabel, String cancelLabel,
                          Runnable onConfirm) {
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
        this.confirmLabel = confirmLabel == null ? "" : confirmLabel;
        this.cancelLabel = cancelLabel == null ? TextCatalog.get("ui.common.cancel") : cancelLabel;
        this.onConfirm = onConfirm;
        this.onCancel = null;
    }

    public void configure(String title, String message,
                          String confirmLabel, String cancelLabel,
                          Runnable onConfirm, Runnable onCancel) {
        configure(title, message, confirmLabel, cancelLabel, onConfirm);
        this.onCancel = onCancel;
    }

    public void open() {
        super.open();
        confirmPressed = false;
        cancelPressed = false;
    }

    public void close() {
        super.close();
        confirmPressed = false;
        cancelPressed = false;
    }

    public void cancel() {
        close();
        if (onCancel != null) onCancel.run();
    }

    public Rect window() {
        layoutRects();
        return window;
    }

    @Override
    protected void layoutRects() {
        float w = Math.min(320f, ctx.worldW() - 24f);
        float h = Math.min(184f, ctx.worldH() - 24f);
        window.set((ctx.worldW() - w) * 0.5f,
                (ctx.worldH() - h) * 0.5f, w, h);

        float pad = 14f;
        float gap = 10f;
        float btnH = 44f;
        float btnW = (w - 2f * pad - gap) * 0.5f;
        float btnY = window.y + pad;
        confirmBtn.set(window.x + pad, btnY, btnW, btnH);
        cancelBtn.set(confirmBtn.right() + gap, btnY, btnW, btnH);
    }

    @Override
    protected void renderShapesPass() {
        beginModalShapes();
        ShapeRenderer s = ctx.shapes;
        drawScrim();
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
        drawButton(s, confirmBtn, confirmPressed);
        drawButton(s, cancelBtn, cancelPressed);
        endModalShapes();
    }

    @Override
    protected void renderTextPass() {
        ctx.batch.begin();
        // Title in the regular (smaller) font, wrapped to two lines - item
        // names ("Recycle the Cloth Tunic +1?") routinely outgrow a single
        // header-font line on narrow viewports.
        TextDraw.TextBlock titleBlock = TextDraw.block(ctx.fontRegular, title,
                window.w - 28f, 2, ctx.lineH());
        TextDraw.wrappedCentre(ctx, ctx.fontRegular, UIVars.TEXT_WARN,
                titleBlock, window.cx(), window.top() - ctx.lineH());
        TextDraw.TextBlock body = TextDraw.block(ctx.fontRegular, message,
                window.w - 28f, 3, ctx.lineH());
        TextDraw.wrappedCentre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                body, window.cx(),
                window.top() - titleBlock.height() - ctx.lineH() - 8f);
        TextDraw.centreFit(ctx, ctx.fontHeader,
                confirmPressed ? UIVars.ACCENT : UIVars.TEXT_WARN,
                confirmLabel, confirmBtn.cx(), confirmBtn.cy() + 8f,
                confirmBtn.w - 12f);
        TextDraw.centreFit(ctx, ctx.fontHeader,
                cancelPressed ? UIVars.ACCENT : UIVars.TEXT_BODY,
                cancelLabel, cancelBtn.cx(), cancelBtn.cy() + 8f,
                cancelBtn.w - 12f);
        ctx.batch.end();
    }

    private void drawButton(ShapeRenderer s, Rect r, boolean pressed) {
        ButtonChrome.shape(ctx, r, pressed, false, false, UIVars.BTN_BG);
    }

    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                layoutRects();
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                confirmPressed = confirmBtn.contains(vx, vy);
                cancelPressed = cancelBtn.contains(vx, vy);
                if (!window.contains(vx, vy)) cancel();
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                boolean confirm = confirmPressed && confirmBtn.contains(vx, vy);
                boolean cancel = cancelPressed && cancelBtn.contains(vx, vy);
                confirmPressed = false;
                cancelPressed = false;
                if (confirm) {
                    close();
                    if (onConfirm != null) onConfirm.run();
                } else if (cancel) {
                    cancel();
                }
                return true;
            }

            @Override
            public boolean keyDown(int keycode) {
                if (!isOpen()) return false;
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    cancel();
                    return true;
                }
                // Enter / Space fires the primary action - desktop muscle
                // memory expects this on any confirmation dialog.
                if (keycode == Input.Keys.ENTER
                        || keycode == Input.Keys.NUMPAD_ENTER
                        || keycode == Input.Keys.SPACE) {
                    close();
                    if (onConfirm != null) onConfirm.run();
                    return true;
                }
                return true;
            }
        };
    }
}
