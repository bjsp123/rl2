package com.bjsp123.rl2.ui.v2;

/** Small geometry helpers for common V2 panel shapes. */
public final class PanelLayout {
    private PanelLayout() {}

    public static void centered(Rect out, UiCtx ctx,
                                float maxW, float maxH,
                                float marginX, float marginY) {
        float w = Math.min(maxW, ctx.worldW() - 2f * marginX);
        float h = Math.min(maxH, ctx.worldH() - 2f * marginY);
        out.set((ctx.worldW() - w) * 0.5f,
                (ctx.worldH() - h) * 0.5f, w, h);
    }

    public static void tabStrip(Rect[] out, Rect window,
                                float pad, float y, float h, float gap) {
        if (out == null || out.length == 0) return;
        float innerW = window.w - 2f * pad;
        float tabW = (innerW - (out.length - 1) * gap) / out.length;
        for (int i = 0; i < out.length; i++) {
            out[i].set(window.x + pad + i * (tabW + gap), y, tabW, h);
        }
    }
}
