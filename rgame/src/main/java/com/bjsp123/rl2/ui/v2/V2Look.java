package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.input.LookMode;
import com.bjsp123.rl2.logic.ItemNames;
import com.bjsp123.rl2.logic.ItemStats;
import com.bjsp123.rl2.logic.MobStats;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.StatBlock;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.world.render.IconSprites;
import com.bjsp123.rl2.model.Buff;

import java.util.ArrayList;
import java.util.List;

/** Compact look popup: terrain, floor item, mob, plus direct info buttons. */
public final class V2Look implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    private static final float HEADER_H = 38f;
    private static final float PAD = 12f;
    private static final float INFO = 24f;
    private static final float SECTION_GAP = 8f;
    private static final float DETAIL_INDENT = 12f;
    private static final float BUFF_ICON_SZ = 14f;
    private static final float BUFF_ICON_GAP = 2f;
    private static final int INFO_TERRAIN = 1;
    private static final int INFO_ITEM = 2;
    private static final int INFO_MOB = 3;

    private final UiCtx ctx;
    private LookMode lookMode;
    private Level level;
    private V2Encyclopedia encyclopedia;
    @SuppressWarnings("unused")
    private V2BuffInfo buffInfo;

    private final Rect window = new Rect();
    private final Rect terrainInfoBtn = new Rect();
    private final Rect itemInfoBtn = new Rect();
    private final Rect mobInfoBtn = new Rect();
    private int pressedInfo;

    private final List<Rect> buffHitRects = new ArrayList<>();
    private final List<Buff.BuffType> buffHitTypes = new ArrayList<>();
    private int pressedBuff = -1;

    private Tile lookedTile;
    private Item lookedItem;
    private Mob lookedMob;
    private int cx, cy;

    private final List<Section> sections = new ArrayList<>();

    public V2Look(UiCtx ctx) { this.ctx = ctx; }

    public void setLookMode(LookMode lm)            { this.lookMode = lm; }
    public void setLevel(Level lvl)                 { this.level = lvl; }
    public void setEncyclopedia(V2Encyclopedia enc) { this.encyclopedia = enc; }
    public void setBuffInfo(V2BuffInfo bi)          { this.buffInfo = bi; }

    public boolean isOpen() {
        return lookMode != null && lookMode.isPanelVisible();
    }

    @Override
    public void renderSelf() {
        if (!isOpen()) return;
        captureLookedAt();
        buildSections();
        layoutRects();
        renderShapesPass();
        renderTextPass();
    }

    private void captureLookedAt() {
        lookedTile = null;
        lookedItem = null;
        lookedMob = null;
        cx = cy = -1;

        Point cursor = lookMode != null ? lookMode.cursor() : null;
        if (cursor == null || level == null) return;
        cx = (int) Math.floor(cursor.x());
        cy = (int) Math.floor(cursor.y());
        if (!inBounds()) return;

        lookedTile = level.tiles[cx][cy];
        if (level.items != null) {
            for (Item it : level.items) {
                if (it == null || it.location == null) continue;
                if ((int) Math.floor(it.location.x()) == cx
                        && (int) Math.floor(it.location.y()) == cy) {
                    lookedItem = it;
                    break;
                }
            }
        }
        if (level.mobs != null) {
            for (Mob m : level.mobs) {
                if (m == null || m.position == null) continue;
                if (m.position.tileX() == cx && m.position.tileY() == cy) {
                    lookedMob = m;
                    break;
                }
            }
        }
    }

    private boolean inBounds() {
        return level != null && cx >= 0 && cy >= 0
                && cx < level.width && cy < level.height;
    }

    private void buildSections() {
        sections.clear();
        if (!inBounds()) {
            sections.add(new Section(TextCatalog.get("ui.look.noTile"), INFO_TERRAIN));
            return;
        }

        sections.add(new Section(floorSummary(), INFO_TERRAIN));

        if (lookedItem != null) {
            Section item = new Section(ItemNames.displayName(lookedItem, null), INFO_ITEM);
            String detail = itemSummary(lookedItem);
            if (!detail.isEmpty()) item.details.add(detail);
            sections.add(item);
        }

        if (lookedMob != null) {
            Section mob = new Section(mobName(lookedMob), INFO_MOB);
            Mob player = TurnSystem.findPlayer(level);
            if (player != null && player != lookedMob) {
                mob.details.add(TextCatalog.format("ui.look.mobHitRates",
                        TextCatalog.vars("player", playerName(player),
                                "toHit", percent(MobStats.hitChance(player, lookedMob)),
                                "beHit", percent(MobStats.hitChance(lookedMob, player)))));
            }
            StatBlock s = lookedMob.effectiveStats();
            if (!s.damage.isZero()) {
                mob.details.add(TextCatalog.format("ui.look.mobMelee",
                        TextCatalog.vars("damage", range(s.damage))));
            }
            if (!s.rangedDamage.isZero()) {
                String type = lookedMob.rangedDamageType == Mob.RangedDamageType.PHYSICAL
                        ? TextCatalog.get("ui.look.damageType.physical")
                        : TextCatalog.get("ui.look.damageType.magic");
                mob.details.add(TextCatalog.format("ui.look.mobRanged",
                        TextCatalog.vars("type", type, "damage", range(s.rangedDamage))));
            }
            String state = compactMobState(lookedMob, player);
            if (!state.isEmpty()) mob.details.add(state);
            if (lookedMob.inventory != null) {
                List<Item> gear = lookedMob.inventory.allEquipped();
                if (!gear.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int gi = 0; gi < gear.size(); gi++) {
                        if (gi > 0) sb.append(" · ");
                        sb.append(ItemNames.displayName(gear.get(gi), null));
                    }
                    mob.details.add(TextCatalog.format("ui.look.mobEquipment",
                            TextCatalog.vars("items", sb.toString())));
                }
            }
            if (lookedMob.buffs != null) {
                for (Buff b : lookedMob.buffs) {
                    if (b == null || b.type == null) continue;
                    TextureRegion r = BuffIcons.regionFor(b.type);
                    if (r != null) { mob.icons.add(r); mob.iconTypes.add(b.type); }
                }
            }
            sections.add(mob);
        }
    }

    private void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(430f, vw - 28f);
        float textW = Math.max(20f, winW - PAD * 2f - INFO - 8f);
        float detailW = Math.max(20f, winW - PAD * 2f - DETAIL_INDENT);
        float contentH = measureSections(textW, detailW) + PAD * 2f;
        float winH = Math.min(vh - 92f, HEADER_H + contentH);
        window.set((vw - winW) * 0.5f, vh - 78f - winH, winW, winH);

        terrainInfoBtn.set(0, 0, 0, 0);
        itemInfoBtn.set(0, 0, 0, 0);
        mobInfoBtn.set(0, 0, 0, 0);

        float y = window.top() - HEADER_H - PAD;
        for (Section sec : sections) {
            if (sec.infoKind != 0) {
                infoRect(sec.infoKind).set(window.right() - PAD - INFO,
                        y - INFO + 5f, INFO, INFO);
            }
            y -= sec.height();
        }
    }

    private float measureSections(float titleW, float detailW) {
        float lineH = ctx.lineH();
        float total = 0f;
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            sec.titleBlock = TextDraw.block(ctx.fontRegular, sec.title,
                    titleW, 2, lineH);
            sec.detailBlocks.clear();
            for (String detail : sec.details) {
                sec.detailBlocks.add(TextDraw.block(ctx.fontRegular, detail,
                        detailW, 2, lineH));
            }
            total += sec.height();
            if (i < sections.size() - 1) total += SECTION_GAP;
        }
        return total;
    }

    private Rect infoRect(int kind) {
        return switch (kind) {
            case INFO_TERRAIN -> terrainInfoBtn;
            case INFO_ITEM -> itemInfoBtn;
            case INFO_MOB -> mobInfoBtn;
            default -> terrainInfoBtn;
        };
    }

    private void renderShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
        s.setColor(UIVars.BORDER_MID);
        s.rect(window.x + 6f, window.top() - HEADER_H, window.w - 12f, 1f);

        if (encyclopedia != null && lookedTile != null) {
            ButtonChrome.shape(ctx, terrainInfoBtn, pressedInfo == INFO_TERRAIN,
                    false, false, UIVars.BTN_BG);
        }
        if (encyclopedia != null && lookedItem != null) {
            ButtonChrome.shape(ctx, itemInfoBtn, pressedInfo == INFO_ITEM,
                    false, false, UIVars.BTN_BG);
        }
        if (encyclopedia != null && lookedMob != null) {
            ButtonChrome.shape(ctx, mobInfoBtn, pressedInfo == INFO_MOB,
                    false, false, UIVars.BTN_BG);
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderTextPass() {
        buffHitRects.clear();
        buffHitTypes.clear();
        ctx.batch.begin();
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get("ui.look.title"),
                window.cx(), window.top() - ctx.headerLineH() * 0.75f);

        float y = window.top() - HEADER_H - PAD;
        float textLeft = window.x + PAD;
        float textRight = window.right() - PAD - INFO - 4f;
        TextureRegion infoIcon = IconSprites.regionFor(IconSprites.Icon.INFO);
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    sec.titleBlock, textLeft, y);
            if (sec.infoKind != 0) {
                ButtonChrome.icon(ctx, infoRect(sec.infoKind), infoIcon,
                        pressedInfo == sec.infoKind, false);
            }
            y -= sec.titleBlock.height();
            float detailX = textLeft + DETAIL_INDENT;
            for (TextDraw.TextBlock detail : sec.detailBlocks) {
                TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        detail, detailX, y);
                y -= detail.height();
            }
            if (!sec.icons.isEmpty()) {
                float iconY = y - BUFF_ICON_SZ;
                float iconX = detailX;
                for (int bi = 0; bi < sec.icons.size(); bi++) {
                    ctx.batch.draw(sec.icons.get(bi), iconX, iconY, BUFF_ICON_SZ, BUFF_ICON_SZ);
                    if (bi < sec.iconTypes.size()) {
                        Rect hr = new Rect();
                        hr.set(iconX, iconY, BUFF_ICON_SZ, BUFF_ICON_SZ);
                        buffHitRects.add(hr);
                        buffHitTypes.add(sec.iconTypes.get(bi));
                    }
                    iconX += BUFF_ICON_SZ + BUFF_ICON_GAP;
                }
                y -= sec.iconRowH();
            }
            if (i < sections.size() - 1) y -= SECTION_GAP;
        }
        ctx.batch.end();
    }

    private String floorSummary() {
        String terrain = TextCatalog.getOrDefault("level.theme." + level.theme.name(),
                pretty(level.theme.name())) + " "
                + TextCatalog.getOrDefault("tile." + lookedTile.name(), pretty(lookedTile.name()));
        List<String> features = new ArrayList<>(3);
        if (level.surface != null && level.surface[cx][cy] != null) {
            features.add(describeSurface(level.surface[cx][cy]));
        }
        if (level.vegetation != null && level.vegetation[cx][cy] != null) {
            features.add(describeVegetation(level.vegetation[cx][cy]));
        }
        if (level.cloud != null && level.cloud[cx][cy] != 0) {
            Level.Cloud c = com.bjsp123.rl2.logic.CloudSystem.type(level.cloud[cx][cy]);
            if (c != null) features.add(TextCatalog.getOrDefault(
                    "ui.look.cloud." + c.name(), pretty(c.name())));
        }
        if (features.isEmpty()) {
            return TextCatalog.format("ui.look.floorSummary",
                    TextCatalog.vars("terrain", terrain));
        }
        return TextCatalog.format("ui.look.floorSummaryWith",
                TextCatalog.vars("terrain", terrain, "features", joinFeatures(features)));
    }

    private static String itemSummary(Item it) {
        List<String> bits = new ArrayList<>(4);
        MinMax dmg = itemDamageWithBrand(it);
        if (!dmg.isZero()) {
            bits.add(TextCatalog.format("ui.look.itemDamage",
                    TextCatalog.vars("damage", range(dmg))));
        }
        MinMax arm = ItemStats.effectiveArmorRange(it);
        if (!arm.isZero()) {
            bits.add(TextCatalog.format("ui.look.itemArmor",
                    TextCatalog.vars("armor", range(arm))));
        }
        if (it.brand != null && it.brand.element != null) {
            bits.add(TextCatalog.getOrDefault("ui.look.brandEffect." + it.brand.element.name(),
                    TextCatalog.get("ui.look.brandEffect.generic")));
        } else if (it.throwEffect != null) {
            bits.add(TextCatalog.getOrDefault("item.effect." + it.throwEffect.name(), ""));
        } else if (it.useBehavior != null && it.useBehavior != Item.UseBehavior.NONE) {
            bits.add(TextCatalog.getOrDefault("item.useVerb." + it.useBehavior.name(), ""));
        }
        return String.join("  ", bits);
    }

    private static MinMax itemDamageWithBrand(Item it) {
        MinMax dmg = ItemStats.effectiveDamageRange(it);
        if (it != null && it.brand != null && it.brand.damage > 0) {
            dmg = dmg.plus(new MinMax(it.brand.damage, it.brand.damage));
        }
        return dmg;
    }

    private String mobName(Mob m) {
        String name = m.name != null ? m.name : TextCatalog.get("ui.look.mobFallback");
        return m.characterLevel > 1
                ? TextCatalog.format("ui.look.mobLevelPlain",
                        TextCatalog.vars("name", name, "level", m.characterLevel))
                : name;
    }

    private String compactMobState(Mob m, Mob player) {
        List<String> bits = new ArrayList<>(3);
        if (player != null && player != m) {
            bits.add(attitudeLabel(player, m));
        }
        if (m.behavior == Mob.Behavior.PLAYER) {
            bits.add(TextCatalog.get("ui.look.state.player"));
        } else if (m.stateOfMind != null) {
            bits.add(switch (m.stateOfMind) {
                case ASLEEP -> TextCatalog.get("ui.look.state.asleep");
                case AWAKE -> TextCatalog.get("ui.look.state.awake");
                case SEEKING_HIDING -> TextCatalog.get("ui.look.state.seekingHiding");
                case HIDING -> TextCatalog.get("ui.look.state.hiding");
                case FOLLOWING -> TextCatalog.get("ui.look.state.following");
            });
            String intentLabel = intentLabel(m.intent);
            if (!intentLabel.isEmpty()) bits.add(intentLabel);
        }
        return String.join("  ", bits);
    }

    private static String intentLabel(Mob.Intent intent) {
        if (intent == null) return "";
        return switch (intent) {
            case IDLE            -> TextCatalog.get("ui.look.intent.idle");
            case CONSIDERING     -> TextCatalog.get("ui.look.intent.considering");
            case WANDERING       -> TextCatalog.get("ui.look.intent.wandering");
            case PURSUING        -> TextCatalog.get("ui.look.intent.pursuing");
            case CHASING_LAST_KNOWN -> TextCatalog.get("ui.look.intent.chasing");
            case SHOOTING        -> TextCatalog.get("ui.look.intent.shooting");
            case KITING          -> TextCatalog.get("ui.look.intent.kiting");
            case FLEEING         -> TextCatalog.get("ui.look.intent.fleeing");
            case FOLLOWING_LEADER -> TextCatalog.get("ui.look.intent.following");
            case USING_ABILITY   -> TextCatalog.get("ui.look.intent.usingAbility");
            case USING_ITEM      -> TextCatalog.get("ui.look.intent.usingItem");
            case RELOADING       -> TextCatalog.get("ui.look.intent.reloading");
        };
    }

    private static String playerName(Mob player) {
        if (player.name != null && !player.name.isEmpty()) return player.name;
        return player.characterClass != null ? player.characterClass.displayName()
                : TextCatalog.get("eventlog.fallback.adventurer");
    }

    private static String attitudeLabel(Mob viewer, Mob target) {
        com.bjsp123.rl2.logic.MobSystem.Attitude a =
                com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(target, viewer);
        if (a == null) return "";
        return switch (a) {
            case ATTACK -> TextCatalog.get("ui.look.attitude.attack");
            case FLEE -> TextCatalog.get("ui.look.attitude.flee");
            case NOTHING -> TextCatalog.get("ui.look.attitude.nothing");
            case ALLY -> TextCatalog.get("ui.look.attitude.ally");
        };
    }

    private static String describeSurface(Level.Surface s) {
        return TextCatalog.get("ui.look.surface." + s.name().toLowerCase());
    }

    private static String describeVegetation(Level.Vegetation v) {
        return TextCatalog.get("ui.look.terrain." + v.name().toLowerCase());
    }

    private static String joinFeatures(List<String> xs) {
        if (xs.isEmpty()) return "";
        if (xs.size() == 1) return xs.get(0);
        if (xs.size() == 2) {
            return TextCatalog.format("ui.list.two",
                    TextCatalog.vars("a", xs.get(0), "b", xs.get(1)));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(i == xs.size() - 1
                    ? TextCatalog.get("ui.list.finalSep")
                    : TextCatalog.get("ui.list.sep"));
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    private static int percent(double p) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, p)) * 100.0);
    }

    private static String range(MinMax mm) {
        if (mm == null || (mm.min() == 0 && mm.max() == 0)) return "0";
        return mm.min() == mm.max() ? Integer.toString(mm.min())
                : mm.min() + "-" + mm.max();
    }

    private static String pretty(String raw) {
        return raw == null ? "" : raw.toLowerCase().replace('_', ' ');
    }

    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                pressedInfo = 0;
                pressedBuff = -1;
                if (encyclopedia != null && lookedTile != null && terrainInfoBtn.contains(vx, vy)) {
                    pressedInfo = INFO_TERRAIN;
                    return true;
                }
                if (encyclopedia != null && lookedItem != null && itemInfoBtn.contains(vx, vy)) {
                    pressedInfo = INFO_ITEM;
                    return true;
                }
                if (encyclopedia != null && lookedMob != null && mobInfoBtn.contains(vx, vy)) {
                    pressedInfo = INFO_MOB;
                    return true;
                }
                if (encyclopedia != null) {
                    for (int bi = 0; bi < buffHitRects.size(); bi++) {
                        if (buffHitRects.get(bi).contains(vx, vy)) {
                            pressedBuff = bi;
                            return true;
                        }
                    }
                }
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
                int info = pressedInfo;
                int buff = pressedBuff;
                pressedInfo = 0;
                pressedBuff = -1;
                if (info == INFO_TERRAIN && terrainInfoBtn.contains(vx, vy) && lookedTile != null) {
                    Tile t = lookedTile;
                    if (lookMode != null) lookMode.toggle();
                    encyclopedia.openTo(t);
                    return true;
                }
                if (info == INFO_ITEM && itemInfoBtn.contains(vx, vy) && lookedItem != null) {
                    String type = lookedItem.type;
                    if (lookMode != null) lookMode.toggle();
                    encyclopedia.openTo(type);
                    return true;
                }
                if (info == INFO_MOB && mobInfoBtn.contains(vx, vy) && lookedMob != null) {
                    String type = lookedMob.mobType;
                    if (lookMode != null) lookMode.toggle();
                    encyclopedia.openTo(type);
                    return true;
                }
                if (buff >= 0 && buff < buffHitRects.size()
                        && buffHitRects.get(buff).contains(vx, vy)
                        && buff < buffHitTypes.size()) {
                    Buff.BuffType bt = buffHitTypes.get(buff);
                    if (lookMode != null) lookMode.toggle();
                    encyclopedia.openTo(bt);
                    return true;
                }
                return true;
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

    private static final class Section {
        final String title;
        final int infoKind;
        final List<String> details = new ArrayList<>();
        final List<TextDraw.TextBlock> detailBlocks = new ArrayList<>();
        final List<TextureRegion> icons = new ArrayList<>();
        final List<Buff.BuffType> iconTypes = new ArrayList<>();
        TextDraw.TextBlock titleBlock;

        private Section(String title, int infoKind) {
            this.title = title == null ? "" : title;
            this.infoKind = infoKind;
        }

        float iconRowH() {
            return icons.isEmpty() ? 0f : BUFF_ICON_SZ + BUFF_ICON_GAP;
        }

        float height() {
            float h = titleBlock == null ? 0f : titleBlock.height();
            for (TextDraw.TextBlock block : detailBlocks) h += block.height();
            h += iconRowH();
            return h;
        }
    }
}
