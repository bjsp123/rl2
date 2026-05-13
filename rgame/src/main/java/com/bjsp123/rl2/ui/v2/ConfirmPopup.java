package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.ui.v2.stage.Scrim;

/**
 * Reusable two-button confirmation modal for destructive or high-friction
 * actions. The popup owns its chrome, text, and first-priority input so
 * screens do not need to mutate button labels or duplicate modal plumbing.
 */
public final class ConfirmPopup implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    private final UiCtx ctx;
    private final Rect window = new Rect();
    private final Rect confirmBtn = new Rect();
    private final Rect cancelBtn = new Rect();

    private String title = "Confirm?";
    private String message = "";
    private String confirmLabel = "Confirm";
    private String cancelLabel = "Cancel";
    private Runnable onConfirm;
    private Runnable onCancel;
    private boolean open;
    private boolean confirmPressed;
    private boolean cancelPressed;

    public ConfirmPopup(UiCtx ctx) {
        this.ctx = ctx;
    }

    public void configure(String title, String message,
                          String confirmLabel, String cancelLabel,
                          Runnable onConfirm) {
        this.title = title == null ? "Confirm?" : title;
        this.message = message == null ? "" : message;
        this.confirmLabel = confirmLabel == null ? "Confirm" : confirmLabel;
        this.cancelLabel = cancelLabel == null ? "Cancel" : cancelLabel;
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
        open = true;
        confirmPressed = false;
        cancelPressed = false;
    }

    public void close() {
        open = false;
        confirmPressed = false;
        cancelPressed = false;
    }

    public void cancel() {
        close();
        if (onCancel != null) onCancel.run();
    }

    public Rect window() {
        layout();
        return window;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void renderSelf() {
        if (!open) return;
        layout();

        Scrim.draw(ctx);

        ctx.applyProjection();
        com.badlogic.gdx.Gdx.gl.glEnable(GL20.GL_BLEND);
        com.badlogic.gdx.Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 1f);
        s.rect(window.x, window.y, window.w, window.h);
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
        drawButton(s, confirmBtn, confirmPressed);
        drawButton(s, cancelBtn, cancelPressed);
        s.end();
        com.badlogic.gdx.Gdx.gl.glDisable(GL20.GL_BLEND);

        ctx.batch.begin();
        TextDraw.centreFit(ctx, ctx.fontHeader, UIVars.TEXT_WARN, title,
                window.cx(), window.top() - ctx.headerLineH(), window.w - 28f);
        TextDraw.TextBlock body = TextDraw.block(ctx.fontRegular, message,
                window.w - 28f, 3, ctx.lineH());
        TextDraw.wrappedCentre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                body, window.cx(),
                window.top() - ctx.headerLineH() - ctx.lineH() - 8f);
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

    private void layout() {
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

    private void drawButton(ShapeRenderer s, Rect r, boolean pressed) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
        s.setColor(pressed ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
        s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
    }

    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!open) return false;
                layout();
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                confirmPressed = confirmBtn.contains(vx, vy);
                cancelPressed = cancelBtn.contains(vx, vy);
                if (!window.contains(vx, vy)) cancel();
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                if (!open) return false;
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
                if (!open) return false;
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    cancel();
                    return true;
                }
                return true;
            }
        };
    }
}
