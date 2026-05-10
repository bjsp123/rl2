package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.input.LookMode;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.StatBlock;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.world.render.BuffIcons;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 look popup — modal info panel that appears alongside {@link LookMode}
 * and shows the contents of the tile under the cursor (terrain + items + mob).
 * Anchored to the top-centre of the viewport so the world view + bottom HUD
 * stay visible behind it.
 *
 * <p>Each section gets a "?" button when an encyclopaedia is wired —
 * tapping it opens the encyclopaedia pre-selected to that tile / mob entry,
 * deactivating look mode in the process so the V2 single-popup-at-a-time
 * rule is preserved.
 */
public final class V2Look implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    private final UiCtx ctx;
    private LookMode lookMode;
    private Level level;
    private V2Encyclopedia encyclopedia;

    private final Rect window = new Rect();
    /** Top of the mob-block region, captured during layout so the text pass
     *  can render its first line at a stable baseline regardless of how
     *  many items above it pushed the cursor around. */
    private float mobBlockTop;

    /** Pre-wrapped flavor lines for the mob description (rendered in bright
     *  text above the divider). Populated in {@link #layoutRects()}. */
    private final List<String> mobFlavorLines = new ArrayList<>();
    /** Y of the horizontal rule between the mob description and the live
     *  details below it, or {@code Float.NaN} when the looked-at mob has
     *  no description and no rule should be drawn. */
    private float mobDividerY = Float.NaN;
    /** Y of the chrome bar separating the tile / item section (top) from
     *  the mob section (bottom). Drawn as a tri-line strip whenever the
     *  looked-at tile carries a mob — without one there's nothing to
     *  separate from. */
    private float tileSectionDividerY = Float.NaN;

    /** Tile under the cursor, captured during layout for input + render. */
    private Tile lookedTile;
    /** Mob under the cursor (or null). */
    private Mob lookedMob;

    // "?" buttons.
    private final Rect tileInfoBtn = new Rect();
    private final Rect mobInfoBtn  = new Rect();
    private boolean tileInfoPressed, mobInfoPressed;
    /** Per-buff-icon hit rects on the looked-at mob, rebuilt every frame
     *  by the text pass that draws them. */
    private final List<Rect> buffIconRects = new ArrayList<>();
    private final List<Buff.BuffType> buffIconTypes = new ArrayList<>();
    private int buffIconPressed = -1;

    public V2Look(UiCtx ctx) { this.ctx = ctx; }

    public void setLookMode(LookMode lm)         { this.lookMode = lm; }
    public void setLevel(Level lvl)              { this.level = lvl; }
    /** Wire the encyclopaedia popup so the per-section "?" buttons can
     *  jump into it. Without this set, no "?" buttons render. */
    public void setEncyclopedia(V2Encyclopedia enc) { this.encyclopedia = enc; }

    /** True while the look popup is actually visible — i.e. look mode is
     *  active AND the player has committed a target via tap/click/enter.
     *  During the preview phase (look just activated, no tap yet) this
     *  returns false so the popup neither renders nor captures input —
     *  taps fall through to {@link LookMode}'s own handler so the player
     *  can pick a tile. */
    public boolean isOpen() {
        return lookMode != null && lookMode.isPanelVisible();
    }

    @Override
    public void renderSelf() {
        if (!isOpen()) return;
        captureLookedAt();
        layoutRects();
        renderShapesPass();
        renderTextPass();
    }

    private void captureLookedAt() {
        lookedTile = null;
        lookedMob  = null;
        Point cursor = lookMode != null ? lookMode.cursor() : null;
        if (cursor == null || level == null) return;
        int cx = (int) Math.floor(cursor.x());
        int cy = (int) Math.floor(cursor.y());
        if (cx >= 0 && cx < level.width && cy >= 0 && cy < level.height) {
            lookedTile = level.tiles[cx][cy];
        }
        if (level.mobs != null) {
            for (Mob m : level.mobs) {
                if (m == null || m.position == null) continue;
                if ((int) Math.floor(m.position.x()) == cx
                        && (int) Math.floor(m.position.y()) == cy) {
                    lookedMob = m;
                    break;
                }
            }
        }
    }

    private void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(320f, vw - 32f);

        // Wrap the mob description (when present) into the flavor block —
        // bright text above the divider rule. Width matches the live-detail
        // body width below.
        mobFlavorLines.clear();
        if (lookedMob != null && lookedMob.description != null
                && !lookedMob.description.isEmpty()) {
            TextDraw.wrap(ctx.fontRegular, lookedMob.description,
                    winW - 28f, 4, mobFlavorLines);
        }

        // Variable height — the flavor block at the top, optional divider
        // gap, then the live-stats block (header line + stats + state +
        // optional buff-icon row).
        int detailsH = 0;
        if (lookedMob != null) {
            detailsH = 22                                                 // header line
                    + 6 * 16                                              // stat lines
                    + 18                                                  // state + intent
                    + ((lookedMob.buffs != null && !lookedMob.buffs.isEmpty())
                            ? 22 : 0);
        }
        int flavorH = mobFlavorLines.size() * 16;
        boolean hasRule = !mobFlavorLines.isEmpty() && lookedMob != null;
        int ruleGap = hasRule ? 12 : 0;
        int mobBlockH = lookedMob == null
                ? 0 : flavorH + ruleGap + detailsH;
        float winH = 160f + mobBlockH;
        window.set((vw - winW) * 0.5f, vh - 80f - winH, winW, winH);
        // First-line baseline of the live-details block — anchored to the
        // bottom strip of the window so the buff icons never crowd the
        // window's lower edge.
        mobBlockTop = window.y + detailsH - 14f;
        // Divider sits between flavor block (top) and details block (bottom).
        mobDividerY = hasRule
                ? window.y + detailsH + ruleGap * 0.5f - 1f
                : Float.NaN;
        // Chrome bar separating tile / items section (above) from the mob
        // section (below). Anchored just above the topmost line of the
        // mob block; only drawn when there's a mob to introduce.
        tileSectionDividerY = lookedMob == null
                ? Float.NaN
                : window.y + mobBlockH + 6f;

        float infoSz = 22f;
        // Tile "?" button — top-right of the tile section.
        tileInfoBtn.set(window.right() - 14f - infoSz,
                window.top() - 80f, infoSz, infoSz);
        // Mob "?" button — beside the mob's name line at the top of the
        // live-details block. Aligned to the same X as the tile info button.
        mobInfoBtn .set(window.right() - 14f - infoSz,
                mobBlockTop - infoSz + 6f, infoSz, infoSz);
    }

    private void renderShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        if (encyclopedia != null && lookedTile != null) {
            drawInfoBtn(s, tileInfoBtn, tileInfoPressed);
        }
        if (encyclopedia != null && lookedMob != null) {
            drawInfoBtn(s, mobInfoBtn, mobInfoPressed);
        }
        // Horizontal rule between the bright mob flavor blurb and the
        // dim live-details body below it.
        if (!Float.isNaN(mobDividerY)) {
            s.setColor(UiColors.BORDER_MID);
            s.rect(window.x + 14f, mobDividerY,
                    window.w - 28f, 1f);
        }
        // Chrome bar between tile / items and the mob section — same
        // tri-line treatment as the window edge so the divide reads as
        // proper window furniture rather than just a hairline rule.
        if (!Float.isNaN(tileSectionDividerY)) {
            Edges.drawTriLine(s, window.x + 8f, tileSectionDividerY,
                    window.w - 16f, 4f, 1f);
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawInfoBtn(ShapeRenderer s, Rect r, boolean pressed) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W);
        s.setColor(pressed ? UiColors.BTN_PRESSED_BG : UiColors.BTN_BG);
        s.rect(r.x + Pal.HUD_BORDER, r.y + Pal.HUD_BORDER,
                r.w - 2 * Pal.HUD_BORDER, r.h - 2 * Pal.HUD_BORDER);
    }

    private void renderTextPass() {
        ctx.batch.begin();
        Point cursor = lookMode != null ? lookMode.cursor() : null;
        TextDraw.centre(ctx, ctx.fontHeader, UiColors.ACCENT, "Look",
                window.cx(), window.top() - 22f);

        if (cursor == null || level == null) {
            TextDraw.centre(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    "(no tile)", window.cx(), window.top() - 56f);
            ctx.batch.end();
            return;
        }

        int cx = (int) Math.floor(cursor.x());
        int cy = (int) Math.floor(cursor.y());
        float left = window.x + 14f;
        float top  = window.top() - 56f;

        if (lookedTile != null) {
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    "Terrain: " + lookedTile.name().toLowerCase(),
                    left, top);
            // "?" glyph centred in the tile-info button.
            if (encyclopedia != null) {
                TextDraw.centre(ctx, ctx.fontRegular,
                        tileInfoPressed ? UiColors.ACCENT : UiColors.TEXT_BODY,
                        "?", tileInfoBtn.cx(), tileInfoBtn.cy() + 6f);
            }
            top -= 18f;
        }

        // Surface (water / blood / oil / ice) and vegetation (grass /
        // mushrooms / fire / trees) on this tile — only drawn when
        // present so a bare floor stays uncluttered.
        if (level.surface != null
                && cx >= 0 && cy >= 0
                && cx < level.width && cy < level.height
                && level.surface[cx][cy] != null) {
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    "Surface: " + describeSurface(level.surface[cx][cy]),
                    left, top);
            top -= 18f;
        }
        if (level.vegetation != null
                && cx >= 0 && cy >= 0
                && cx < level.width && cy < level.height
                && level.vegetation[cx][cy] != null) {
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    "Plants: " + describeVegetation(level.vegetation[cx][cy]),
                    left, top);
            top -= 18f;
        }
        // Cloud — packed in level.cloud; non-zero means a gas overlay.
        if (level.cloud != null
                && cx >= 0 && cy >= 0
                && cx < level.width && cy < level.height
                && level.cloud[cx][cy] != 0) {
            com.bjsp123.rl2.model.Level.Cloud type =
                    com.bjsp123.rl2.logic.CloudSystem.type(level.cloud[cx][cy]);
            if (type != null) {
                TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                        "Cloud: " + type.name().toLowerCase().replace('_', ' '),
                        left, top);
                top -= 18f;
            }
        }
        if (lookedTile != null) top -= 4f;

        // Items on this tile.
        List<Item> floorItems = level.items;
        if (floorItems != null) {
            int count = 0;
            for (Item it : floorItems) {
                if (it == null || it.location == null) continue;
                int ix = (int) Math.floor(it.location.x());
                int iy = (int) Math.floor(it.location.y());
                if (ix == cx && iy == cy) {
                    if (count == 0) {
                        TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                                "Items:", left, top);
                        top -= 18f;
                    }
                    String iname = it.name != null ? it.name : it.type;
                    if (it.level > 1) iname += " +" + (it.level - 1);
                    TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_BODY,
                            "  " + iname, left, top);
                    top -= 16f;
                    count++;
                    if (count > 3) break;
                }
            }
        }

        // Mob on this tile — flavor description (bright) at the top of the
        // mob block, then the rule (drawn in the shape pass), then the
        // live-details body anchored to the bottom of the window so the
        // "?" jump button always sits beside the name line.
        if (lookedMob != null) {
            // Flavor lines — bright text above the divider rule. Top line
            // sits just under the divider's top edge, descending upward
            // from there.
            float flavorTop = (Float.isNaN(mobDividerY)
                    ? mobBlockTop + mobFlavorLines.size() * 16f
                    : mobDividerY + 6f + mobFlavorLines.size() * 16f);
            for (int i = 0; i < mobFlavorLines.size(); i++) {
                TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_BODY,
                        mobFlavorLines.get(i), left,
                        flavorTop - i * 16f);
            }
            renderMobBlock(left, mobBlockTop);
            // Mob "?" glyph.
            if (encyclopedia != null) {
                TextDraw.centre(ctx, ctx.fontRegular,
                        mobInfoPressed ? UiColors.ACCENT : UiColors.TEXT_BODY,
                        "?", mobInfoBtn.cx(), mobInfoBtn.cy() + 6f);
            }
        }
        ctx.batch.end();
    }

    /** Friendly label for a {@link com.bjsp123.rl2.model.Level.Surface}. */
    private static String describeSurface(com.bjsp123.rl2.model.Level.Surface s) {
        return switch (s) {
            case WATER -> "shallow water";
            case BLOOD -> "blood";
            case OIL   -> "oil slick";
            case ICE   -> "ice";
        };
    }

    /** Friendly label for a {@link com.bjsp123.rl2.model.Level.Vegetation}. */
    private static String describeVegetation(com.bjsp123.rl2.model.Level.Vegetation v) {
        return switch (v) {
            case GRASS     -> "grass";
            case MUSHROOMS -> "mushrooms";
            case FIRE      -> "fire";
            case TREES     -> "trees";
        };
    }

    /** Draw the live-mob-on-this-tile block — name + level, current HP / max HP,
     *  attack / defence / damage / armor, state-of-mind + intent, and an
     *  optional buff-icon row. Caller has the SpriteBatch open and supplies
     *  the top-left start position. */
    private void renderMobBlock(float left, float top) {
        Mob m = lookedMob;
        StatBlock s = m.effectiveStats();

        // Name + level header — dimmer than the flavor blurb above the
        // divider so the description / details split reads visually.
        String mname = m.name != null ? m.name : "mob";
        String header = "Mob: " + mname;
        if (m.characterLevel > 1) header += " (lvl " + m.characterLevel + ")";
        TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                header, left, top);
        top -= 18f;

        // Current HP / max HP.
        int hp    = (int) Math.round(m.hp);
        int maxHp = (int) Math.round(s.maxHp);
        TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                "HP " + hp + " / " + maxHp, left, top);
        top -= 16f;

        // Combat numbers — only emit the row when the value isn't a useless
        // default (zero damage / armor on a weaponless mob is uninteresting).
        TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                "Att " + s.accuracy + "   Def " + s.evasion, left, top);
        top -= 16f;
        TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                "Dmg " + range(s.damage)
                        + "   Arm " + range(s.armor),
                left, top);
        top -= 16f;
        if (!s.rangedDamage.isZero()) {
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    "Ranged " + range(s.rangedDamage)
                            + " @ " + s.rangedDistance + " sq",
                    left, top);
            top -= 16f;
        }

        // Attitude toward the player — colour-coded so the hostility of
        // anything on the tile reads at a glance.
        Mob viewer = com.bjsp123.rl2.logic.TurnSystem.findPlayer(level);
        if (viewer != null && m != viewer) {
            String attitudeLine = attitudeLabel(viewer, m);
            if (attitudeLine != null) {
                TextDraw.left(ctx, ctx.fontRegular,
                        attitudeColor(viewer, m),
                        attitudeLine, left, top);
                top -= 16f;
            }
        }

        // State of mind + intent — what the mob is doing right now.
        String state = stateOfMindLabel(m);
        if (state != null) {
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    state, left, top);
            top -= 16f;
        }

        // Loyalty — owned mobs (kittens, summoned dogs, tame creatures)
        // show whom they belong to so the player can spot allies at a
        // glance. Player owners read as "you" rather than the underlying
        // mobType string.
        if (m.owner != null) {
            String ownerLabel = m.owner.behavior == Mob.Behavior.PLAYER
                    ? "you"
                    : (m.owner.mobType != null
                            ? m.owner.mobType.toLowerCase() : "unknown");
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    "Loyal to: " + ownerLabel, left, top);
            top -= 16f;
        }

        // Buff icons row — each icon is a hit target that opens the
        // encyclopedia at that buff's page.
        buffIconRects.clear();
        buffIconTypes.clear();
        if (m.buffs != null && !m.buffs.isEmpty()) {
            float iconSz = 16f;
            float iconGap = 2f;
            int max = Math.min(m.buffs.size(), 8);
            for (int i = 0; i < max; i++) {
                Buff b = m.buffs.get(i);
                if (b == null || b.type == null) continue;
                TextureRegion region = BuffIcons.regionFor(b.type);
                if (region == null) continue;
                float ix = left + i * (iconSz + iconGap);
                float iy = top - iconSz;
                ctx.batch.draw(region, ix, iy, iconSz, iconSz);
                Rect r = new Rect();
                r.set(ix, iy, iconSz, iconSz);
                buffIconRects.add(r);
                buffIconTypes.add(b.type);
            }
        }
    }

    /** Human-readable "what is this mob doing" label combining
     *  {@link Mob#stateOfMind} with the per-tick {@link Mob#intent}. The
     *  player and inert species (e.g. mushrooms) get a brief description
     *  instead of a state machine label. Returns {@code null} when there
     *  is nothing useful to say. */
    /** Human-readable hostility line — "Hostile.", "Fearful.", "Neutral.",
     *  or "Friendly." — for {@code target}'s attitude toward {@code viewer}.
     *  Returns null when the attitude can't be resolved. */
    private static String attitudeLabel(Mob viewer, Mob target) {
        com.bjsp123.rl2.logic.MobSystem.Attitude a =
                com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(target, viewer);
        if (a == null) return null;
        return switch (a) {
            case ATTACK  -> "Hostile.";
            case FLEE    -> "Fearful.";
            case NOTHING -> "Neutral.";
            case ALLY    -> "Friendly.";
        };
    }

    /** Friendly green — UiColors has no OK / green-positive tone, so the
     *  attitude line uses this local constant for the ALLY case. */
    private static final com.badlogic.gdx.graphics.Color ATTITUDE_FRIENDLY =
            new com.badlogic.gdx.graphics.Color(0.4f, 0.85f, 0.4f, 1f);

    /** Tint matching {@link #attitudeLabel} — red for hostile, yellow for
     *  fearful, dim for neutral, green for friendly. */
    private static com.badlogic.gdx.graphics.Color attitudeColor(Mob viewer, Mob target) {
        com.bjsp123.rl2.logic.MobSystem.Attitude a =
                com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(target, viewer);
        if (a == null) return UiColors.TEXT_DIM;
        return switch (a) {
            case ATTACK  -> UiColors.TEXT_WARN;
            case FLEE    -> UiColors.ACCENT;
            case NOTHING -> UiColors.TEXT_DIM;
            case ALLY    -> ATTITUDE_FRIENDLY;
        };
    }

    private static String stateOfMindLabel(Mob m) {
        if (m.behavior == Mob.Behavior.PLAYER) return "(this is you)";
        if (m.stateOfMind == null) return null;
        String prefix = switch (m.stateOfMind) {
            case ASLEEP         -> "Asleep";
            case AWAKE          -> "Awake";
            case SEEKING_HIDING -> "Seeking cover";
            case HIDING         -> "Hiding";
            case FOLLOWING      -> "Following";
        };
        String suffix = m.intent == null ? "" : switch (m.intent) {
            case IDLE               -> "";
            case WANDERING          -> " — wandering";
            case PURSUING           -> " — pursuing a foe";
            case CHASING_LAST_KNOWN -> " — chasing last seen";
            case SHOOTING           -> " — taking aim";
            case KITING             -> " — keeping range";
            case FLEEING            -> " — fleeing";
            case FOLLOWING_LEADER   -> " — following its leader";
        };
        return prefix + suffix;
    }

    private static String range(MinMax mm) {
        if (mm == null || (mm.min() == 0 && mm.max() == 0)) return "0";
        return mm.min() == mm.max() ? Integer.toString(mm.min())
                                    : mm.min() + "-" + mm.max();
    }

    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                if (encyclopedia != null && lookedTile != null
                        && tileInfoBtn.contains(vx, vy)) {
                    tileInfoPressed = true; return true;
                }
                if (encyclopedia != null && lookedMob != null
                        && mobInfoBtn.contains(vx, vy)) {
                    mobInfoPressed = true; return true;
                }
                for (int i = 0; i < buffIconRects.size(); i++) {
                    if (buffIconRects.get(i).contains(vx, vy)) {
                        buffIconPressed = i;
                        return true;
                    }
                }
                // Tap outside the look window acts like Back — closes
                // look mode. Matches the V2 modal-dismissal contract used
                // by the inventory, encyclopaedia, and character popups.
                if (!window.contains(vx, vy)) {
                    if (lookMode != null) lookMode.toggle();
                    return true;
                }
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                if (tileInfoPressed) {
                    tileInfoPressed = false;
                    if (encyclopedia != null
                            && tileInfoBtn.contains(vx, vy)
                            && lookedTile != null) {
                        Tile t = lookedTile;
                        // Looking has been "consumed" — exit look mode so
                        // back from the encyclopaedia returns to the
                        // world view, not a re-armed look popup.
                        if (lookMode != null) lookMode.toggle();
                        encyclopedia.openTo(t);
                    }
                    return true;
                }
                if (mobInfoPressed) {
                    mobInfoPressed = false;
                    if (encyclopedia != null
                            && mobInfoBtn.contains(vx, vy)
                            && lookedMob != null) {
                        // Mob ids in V2Encyclopedia are stored as the mob
                        // type string (matching MobRegistry.knownTypes).
                        String mobType = lookedMob.mobType;
                        if (lookMode != null) lookMode.toggle();
                        if (mobType != null) {
                            encyclopedia.openTo(mobType);
                        }
                    }
                    return true;
                }
                if (buffIconPressed >= 0) {
                    int idx = buffIconPressed;
                    buffIconPressed = -1;
                    if (encyclopedia != null
                            && idx < buffIconRects.size()
                            && idx < buffIconTypes.size()
                            && buffIconRects.get(idx).contains(vx, vy)) {
                        Buff.BuffType type = buffIconTypes.get(idx);
                        if (lookMode != null) lookMode.toggle();
                        if (type != null) encyclopedia.openTo(type);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean keyDown(int keycode) {
                if (!isOpen()) return false;
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    if (lookMode != null) lookMode.toggle();
                    return true;
                }
                return false;
            }
        };
    }
}
