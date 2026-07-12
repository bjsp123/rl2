package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.world.render.ItemSprites;

import java.util.ArrayList;
import java.util.List;

/**
 * Small modal help window opened by long-pressing a UI element: an optional
 * icon, a title, and a wrapped body paragraph. The one popup behind every
 * long-press help surface - HUD chrome, inventory slots, and any {@link Btn}
 * carrying a {@code helpKey}. Fully modal; any tap or Back/Escape dismisses.
 *
 * <p>For items ({@link #open(Item, Mob)}) the body is the same
 * {@link com.bjsp123.rl2.ui.ItemLore} flavor + details text the inventory
 * detail panel shows, so item help reads identically everywhere.
 */
public final class V2HelpPopup extends BasePopup {

    private static final float ICON_SZ = 48f;

    private String title = "";
    private String body = "";
    private TextureRegion icon;
    private final List<String> bodyLines = new ArrayList<>();
    private boolean swallowNextUp;

    public V2HelpPopup(UiCtx ctx) { super(ctx); }

    /** Open with pre-resolved strings (no icon). */
    public void open(String title, String body) {
        open(title, body, null);
    }

    public void open(String title, String body, TextureRegion icon) {
        this.title = title != null ? title : "";
        this.body = body != null ? body : "";
        this.icon = icon;
        open();
    }

    /** Open for a strings.csv help key pair ({@code help.<key>.title} /
     *  {@code help.<key>.body}), falling back to {@code fallbackTitle} and
     *  the generic no-help body when the catalog has no entry. */
    public void openKey(String helpKey, String fallbackTitle) {
        open(TextCatalog.getOrDefault("help." + helpKey + ".title", fallbackTitle),
             TextCatalog.getOrDefault("help." + helpKey + ".body",
                     TextCatalog.get("help.generic.body")));
    }

    /** Open as an item lore card - title is the item's display name, body is
     *  the ItemLore flavor blurb followed by the mechanical details.
     *  {@code holder} (nullable) drives the effective-level numbers. */
    public void open(Item item, Mob holder) {
        if (item == null) return;
        String name = com.bjsp123.rl2.logic.ItemNames.displayName(item, holder);
        if (name == null || name.isEmpty()) name = item.type != null ? item.type : "";
        String flavor = com.bjsp123.rl2.ui.ItemLore.describeFlavor(item);
        String details = com.bjsp123.rl2.ui.ItemLore.describeDetails(item, holder);
        StringBuilder sb = new StringBuilder();
        if (flavor != null && !flavor.isEmpty()) sb.append(flavor);
        if (details != null && !details.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(details);
        }
        open(TextCatalog.titleCase(name), sb.toString(), ItemSprites.regionFor(item));
    }

    @Override
    protected boolean canRender() { return !title.isEmpty() || !body.isEmpty(); }

    @Override
    protected void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(300f, vw - 32f);

        bodyLines.clear();
        if (!body.isEmpty()) {
            TextDraw.wrap(ctx.fontRegular, body, winW - 28f, 20, bodyLines);
        }

        float iconH = icon != null ? ICON_SZ + 8f : 0f;
        float winH = Math.min(
                32f + iconH + ctx.headerLineH() + 10f + bodyLines.size() * ctx.lineH(),
                vh - 48f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);
    }

    @Override
    protected void drawWindow() {
        Window.drawInfoShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void renderShapesPass() {
        beginModalShapes();
        drawScrim();
        drawWindow();
        // Fully-opaque inner fill so the body text stays legible over a busy
        // world backdrop - same treatment as V2BuffInfo.
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = ctx.shapes;
        s.setColor(UIVars.INFO_WIN_BG.r, UIVars.INFO_WIN_BG.g, UIVars.INFO_WIN_BG.b, 1f);
        float inset = 4f;
        s.rect(window.x + inset, window.y + inset,
                window.w - 2 * inset, window.h - 2 * inset);
        endModalShapes();
    }

    @Override
    protected void renderTextPass() {
        ctx.batch.begin();

        float top = window.top() - 16f;
        if (icon != null) {
            ctx.batch.draw(icon, window.cx() - ICON_SZ * 0.5f, top - ICON_SZ,
                    ICON_SZ, ICON_SZ);
            top -= ICON_SZ + 8f;
        }
        top -= ctx.lineH() * 0.4f;
        TextDraw.centreFit(ctx, ctx.fontHeader, UIVars.ACCENT, title,
                window.cx(), top, window.w - 28f);

        top -= ctx.headerLineH();
        float left = window.x + 14f;
        for (String line : bodyLines) {
            if (top < window.y + 10f) break;   // clipped by the height clamp
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY, line, left, top);
            top -= ctx.lineH();
        }

        ctx.batch.end();
    }

    /** Modal dismiss processor - ANY tap (inside or outside the window) or
     *  Back/Escape closes; the matching touchUp is swallowed so the tap
     *  can't leak a world move / HUD click through the closing popup. */
    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                close();
                swallowNextUp = true;
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                if (swallowNextUp) { swallowNextUp = false; return true; }
                return isOpen();
            }

            @Override
            public boolean keyDown(int keycode) {
                return closeOnBack(keycode);
            }
        };
    }
}
