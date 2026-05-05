package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.bjsp123.rl2.world.anim.MobAnimState;
import com.bjsp123.rl2.logic.TurnSystem;

/**
 * Tile renderer. Atlas knowledge lives in the per-sheet sprite classes, not here:
 * <ul>
 *   <li>Terrain ({@code sprites/terrain_<theme>.png}) — {@link TileSprites}.</li>
 *   <li>Mobs ({@code sprites/mobs_simple.png}) — {@link MobSprites}.</li>
 *   <li>Surfaces / vegetation / fire — {@link SurfaceSprites}.</li>
 *   <li>Items / staff / gems — {@link ItemSprites}, {@link GemSprites}.</li>
 * </ul>
 * This class consumes those regions to compose four render passes:
 * <ol>
 *   <li><b>Floors + chasm</b> (any order). Base floor tile, stair underlays, chasm
 *       black fill, and the "dripping edge" chasm tile pulled from the north neighbor.</li>
 *   <li><b>Surface pass</b> (water / blood / oil / ice). Lifted out of the per-cell
 *       loop so the mask shader binds exactly once.</li>
 *   <li><b>Per-cell content</b>, north→south, west→east. Floor-edge shadow → wall /
 *       door body + wall-base shadow + rear-corner shadows → rear vegetation → lamp →
 *       items → mobs → front vegetation (tucks over mob feet) → fx. Fx run even on
 *       unexplored tiles so the player sees their own projectiles.</li>
 *   <li><b>Wall + door tops</b>, north→south. Overhangs and internal-wall caps painted
 *       on the cell to the NORTH of each wall / door so tall scenery clips anything
 *       behind it.</li>
 * </ol>
 * Fog of war then runs once as a soft-edge overlay with an additive lamp-light layer.
 */
public class DefaultLevelRenderer implements LevelRenderer {

    /** Liquid tile size in source pixels — matches {@link SurfaceSprites#LIQUID_TILE}.
     *  The shader's per-cell UV math divides world coordinates by this to get a UV
     *  that wraps once per tile. */
    private static final float WATER_TEX_SIZE = SurfaceSprites.LIQUID_TILE;
    /** Speed of liquid texture drift. Slow on purpose — water/blood/oil should look
     *  like a gentle current, not a river. */
    private static final float WATER_SCROLL_PX_PER_SEC = 1.2f;

    private static final int CELL = 16;
    /** Pixels above the cell's bottom edge where a mob's baseline sits — its feet end here
     *  and the sprite extends upward from this line. */
    private static final int ENTITY_Y_OFFSET = 2;

    /** Uniform on-screen size for every mob's VISIBLE silhouette (not including blank padding). */
    private static final int MOB_VISIBLE_W = 16;
    private static final int MOB_VISIBLE_H = 20;
    /** Extra Y lift (in y-up world pixels) applied to the player only — pushes the
     *  character a few pixels above the tile's baseline so they read as standing on the
     *  floor rather than embedded in it. NPCs keep the default baseline. */
    private static final float PLAYER_Y_LIFT = 4f;

    /** Minimum radial taps for the silhouette outline at small widths — 8 hits the
     *  cardinals + diagonals every 45°. At thicker widths we add taps proportional
     *  to the perimeter so the outline's outer edge reads as a smooth curve instead
     *  of a chunky 8-pointed star. {@link #ensureOutlineTaps} keeps the cached arrays
     *  in sync with {@link com.bjsp123.rl2.ui.skin.MobOutline#width()}. */
    private static final int OUTLINE_MIN_TAPS = 8;
    private static int     outlineTaps     = OUTLINE_MIN_TAPS;
    private static float[] outlineDx       = new float[OUTLINE_MIN_TAPS];
    private static float[] outlineDy       = new float[OUTLINE_MIN_TAPS];
    /** Last width the cached arrays were built for. {@code NaN} forces a rebuild on
     *  the first call so {@link #outlineDx} / {@link #outlineDy} hold valid values. */
    private static float   outlineCachedW  = Float.NaN;
    static {
        ensureOutlineTaps(0f); // populate the min-tap arrays
    }

    /** Refresh the cached unit-circle offset arrays for {@code outlineW} (in world
     *  pixels). Tap count is {@code max(MIN, ceil(2π * outlineW))} so adjacent taps
     *  sit ≈1 world pixel apart on the dilation circle — at width 0.6 we keep 8 taps
     *  (the original 45° spread); at width 2 we use 13; at width 3 we use 19. */
    private static void ensureOutlineTaps(float outlineW) {
        if (outlineW == outlineCachedW) return;
        int taps = Math.max(OUTLINE_MIN_TAPS,
                            (int) Math.ceil(2.0 * Math.PI * Math.max(0f, outlineW)));
        if (taps != outlineTaps || outlineDx.length != taps) {
            outlineTaps = taps;
            outlineDx = new float[taps];
            outlineDy = new float[taps];
        }
        for (int i = 0; i < taps; i++) {
            double a = i * Math.PI * 2.0 / taps;
            outlineDx[i] = (float) Math.cos(a);
            outlineDy[i] = (float) Math.sin(a);
        }
        outlineCachedW = outlineW;
    }
    /** Width of the thin shadow strip painted along the wall-facing edges of floor tiles. The
     *  strip uses a 4-pixel alpha gradient texture (opaque at the wall, fading into the floor). */
    private static final float FLOOR_SHADOW_PX    = 3f;
    /** Height of the gradient shadow painted across the base of a wall with floor to its south. */
    private static final float WALL_BASE_SHADOW_PX = 6f;

    // Per-theme shadow tints — cached so shadow passes don't allocate a new Color per cell.
    // Crystal: pure black; Concrete: warm sodium-amber bias; Straightforward: cool cyan bias.
    private static final Color SHADOW_BLACK   = new Color(0f, 0f, 0f, 0.65f);
    /** Black with a faint warm brown bias — concrete tunnels and brutalist interiors
     *  sit in dim sodium-amber light, so shadows lean warm rather than neutral.
     *  Used for the CONCRETE theme. */
    private static final Color SHADOW_CONCRETE = new Color(0.06f, 0.04f, 0.02f, 0.65f);
    /** Black with a hint of cyan — paired with the STRAIGHTFORWARD theme so its
     *  cool-lit terrain reads with shadows that bias toward the same hue rather
     *  than neutral grey. */
    private static final Color SHADOW_STRAIGHTFORWARD = new Color(0f, 0.05f, 0.08f, 0.65f);

    /**
     * Tint for every shadow pass (floor-edge, wall-base, rear-corner) on this level. One
     * switch, one place to adjust per-theme lighting.
     */
    private static Color getShadowColor(Level level) {
        return switch (level.theme) {
            case CRYSTAL         -> SHADOW_BLACK;
            case CONCRETE        -> SHADOW_CONCRETE;
            case STRAIGHTFORWARD -> SHADOW_STRAIGHTFORWARD;
        };
    }

    /**
     * Width of the "rear corner shadows" — thin vertical bands painted along the left or right
     * edge of a wall tile that sits directly above a floor and has a perpendicular wall coming
     * out of the diagonally-down corner. Visually fakes the contact shadow of the rear-corner
     * wall against the side of the horizontal wall section.
     */
    private static final float WALL_REAR_CORNER_SHADOW_PX = 3f;
    /** Height of the rear-corner shadow band, measured from the bottom of the wall cell
     *  upward. The band covers only the lower portion of the wall — the top 5 pixels are
     *  left clean so the wall cap doesn't look smothered in shadow. */
    private static final float WALL_REAR_CORNER_SHADOW_H  = 11f;

    /** Shadow oval pinned under each mob at the baseline. Height is fixed; width tracks the
     *  mob's silhouette plus {@link #SHADOW_EXTRA_W} so the shadow always pokes slightly
     *  past the mob's feet on both sides (reads as planted on the floor, not perched atop it). */
    private static final int   SHADOW_H        = 6;
    private static final int   SHADOW_EXTRA_W  = 4;
    private static final float SHADOW_MAX_ALPHA = 0.45f;
    /** Size + peak-alpha of the ellipse cast under a lamp tile at its base. Lamps are static
     *  light sources that loom taller than any mob, so their contact shadow is wider, a touch
     *  taller, and noticeably darker than the mob shadow — otherwise the lamp reads as
     *  floating above the floor. */
    private static final int   LAMP_SHADOW_W         = 30;
    private static final int   LAMP_SHADOW_H         = 7;
    private static final float LAMP_SHADOW_MAX_ALPHA = 0.80f;

    private SpriteBatch     batch;
    private BitmapFont      font;
    /** Per-cell terrain regions for the active level's theme — borrowed from
     *  {@link TileSprites#regionsFor} at the top of every {@link #render}. */
    private TextureRegion[] tiles;
    /** Per-frame ornament regions for the active theme, borrowed from {@link TileSprites}
     *  at the top of {@link #render} so draw helpers don't have to look the level up. */
    private TextureRegion currentSmallStatue;
    private TextureRegion currentLargeStatue;
    private TextureRegion currentLampOrnament;
    private TextureRegion currentStairsUp;
    private TextureRegion currentStairsDown;
    /** Mob the player is currently inspecting via look mode. When non-null, the renderer
     *  overlays its state-of-mind above its tile and draws this mob's attitude toward
     *  every other visible mob. {@link com.bjsp123.rl2.screen.PlayScreen} updates it each
     *  frame from {@code LookMode.mobAtCursor()}; null clears the overlay. */
    private Mob lookedAtMob;
    public void setLookedAtMob(Mob m) { this.lookedAtMob = m; }
    private Texture         waterTex;
    private Texture         bloodTex;
    private Texture         oilTex;
    private Texture         iceTex;

    /** Two grass sprite variants pulled from surfaces.png (atlas cells (5,0) and (6,0) at
     *  {@value #VEG_SPRITE_PX} px). The A/B pick per cell is hash-driven by
     *  {@link #vegRearVariantBit} so adjacent grass tiles read as a mix instead of a stamped
     *  uniform. Same scheme for mushrooms (rows 1) and trees (rows 2). */
    private Texture         grassTexA, grassTexB;
    private Texture         mushroomTexA, mushroomTexB;
    private Texture         treeTexA, treeTexB;
    /** Two painted 8-frame fire animation sheets — each 256×48 (8 frames of 32×48). Held
     *  here only for {@link #dispose()}; the per-frame draw machinery lives in
     *  {@link FxRenderer}, which receives both as constructor args. Optional: a missing
     *  file is loaded as null and FxRenderer draws nothing for that variant. */
    private Texture         fire1Tex, fire2Tex;
    /** West-facing + east-facing (mirrored) pairs for the three small critters + the player. */
    /** Player class poses, keyed by {@link CharacterClass}. */
    private Sprite[]        warriorFacing, mageFacing, rogueFacing;
    /** Per-species facing-pair sprites, keyed by mob-type string. Populated in
     *  {@link #create()} from every row in {@link com.bjsp123.rl2.logic.MobRegistry}.
     *  Replaces the legacy 25 hand-named per-species fields. */
    private final java.util.Map<String, Sprite[]> speciesFacing = new java.util.HashMap<>();
    private Texture         whiteTex;
    private Texture         shadowTex;
    /** Dedicated soft-oval shadow for lamp tiles — bigger and darker than the mob shadow. */
    private Texture         lampShadowTex;
    private Texture         wallBaseShadowTex;
    /** 1×4 gradient: alpha 0 at image-top, 255 at image-bottom. Used for N/S floor shadows. */
    private Texture         floorShadowVertTex;
    /** 4×1 gradient: alpha 255 at image-left, 0 at image-right. Used for E/W floor shadows. */
    private Texture         floorShadowHorzTex;
    /** 16-way alpha mask tiles lifted from the bottom strip of surfaces.png. Index 0 is a
     *  synthesized solid-white tile (the art leaves variant 0 fully transparent). */
    private Texture[]       surfaceMaskTex;
    /** Custom shader that draws a scrolling surface modulated by a per-cell alpha mask. */
    private ShaderProgram   surfaceMaskShader;
    private float           waterTime;
    /** Reference to the world for cross-level lookups (currently used by the stair labels
     *  to read the destination level's depth + side). Set via {@link #setWorld}; null if
     *  the renderer is being used outside a full game (e.g. tests). */
    private com.bjsp123.rl2.model.World world;
    /** In-world animator providing per-mob {@link com.bjsp123.rl2.world.anim.MobAnimState}.
     *  Set via {@link #setAnimator} during PlayScreen init; required for all mob draws. */
    private com.bjsp123.rl2.world.anim.Animator animator;
    /** Real-time accumulator for the stair-label fade animation, in seconds. Bumped each
     *  frame from {@link com.badlogic.gdx.Gdx#graphics}. */
    private float           stairLabelTime;
    private TextureRegion   whiteRegion;
    private final FogOverlay fog = new FogOverlay();

    /** All effect/particle/fire/sleep-Z rendering lives here. Constructed lazily in
     *  {@link #create()} once the underlying batch + font + fire textures are loaded. */
    private FxRenderer fxRenderer;

    /** Cached per-cell item index. Rebuilt lazily when {@link #indexesDirty} is true —
     *  items only move on game ticks (pickup, drop, throw) so between-tick frames can
     *  reuse the prior frame's index verbatim. */
    private Map<Long, List<Item>> cachedItemsByCell;
    /** Cached per-cell mob index. Same caching scheme as {@link #cachedItemsByCell} —
     *  mobs' positions only change on ticks. The flicker / fade of a dying mob is a
     *  frame-counter bump inside {@link Mob}, not a position change, so it doesn't need
     *  the index rebuilt. */
    private Map<Long, List<Mob>>  cachedMobsByCell;
    /** When true, {@link #cachedItemsByCell} and {@link #cachedMobsByCell} need to be
     *  repopulated from the current level. Set by {@link #markDirty()} and also on the
     *  very first render after creation. Fx are NOT cached — they get added / removed
     *  every frame via {@code advanceEffects}, so the fx index is rebuilt per frame. */
    private boolean indexesDirty = true;

    /**
     * One drawable entity frame.
     * <ul>
     *   <li>{@code w, h} — the source frame's total native pixel dimensions.</li>
     *   <li>{@code visibleW, visibleH} — pixel size of the non-transparent silhouette
     *       (bounding box of all opaque pixels). Mobs scale X by {@code visibleW} and Y by
     *       {@code visibleH} independently so every mob's silhouette lands at exactly
     *       {@link #MOB_VISIBLE_W} × {@link #MOB_VISIBLE_H} regardless of how much blank
     *       canvas the artist left around the character.</li>
     *   <li>{@code visibleLeft} — source-pixel x of the leftmost opaque column, used to
     *       recentre the silhouette on the tile after scaling (since side padding may be
     *       asymmetric).</li>
     *   <li>{@code yAdjust} — source-pixel offset added to the cell baseline when drawing:
     *       negative pulls the frame down to compensate for blank bottom rows so feet land
     *       on the shared baseline; positive lifts for flying mobs. Scales with the sprite
     *       at draw time.</li>
     * </ul>
     */
    private static final class Sprite {
        final TextureRegion region;
        final int w, h, visibleW, visibleH, visibleLeft, yAdjust;
        /** When true, {@link #drawMobSprite} draws this at its native pixel size instead of
         *  scaling the silhouette to {@link #MOB_VISIBLE_W} × {@link #MOB_VISIBLE_H}. Used
         *  for "large" mobs like the blob whose shape is meant to extend past the tile. */
        final boolean natural;
        Sprite(TextureRegion region, int w, int h,
               int visibleW, int visibleH, int visibleLeft, int yAdjust, boolean natural) {
            this.region = region;
            this.w = w; this.h = h;
            this.visibleW = visibleW; this.visibleH = visibleH;
            this.visibleLeft = visibleLeft; this.yAdjust = yAdjust;
            this.natural = natural;
        }
    }

    /** Extra pixels a flying mob hovers above the shared ground baseline. */
    private static final int FLYING_HOVER_PX = 4;

    @Override
    public void create() {
        batch = new SpriteBatch();
        font  = new BitmapFont();

        // Player class poses.
        warriorFacing          = mobsFacingPair(CharacterClass.WARRIOR);
        mageFacing             = mobsFacingPair(CharacterClass.MAGE);
        rogueFacing            = mobsFacingPair(CharacterClass.ROGUE);
        // Every NPC species — driven entirely by the registry. Flying species
        // (bat, ghost, …) get the floating y-lift; everyone else stands flat
        // on their tile. The registry is populated from {@code mobs.csv} at
        // bootstrap, so this loop picks up new species without code edits.
        for (String type : com.bjsp123.rl2.logic.MobRegistry.knownTypes()) {
            com.bjsp123.rl2.logic.MobDefinition def =
                    com.bjsp123.rl2.logic.MobRegistry.get(type);
            Sprite[] pair = (def != null && def.flying)
                    ? mobsFloatingFacingPair(type)
                    : mobsFacingPair(type);
            speciesFacing.put(type, pair);
        }

        Pixmap wp = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        wp.setColor(Color.WHITE);
        wp.fill();
        whiteTex = new Texture(wp);
        wp.dispose();
        whiteRegion = new TextureRegion(whiteTex);

        shadowTex     = makeShadowTexture(32, 8, SHADOW_MAX_ALPHA);
        lampShadowTex = makeShadowTexture(40, 10, LAMP_SHADOW_MAX_ALPHA);
        wallBaseShadowTex = makeWallBaseShadowTexture();
        floorShadowVertTex = makeAlphaGradient(1, 4, /*horizontal*/ false);
        floorShadowHorzTex = makeAlphaGradient(4, 1, /*horizontal*/ true);

        // Surface / vegetation / mask / fire textures all come from SurfaceSprites
        // — the single owner of surfaces.png and the two fire sheets across the whole
        // game. We borrow Texture references; SurfaceSprites.disposeShared() releases
        // them, never this dispose().
        surfaceMaskTex = new Texture[com.bjsp123.rl2.world.render.SurfaceSprites.MASK_VARIANTS];
        for (int i = 0; i < surfaceMaskTex.length; i++) {
            surfaceMaskTex[i] = com.bjsp123.rl2.world.render.SurfaceSprites.maskTexture(i);
        }
        surfaceMaskShader = buildSurfaceMaskShader();

        waterTex = com.bjsp123.rl2.world.render.SurfaceSprites.liquidTexture(
                com.bjsp123.rl2.model.Level.Surface.WATER);
        bloodTex = com.bjsp123.rl2.world.render.SurfaceSprites.liquidTexture(
                com.bjsp123.rl2.model.Level.Surface.BLOOD);
        oilTex   = com.bjsp123.rl2.world.render.SurfaceSprites.liquidTexture(
                com.bjsp123.rl2.model.Level.Surface.OIL);
        iceTex   = com.bjsp123.rl2.world.render.SurfaceSprites.liquidTexture(
                com.bjsp123.rl2.model.Level.Surface.ICE);
        grassTexA    = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureA(
                com.bjsp123.rl2.model.Level.Vegetation.GRASS);
        grassTexB    = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureB(
                com.bjsp123.rl2.model.Level.Vegetation.GRASS);
        mushroomTexA = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureA(
                com.bjsp123.rl2.model.Level.Vegetation.MUSHROOMS);
        mushroomTexB = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureB(
                com.bjsp123.rl2.model.Level.Vegetation.MUSHROOMS);
        treeTexA = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureA(
                com.bjsp123.rl2.model.Level.Vegetation.TREES);
        treeTexB = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureB(
                com.bjsp123.rl2.model.Level.Vegetation.TREES);
        fire1Tex = com.bjsp123.rl2.world.render.SurfaceSprites.fire1Texture();
        fire2Tex = com.bjsp123.rl2.world.render.SurfaceSprites.fire2Texture();

        fxRenderer = new FxRenderer(batch, font, whiteRegion, fire1Tex, fire2Tex);
    }


    /** Wrap a {@link MobSprites} region in a {@link Sprite} at half source-pixel size —
     *  the sheet is authored at 2× display resolution so a 32-px source cell becomes a
     *  16-px on-screen cell. Sprites are bottom-aligned within their cell block by the
     *  packer ({@code tools/PackMobsSheet.java}), so the silhouette's visible base lands
     *  at the cell-block's bottom edge automatically. {@code yAdjust} adds an extra lift
     *  for flying mobs. Returns {@code null} if {@code region} didn't load. */
    private static Sprite spriteFromRegion(TextureRegion region, int yAdjust) {
        if (region == null) return null;
        int srcW = region.getRegionWidth();
        int srcH = region.getRegionHeight();
        int dispW = srcW / 2, dispH = srcH / 2;
        return new Sprite(region, dispW, dispH, dispW, dispH, 0, yAdjust, /*natural*/ true);
    }

    private Sprite[] mobsFacingPair(String type) {
        return facingPair(spriteFromRegion(MobSprites.regionFor(type), 0));
    }

    private Sprite[] mobsFacingPair(CharacterClass cls) {
        return facingPair(spriteFromRegion(MobSprites.regionFor(cls), 0));
    }

    private Sprite[] mobsFloatingFacingPair(String type) {
        return facingPair(spriteFromRegion(MobSprites.regionFor(type), FLYING_HOVER_PX));
    }

    private static Sprite[] facingPair(Sprite left) {
        if (left == null) return new Sprite[]{ null, null };
        return new Sprite[]{ left, flipHorizontal(left) };
    }


    private static Sprite flipHorizontal(Sprite src) {
        TextureRegion flipped = new TextureRegion(src.region);
        flipped.flip(true, false);
        // Horizontal flip swaps left and right padding, which shifts visibleLeft.
        int flippedLeft = src.w - src.visibleLeft - src.visibleW;
        return new Sprite(flipped, src.w, src.h,
                          src.visibleW, src.visibleH, flippedLeft, src.yAdjust, src.natural);
    }

    // Liquid + vegetation extraction lives in SurfaceSprites — the single owner of
    // surfaces.png-derived Textures. Both kinds (Linear+Repeat liquids, Nearest veg
    // sprites) come back from there as already-built Textures.

    /** Invalidate cached fog + per-cell item/mob indexes. Called by {@code PlayScreen}
     *  after game ticks and level transitions — between ticks we skip both rebuilds. */
    @Override
    public void markDirty() {
        fog.markDirty();
        indexesDirty = true;
    }

    @Override
    public void setWorld(com.bjsp123.rl2.model.World world) {
        this.world = world;
    }

    @Override
    public void setAnimator(com.bjsp123.rl2.world.anim.Animator animator) {
        this.animator = animator;
    }

    @Override
    public void render(Level level, OrthographicCamera camera) {
        fog.createFor(level.width, level.height);
        fog.update(level);
        float dt = Gdx.graphics.getDeltaTime();
        waterTime      += dt;
        stairLabelTime += dt;

        // Bind the tile atlas matching this level's theme. TileSprites falls back to
        // CRYSTAL internally if a theme slipped through without a registered atlas.
        tiles               = TileSprites.regionsFor(level.theme);
        currentSmallStatue  = TileSprites.smallStatue(level.theme);
        currentLargeStatue  = TileSprites.largeStatue(level.theme);
        currentLampOrnament = TileSprites.lampOrnament(level.theme);
        currentStairsUp     = TileSprites.stairsUp(level.theme);
        currentStairsDown   = TileSprites.stairsDown(level.theme);

        // Bucket every item / mob / effect into the cell it will draw in. Items and mobs
        // only shift on ticks (pickup / drop / throw / step), so their indexes are cached
        // across frames and rebuilt only when the PlayScreen flags {@link #indexesDirty}
        // via {@link #markDirty}. Effects are volatile — added / removed every frame by
        // {@code TurnSystem.advanceEffects} — so the fx index is rebuilt per frame.
        // Effects that span two tiles (missiles, thrown items) anchor at their SOUTHMOST
        // endpoint so the N→S loop paints them after every mob along their path.
        if (indexesDirty || cachedItemsByCell == null || cachedMobsByCell == null) {
            cachedItemsByCell = indexItemsByCell(level);
            cachedMobsByCell  = indexMobsByCell(level);
            indexesDirty = false;
        }
        Map<Long, List<Item>>   itemsByCell = cachedItemsByCell;
        Map<Long, List<Mob>>    mobsByCell  = cachedMobsByCell;
        Map<Long, List<Effect>> fxByCell    = indexEffectsByCell(animator.stage);

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.setColor(Color.WHITE);

        // FOUR PASSES over the level:
        //   Pass 1 — FLOORS + CHASM. Every floor, stair glyph, chasm black fill, and chasm
        //            edge tile lands first so the rest of the scene can layer on top without
        //            ever peeking through to a floorless background.
        //   Pass 2 — SURFACES (water/blood/oil). Lifted out of the per-cell pass so the
        //            mask shader is bound exactly once for all surface cells instead of
        //            thrashing on/off per-cell. Sits between floors and shadows so liquid
        //            visually rests on the floor and tucks under wall-base shadows.
        //   Pass 3 — PER-CELL CONTENT (N→S). Shadows / wall body / vegetation / lamps /
        //            items / mobs / front veg / fx / cloud. Still one-tile-at-a-time so
        //            overlapping cases (grass tucking over feet, tall walls over mobs in
        //            the same cell, etc.) resolve consistently.
        //   Pass 4 — WALL + DOOR TOPS (N→S). Wall overhangs, internal-wall caps, N/S-facing
        //            door overhangs, sideways-door overhangs — every "ceiling" tile painted
        //            on top of the already-laid scene.
        // Fog then runs once after pass 4.
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (!level.explored[x][y]) continue;
                drawFloorAt(level, x, y);
                drawChasmEdgeAt(level, x, y);
            }
        }

        drawSurfacesPass(level);

        for (int y = level.height - 1; y >= 0; y--) {
            for (int x = 0; x < level.width; x++) {
                boolean explored = level.explored[x][y];
                if (explored) {
                    drawFloorEdgeShadowAt(level, x, y);
                    drawWallAt(level, x, y);
                    drawWallBaseShadowAt(level, x, y);
                    drawRearVegetationAt(level, x, y);
                    drawLampAt(level, x, y);
                    drawStairsAt(level, x, y);
                    drawStatueAt(level, x, y);
                    drawItemsAt(level, x, y, itemsByCell);
                    drawMobsAt(level, x, y, mobsByCell);
                    drawFrontVegetationAt(level, x, y);
                }
                // Fx run regardless of explored — magic missiles are intentionally drawn
                // across dark tiles so the player can see their own projectile. Each
                // effect's own draw code decides whether to honor level.visible[].
                drawFxAt(level, x, y, fxByCell);
            }
        }

        for (int y = level.height - 1; y >= 0; y--) {
            for (int x = 0; x < level.width; x++) {
                if (!level.explored[x][y]) continue;
                drawWallOverlayAt(level, x, y);
            }
        }

        // Look-mode annotations layer — drawn above the world but under the fog overlay
        // so unseen tiles still darken normally. Only fires when the player is looking at
        // a mob; otherwise it's a no-op.
        if (lookedAtMob != null) drawLookAnnotations(level);

        fog.render(batch);
        batch.end();
    }

    /**
     * Overlay text annotations driven by {@link #lookedAtMob}. Above the looked-at mob's
     * tile we print its state-of-mind ("asleep" / "awake" / "hiding" / "following"); above
     * every other visible mob we print a single-character attitude marker indicating how
     * the looked-at mob feels about it ({@code !} hostile red, {@code ?} fleeing yellow,
     * {@code ·} neutral grey, {@code ♥} ally green). Cleared when the look cursor leaves
     * the mob (PlayScreen sets {@link #lookedAtMob} back to null).
     */
    private void drawLookAnnotations(Level level) {
        Mob anchor = lookedAtMob;
        if (anchor == null) return;
        int ax = anchor.position.tileX(), ay = anchor.position.tileY();
        if (ax < 0 || ay < 0 || ax >= level.width || ay >= level.height) return;

        // The looked-at mob: state of mind above its tile.
        drawAnnotationAbove(anchor, stateOfMindLabel(anchor.stateOfMind), Color.WHITE);

        // Every other visible mob: attitude label.
        for (Mob m : level.mobs) {
            if (m == anchor) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            String text;
            Color   color;
            if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(anchor, m)
                    == com.bjsp123.rl2.logic.MobSystem.Attitude.ALLY) {
                text = "+"; color = Color.GREEN;
            } else {
                com.bjsp123.rl2.logic.MobSystem.Attitude att =
                        com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(anchor, m);
                switch (att) {
                    case ATTACK -> { text = "!"; color = Color.RED; }
                    case FLEE   -> { text = "?"; color = Color.YELLOW; }
                    default     -> { text = "·"; color = Color.LIGHT_GRAY; }
                }
            }
            drawAnnotationAbove(m, text, color);
        }
    }

    /** Draw {@code text} centered slightly above {@code mob}'s tile using the renderer's
     *  shared {@link #font}. Uses the same world-y offset convention as floating text so
     *  multi-line stacks (state-of-mind on the looked-at mob, "+1" floating heal text,
     *  etc.) layer cleanly. */
    private void drawAnnotationAbove(Mob mob, String text, Color color) {
        if (text == null || text.isEmpty()) return;
        int gx = mob.position.tileX(), gy = mob.position.tileY();
        float wx = gx * (float) CELL;
        float wy = gy * (float) CELL + CELL * 2f;     // ~one cell above sprite top
        font.setColor(color);
        font.draw(batch, text, wx, wy);
        font.setColor(Color.WHITE);
    }

    private static String stateOfMindLabel(com.bjsp123.rl2.model.Mob.StateOfMind s) {
        if (s == null) return "";
        return switch (s) {
            case ASLEEP         -> "asleep";
            case AWAKE          -> "awake";
            case SEEKING_HIDING -> "fleeing";
            case HIDING         -> "hiding";
            case FOLLOWING      -> "following";
        };
    }

    private static long cellKey(int x, int y) {
        return (((long) y) << 32) | (x & 0xFFFFFFFFL);
    }

    private static Map<Long, List<Item>> indexItemsByCell(Level level) {
        Map<Long, List<Item>> out = new HashMap<>();
        for (Item it : level.items) {
            if (it.location == null) continue;
            out.computeIfAbsent(cellKey(it.location.tileX(), it.location.tileY()),
                                k -> new ArrayList<>()).add(it);
        }
        return out;
    }

    private static Map<Long, List<Mob>> indexMobsByCell(Level level) {
        Map<Long, List<Mob>> out = new HashMap<>();
        for (Mob mob : level.mobs) {
            if (mob.position == null) continue;
            out.computeIfAbsent(cellKey(mob.position.tileX(), mob.position.tileY()),
                                k -> new ArrayList<>()).add(mob);
        }
        return out;
    }

    /**
     * Anchor each effect at its SOUTHMOST endpoint's cell. In the N→S loop that means the
     * effect renders only after every mob it could visually overlap (source, destination,
     * or any cell between) has already drawn, so missiles and thrown items stay on top.
     */
    private static Map<Long, List<Effect>> indexEffectsByCell(EffectStage stage) {
        Map<Long, List<Effect>> out = new HashMap<>();
        for (Effect e : stage.active) {
            if (e.location == null) continue;
            int ax = e.location.tileX();
            int ay = e.location.tileY();
            if (e.endLocation != null && e.endLocation.tileY() < ay) {
                ax = e.endLocation.tileX();
                ay = e.endLocation.tileY();
            }
            out.computeIfAbsent(cellKey(ax, ay), k -> new ArrayList<>()).add(e);
        }
        return out;
    }

    /**
     * Floor layer for one cell — everything that should read as "floor". FLOOR / FLOOR_WOOD,
     * LAMP (base), STAIRS (floor underlay + stair glyph), plus the black fill for CHASM cells.
     * Walls, doors, and the "chasm edge" stripes are painted later by {@link #drawWallAt} so
     * they sit on top of surfaces drawn in between.
     */
    private void drawFloorAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        if (t == Tile.CHASM) {
            drawBlackFill(x, y);
            return;
        }
        if (t == Tile.WALL) return;
        // Doors get a floor underlay so the door body (drawn later in drawWallAt) sits on
        // a continuous floor — picked from the neighbour to the N first, failing that the
        // neighbour to the W, failing that the default stone-floor variant. Matching the
        // neighbour's variant keeps wood-floored corridors reading as wood right up to
        // (and under) the door.
        if (t == Tile.DOOR || t == Tile.DOOR_OPEN) {
            int variant = floorVisualAt(level, x, y + 1);   // N (y-up)
            if (variant < 0) variant = floorVisualAt(level, x - 1, y);
            if (variant < 0) variant = TileSprites.floorVariant(TileSprites.variantHash(x, y));
            drawTile(variant, x, y);
            return;
        }
        // Stairs sit on top of a regular floor tile — the stair glyphs only carve out the
        // step geometry, not a full cell, so without the floor under them the surrounding
        // area shows through.
        if (t == Tile.STAIRS_UP || t == Tile.STAIRS_DOWN) {
            // Stair underlay uses the same floor-variant picker as neighboring floors so
            // it doesn't stand out from the surrounding random-variant pattern.
            drawTile(TileSprites.floorVariant(TileSprites.variantHash(x, y)), x, y);
        }
        int visual = terrainVisual(level, x, y);
        if (visual < 0) return;
        drawTile(visual, x, y);
    }

    /** Floor sprite that the cell at (x, y) would render — used by the door-underlay
     *  logic to fetch a neighbour's floor variant. Returns -1 if the cell isn't a
     *  floor type. */
    private int floorVisualAt(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return -1;
        Tile t = level.tiles[x][y];
        int hash = TileSprites.variantHash(x, y);
        if (t == Tile.FLOOR)      return TileSprites.floorVariant(hash);
        if (t == Tile.FLOOR_WOOD) return TileSprites.floorWoodVariant(hash);
        return -1;
    }

    /**
     * Wall body for one cell — raised walls and door bodies. Runs after the surface layer
     * so liquid pools at a wall base don't paint over the wall itself. Chasm edges are drawn
     * in pass 1 by {@link #drawChasmEdgeAt} instead so every floor-level detail (floors, chasm
     * black fill, chasm edges) is committed before per-cell content starts layering on top.
     */
    private void drawWallAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        if (t != Tile.WALL && t != Tile.DOOR && t != Tile.DOOR_OPEN) return;
        int visual = terrainVisual(level, x, y);
        if (visual < 0) return;
        // Sideways open door (walls both north and south) — the door body art reads as
        // anchored against the wall above, so we shift the sprite one cell north and let
        // the floor underlay show through where the door body used to sit. The wall at
        // (x, y+1) still renders in its own pass; the door body overlays it. Closed
        // sideways doors keep the in-cell draw so the doorway fills the gap.
        if (t == Tile.DOOR_OPEN
                && isWallish(level, x, y - 1)
                && isWallish(level, x, y + 1)) {
            drawTile(visual, x, y + 1);
            return;
        }
        drawTile(visual, x, y);
    }

    /**
     * Chasm-edge tile for one cell — picks the "dripping edge" glyph for a CHASM cell based
     * on what's in the cell to the north (water/wall/wood floor/anything else).
     * No-op for non-chasm cells; no-op for chasm cells whose north neighbor is also chasm
     * (there's nothing for the edge to "drip" from).
     */
    private void drawChasmEdgeAt(Level level, int x, int y) {
        if (level.tiles[x][y] != Tile.CHASM) return;
        Tile north = tileAt(level, x, y + 1);
        if (north == null || north == Tile.CHASM) return;
        int edge;
        if      (surfaceAt(level, x, y + 1) == Surface.WATER) edge = TileSprites.CHASM_WATER;
        else if (north == Tile.WALL || north == Tile.DOOR
              || north == Tile.DOOR_OPEN)                    edge = TileSprites.CHASM_WALL;
        else if (north == Tile.FLOOR_WOOD)                   edge = TileSprites.CHASM_WOOD;
        else                                                 edge = TileSprites.CHASM_FLOOR;
        drawTile(edge, x, y);
    }

    /**
     * Paint a thin shadow strip on each side of a floor tile that touches a wall, for one
     * cell. The strip is {@value #FLOOR_SHADOW_PX} pixels deep (perpendicular to the wall)
     * and uses a 4-pixel alpha gradient that's opaque right at the wall and fades to
     * transparent into the floor. Negative {@code width}/{@code height} args to
     * {@code batch.draw} flip the gradient when we need it pointing the other way, so a
     * single vertical and a single horizontal texture cover all four sides. Runs AFTER the
     * surface layer so the shadow sits on top of any water/blood/oil at the wall's foot
     * (matching the real-world order: wall casts shadow onto liquid).
     */
    private void drawFloorEdgeShadowAt(Level level, int x, int y) {
        if (!level.tiles[x][y].isFloorLike()) return;
        batch.setColor(getShadowColor(level));
        float px = x * (float) CELL;
        float py = y * (float) CELL;
        if (isWallCell(level, x, y + 1)) {
            // Wall to north: strip at top of cell, opaque at top — flip vertical
            // gradient by drawing with negative height anchored at the cell's top.
            batch.draw(floorShadowVertTex, px, py + CELL,
                       CELL, -FLOOR_SHADOW_PX);
        }
        if (isWallCell(level, x, y - 1)) {
            // Wall to south: strip at bottom of cell, opaque at bottom — natural orientation.
            batch.draw(floorShadowVertTex, px, py, CELL, FLOOR_SHADOW_PX);
        }
        if (isWallCell(level, x - 1, y)) {
            // Wall to west: strip on left, opaque at left — natural orientation.
            batch.draw(floorShadowHorzTex, px, py, FLOOR_SHADOW_PX, CELL);
        }
        if (isWallCell(level, x + 1, y)) {
            // Wall to east: strip on right, opaque at right — flip horizontally.
            batch.draw(floorShadowHorzTex, px + CELL, py,
                       -FLOOR_SHADOW_PX, CELL);
        }
        batch.setColor(Color.WHITE);
    }

    /**
     * Build a 1-D alpha-only gradient as an RGBA texture. Pixels are pure white; alpha ramps
     * from 0 at one end to 255 at the other along the major axis.
     * <ul>
     *   <li>{@code horizontal=false} (vertical strip): alpha 0 at image-top row 0, 255 at
     *       image-bottom row {@code h-1}. SpriteBatch maps image-top to high screen-y, so the
     *       drawn rect is opaque at its bottom edge.</li>
     *   <li>{@code horizontal=true}: alpha 255 at image-left column 0, 0 at image-right
     *       column {@code w-1}. Drawn rect is opaque at its left edge.</li>
     * </ul>
     * Linear filtering smooths the ramp at any render size.
     */
    private static Texture makeAlphaGradient(int w, int h, boolean horizontal) {
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float t = horizontal
                        ? 1f - (w == 1 ? 0f : x / (float) (w - 1)) // 1 at left, 0 at right
                        :       (h == 1 ? 1f : y / (float) (h - 1)); // 0 at top,  1 at bottom
                int a = Math.round(t * 255f);
                int rgba = (255 << 24) | (255 << 16) | (255 << 8) | a; // RGBA8888 white + alpha
                p.drawPixel(x, y, rgba);
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return t;
    }

    /** A wall or a door — both read as solid wall sections for shadow purposes, regardless
     *  of which axis the door sits across or whether the door is open. Floor cells adjacent
     *  to either get the same floor-edge shadow strip, and the cell itself gets the same
     *  wall-base shadow. */
    private static boolean isWallCell(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Tile t = level.tiles[x][y];
        return t == Tile.WALL || t == Tile.DOOR || t == Tile.DOOR_OPEN;
    }

    /**
     * For one cell: if it's a WALL with a non-chasm floor-like tile directly south, paint a
     * gradient shadow across the base of the wall (opaque at the bottom edge, fading a few
     * pixels up) plus rear-corner shadow stripes when a perpendicular wall stub meets it
     * diagonally. Gives the raised wall a soft contact shadow that reads as "the wall
     * catches light from above and casts darkness where it meets the floor". CHASM to the
     * south is explicitly excluded — a wall at the edge of a pit has nothing to shadow onto.
     */
    private void drawWallBaseShadowAt(Level level, int x, int y) {
        if (!isWallCell(level, x, y)) return;
        Tile south = tileAt(level, x, y - 1);
        if (south == null || south == Tile.CHASM || isWallCell(level, x, y - 1)) return;
        batch.setColor(getShadowColor(level));
        float px = x * (float) CELL;
        float py = y * (float) CELL;
        batch.draw(wallBaseShadowTex, px, py, CELL, WALL_BASE_SHADOW_PX);

        // Rear corner shadows — thin vertical bands along the wall's left/right edge
        // when the floor at the south has a perpendicular wall stub at the opposite
        // diagonal. Only fires when south is a true floor tile, since chasm/wall to
        // the south already disqualified us above.
        if (south.isFloorLike()) {
            Tile sw = tileAt(level, x - 1, y - 1);
            Tile se = tileAt(level, x + 1, y - 1);
            if (sw == Tile.WALL) {
                // Wall is northeast of the SW wall stub → shadow on this wall's LEFT edge.
                // Only the lower 11 px of the 16-px cell — keeps the top 5 px of the wall
                // clean so the cap doesn't read as fully darkened.
                batch.draw(floorShadowHorzTex, px, py,
                           WALL_REAR_CORNER_SHADOW_PX, WALL_REAR_CORNER_SHADOW_H);
            }
            if (se == Tile.WALL) {
                // Wall is northwest of the SE wall stub → shadow on this wall's RIGHT edge.
                batch.draw(floorShadowHorzTex, px + CELL, py,
                           -WALL_REAR_CORNER_SHADOW_PX, WALL_REAR_CORNER_SHADOW_H);
            }
        }
        batch.setColor(Color.WHITE);
    }

    // Shore-mask extraction also lives in SurfaceSprites — see SurfaceSprites.maskTexture.

    /**
     * Shader that pairs each sampled pixel of the sprite (the mask tile) with a sample of a
     * secondary texture (the scrolling liquid, bound to TEXTURE1). Output RGB comes from the
     * liquid; output alpha is {@code 1 - mask.alpha} — i.e. opaque white in the mask means
     * NO water at that pixel and transparent mask pixels show full water. Authoring rule:
     * paint the shore-cutout pixels opaque white, leave the water-filled interior fully
     * transparent. Standard alpha blending in the batch then blends the resulting liquid
     * over the already-drawn floor using that profile as its opacity.
     */
    private static ShaderProgram buildSurfaceMaskShader() {
        String vert = "attribute vec4 a_position;\n"
                + "attribute vec4 a_color;\n"
                + "attribute vec2 a_texCoord0;\n"
                + "uniform mat4 u_projTrans;\n"
                + "varying vec4 v_color;\n"
                + "varying vec2 v_texCoords;\n"
                + "void main() {\n"
                + "    v_color = a_color;\n"
                + "    v_color.a = v_color.a * (255.0/254.0);\n"
                + "    v_texCoords = a_texCoord0;\n"
                + "    gl_Position = u_projTrans * a_position;\n"
                + "}";
        String frag = "#ifdef GL_ES\n"
                + "precision mediump float;\n"
                + "#endif\n"
                + "varying vec4 v_color;\n"
                + "varying vec2 v_texCoords;\n"
                + "uniform sampler2D u_texture;\n"      // mask (sprite's own texture)
                + "uniform sampler2D u_surfaceTex;\n"    // scrolling surface, bound to TEXTURE1
                + "uniform vec4 u_surfaceUv;\n"          // (u1, v1, u2, v2) over this cell
                + "void main() {\n"
                + "    float maskA = texture2D(u_texture, v_texCoords).a;\n"
                + "    vec2 uv = u_surfaceUv.xy + v_texCoords * (u_surfaceUv.zw - u_surfaceUv.xy);\n"
                + "    vec4 surf = texture2D(u_surfaceTex, uv);\n"
                + "    gl_FragColor = vec4(surf.rgb * v_color.rgb, (1.0 - maskA) * v_color.a);\n"
                + "}";
        ShaderProgram sp = new ShaderProgram(vert, frag);
        if (!sp.isCompiled()) {
            throw new IllegalStateException("surface-mask shader compile failed: " + sp.getLog());
        }
        return sp;
    }

    /**
     * Build a 1×8 vertical gradient texture with alpha 0 at the top row and alpha 255 at the
     * bottom row. Linear filtering smooths the ramp at whatever render size we ask for.
     * Stored as black so the batch color becomes the final tint.
     */
    private static Texture makeWallBaseShadowTexture() {
        int w = 1, h = 8;
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < h; y++) {
            // y=0 is the image's top row, which libGDX renders at the HIGHEST screen y of
            // the drawn rect. We want that end transparent and the image-bottom opaque so the
            // shadow sits at the cell floor and fades upward on screen.
            float t = y / (float) (h - 1);
            int alpha = Math.round(t * 255f);
            p.drawPixel(0, y, alpha); // RGBA8888: R=G=B=0, A=alpha
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return t;
    }

    /**
     * Surface pass — draws every water/blood/oil cell on the level using the surface-mask
     * shader. Runs as a single dedicated pass right after floors are laid down, before
     * shadows / walls / vegetation / mobs / etc., so liquid sits on the floor and tucks
     * under wall-base shadows. Bundling the cells means the mask shader gets bound exactly
     * once for the whole level instead of swapped on/off per cell, and the liquid texture
     * (TEXTURE1) is rebound only when the surface kind changes between cells.
     */
    private void drawSurfacesPass(Level level) {
        if (level.surface == null) return;
        // Cheap bail-out when the level has no surfaces at all — keeps the shader off the
        // GPU pipeline on dry levels.
        boolean any = false;
        outer:
        for (int x = 0; x < level.width && !any; x++) {
            for (int y = 0; y < level.height; y++) {
                if (level.explored[x][y] && level.surface[x][y] != null) { any = true; break outer; }
            }
        }
        if (!any) return;

        batch.setShader(surfaceMaskShader);
        surfaceMaskShader.setUniformi("u_surfaceTex", 1);
        Surface lastSurf = null;
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (!level.explored[x][y]) continue;
                Surface s = level.surface[x][y];
                if (s == null) continue;
                if (s != lastSurf) {
                    // The bound liquid texture is per-pass state, so flush any buffered
                    // draws under the old binding before swapping in the next surface kind.
                    batch.flush();
                    textureFor(s).bind(1);
                    Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
                    lastSurf = s;
                }
                drawMaskedSurfaceCell(level, s, x, y);
            }
        }
        batch.setShader(null);
        batch.setColor(Color.WHITE);
    }

    private void drawMaskedSurfaceCell(Level level, Surface s, int gx, int gy) {
        int variant = stitchSurface(level, gx, gy, s) - TileSprites.WATER;
        float scroll = waterTime * WATER_SCROLL_PX_PER_SEC;
        // Sampling the texture at higher u shifts the visible pattern LEFT on screen; sampling
        // at lower v shifts it DOWN. Use that to drift the liquid south-west over time.
        float u1 = (gx * CELL + scroll) / WATER_TEX_SIZE;
        float v1 = (gy * CELL - scroll) / WATER_TEX_SIZE;
        float u2 = u1 + CELL / WATER_TEX_SIZE;
        float v2 = v1 + CELL / WATER_TEX_SIZE;

        // Commit the previous cell with its uniform BEFORE updating to this cell's value.
        batch.flush();
        surfaceMaskShader.setUniformf("u_surfaceUv", u1, v1, u2, v2);
        batch.draw(surfaceMaskTex[variant], gx * (float) CELL, gy * (float) CELL, CELL, CELL);
    }

    private Texture textureFor(Surface s) {
        return switch (s) {
            case WATER -> waterTex;
            case BLOOD -> bloodTex;
            case OIL   -> oilTex;
            case ICE   -> iceTex;
        };
    }

    /**
     * Small flora on top of the floor. Drawn between the surface pass and the entity pass so
     * grass is under mobs/items but above any water it sits next to.
     */
    /**
     * Rear vegetation pass: runs before items/mobs so the rear sprite (variant A or B per
     * the per-tile {@link #vegRearVariantBit} hash) sits behind anything standing on the tile.
     * X jitter is applied via {@link #vegJitterX} and reused by {@link #drawFrontVegetationAt}
     * so the rear and front layers line up horizontally; vertical lift comes from
     * {@link #vegRearLiftPx} (3–6 px above the tile floor).
     */
    private void drawRearVegetationAt(Level level, int x, int y) {
        // Tree canopies extend into the cell ABOVE their tile. Render order is top-down
        // (highest y first), so a tree at (x, y-1) needs its canopy painted in this cell's
        // rear-veg phase — that way it sits behind any mob that's standing in (x, y).
        if (y - 1 >= 0 && level.vegetation[x][y - 1] == Vegetation.TREES) {
            drawTreeCanopy(x, y - 1);
        }

        Vegetation v = level.vegetation[x][y];
        if (v == null) return;
        if (v == Vegetation.FIRE) { fxRenderer.drawFireAt(x, y); return; }

        if (v == Vegetation.TREES) {
            drawTreeTrunk(x, y);
            return;
        }

        Texture tex = rearVegTexture(v, x, y);
        if (tex == null) return;
        int jx = vegJitterX(x, y);
        int lift = vegRearLiftPx(x, y);
        // Rear vegetation rides 3–6 px above the tile floor so the front sprite (drawn at
        // the base) reads as the foreground blades and the rear sprite reads as the body
        // poking up behind it.
        float dx = x * (float) CELL + jx;
        float dy = y * (float) CELL + lift;
        drawTextureOutline(tex, dx, dy, CELL, CELL);
        batch.setColor(Color.WHITE);
        batch.draw(tex, dx, dy, CELL, CELL);
    }

    /** Paint the upper (canopy) half of the tree-at-({@code treeX}, {@code treeY}) sprite
     *  into the cell directly above it (world (treeX, treeY+1)). Called from that cell's
     *  rear-veg phase so the canopy sits behind any mob standing on the cell above. The
     *  same vertical lift used for normal rear vegetation is applied so the trunk + canopy
     *  read as a single shifted sprite. */
    private void drawTreeCanopy(int treeX, int treeY) {
        Texture tex = rearVegTexture(Vegetation.TREES, treeX, treeY);
        if (tex == null) return;
        int jx = vegJitterX(treeX, treeY);
        int lift = vegRearLiftPx(treeX, treeY);
        int half = tex.getHeight() / 2;
        float dx = treeX * (float) CELL + jx;
        float dy = (treeY + 1) * (float) CELL + lift;
        drawTextureOutlineSrc(tex, dx, dy, CELL, CELL,
                0, 0, tex.getWidth(), half);
        batch.setColor(Color.WHITE);
        batch.draw(tex,
                dx, dy,
                CELL, CELL,
                0, 0,                           // src origin: top of texture = canopy
                tex.getWidth(), half,
                false, false);
    }

    /** Paint the lower (trunk) half of the tree sprite at world (x, y), lifted by the
     *  shared 3–6 px rear-veg offset. */
    private void drawTreeTrunk(int x, int y) {
        Texture tex = rearVegTexture(Vegetation.TREES, x, y);
        if (tex == null) return;
        int jx = vegJitterX(x, y);
        int lift = vegRearLiftPx(x, y);
        int half = tex.getHeight() / 2;
        float dx = x * (float) CELL + jx;
        float dy = y * (float) CELL + lift;
        drawTextureOutlineSrc(tex, dx, dy, CELL, CELL,
                0, half, tex.getWidth(), half);
        batch.setColor(Color.WHITE);
        batch.draw(tex,
                dx, dy,
                CELL, CELL,
                0, half,                        // src origin: middle of texture = top of trunk
                tex.getWidth(), half,
                false, false);
    }

    /** Deterministic per-tile vertical lift for rear vegetation, in pixels in the range
     *  {@code [3..6]}. Adjacent tiles get slightly different lifts so a row of grass reads
     *  as a textured field rather than a stamped band. */
    private static int vegRearLiftPx(int x, int y) {
        int h = (x * 0x9E3779B1) ^ (y * 0xC2B2AE3D);
        return 3 + ((h >>> 11) & 3);    // 3..6
    }

    /**
     * Front vegetation pass: runs AFTER items/mobs so the front sprite (the "second"
     * variant per {@link #frontVegTexture}, e.g. grassTexB) sits in front of any mob on
     * the tile. Drawn at the tile's base — no Y jitter — so the foreground blades anchor
     * to the floor. The art is authored mostly transparent above the blade tops, so a
     * full-cell draw produces the "mob's feet tucked into grass" silhouette.
     */
    private void drawFrontVegetationAt(Level level, int x, int y) {
        Vegetation v = level.vegetation[x][y];
        if (v == null) return;
        // Fire is drawn whole in the rear pass — no front overlay (the flame shouldn't
        // tuck back over the mob's feet the way grass does).
        if (v == Vegetation.FIRE) return;
        Texture tex = frontVegTexture(v, x, y);
        if (tex == null) return;
        // Front vegetation always sits at the tile's base (no Y lift, unlike the rear
        // pass) and renders the full sprite. The art for the front variants is authored
        // with mostly-transparent upper rows + foreground blades near the bottom, so a
        // full-cell draw naturally produces the "feet-tucked-into-grass" effect on the
        // tile's occupant. X jitter matches the rear sprite so the two layers line up
        // horizontally.
        int jx = vegJitterX(x, y);
        float dx = x * (float) CELL + jx;
        float dy = y * (float) CELL;
        drawTextureOutline(tex, dx, dy, CELL, CELL);
        batch.setColor(Color.WHITE);
        batch.draw(tex, dx, dy, CELL, CELL);
    }

    /** Rear-pass texture pick: each veg type has two source variants in surfaces.png; the
     *  per-tile {@link #vegRearVariantBit} hash picks which one renders so adjacent tiles
     *  break up the grid. The chosen sprite goes behind any mob standing on the tile. */
    private Texture rearVegTexture(Vegetation v, int x, int y) {
        boolean b = vegRearVariantBit(x, y);
        switch (v) {
            case GRASS:     return b ? grassTexB    : grassTexA;
            case MUSHROOMS: return b ? mushroomTexB : mushroomTexA;
            case TREES:     return b ? treeTexB     : treeTexA;
            default:        return null;
        }
    }

    /** Front-pass texture pick: each tile picks one of the two grass variants (for grass
     *  and tree tiles) or one of the two mushroom variants (for fungus tiles), randomised
     *  by an independent hash from the rear pick so a tile can mix variants between its
     *  two layers. Fire has no front overlay. */
    private Texture frontVegTexture(Vegetation v, int x, int y) {
        boolean b = vegFrontVariantBit(x, y);
        switch (v) {
            case GRASS:     return b ? grassTexB    : grassTexA;
            case MUSHROOMS: return b ? mushroomTexB : mushroomTexA;
            case TREES:     return b ? grassTexB    : grassTexA;   // grass for tree front
            default:        return null;
        }
    }

    /** Deterministic 1-bit per-tile hash used to pick the rear A/B variant. Chosen so
     *  adjacent tiles flip frequently, breaking up the grid. */
    private static boolean vegRearVariantBit(int x, int y) {
        int h = (x * 73856093) ^ (y * 19349663);
        return ((h >>> 7) & 1) == 1;
    }

    /** Independent per-tile hash for the front variant, so a tile's rear and front layers
     *  may use different variants. Different multipliers + shift than {@link #vegRearVariantBit}. */
    private static boolean vegFrontVariantBit(int x, int y) {
        int h = (x * 0x27D4EB2F) ^ (y * 0x165667B1);
        return ((h >>> 11) & 1) == 1;
    }

    /** Half-range of the deterministic per-tile vegetation jitter: outputs span
     *  {@code -VEG_JITTER_PX .. +VEG_JITTER_PX} per axis, picked by a per-tile hash so
     *  adjacent grass / mushroom tiles don't look stamped from a single rubber stamp. */
    private static final int VEG_JITTER_PX = 3;

    /**
     * Deterministic per-tile horizontal jitter in {@code [-VEG_JITTER_PX, +VEG_JITTER_PX]}.
     * Both rear and front veg passes use this so a tile's two layers stay aligned with
     * each other while neighbours look offset. Vertical positioning is split between the
     * passes: rear uses {@link #vegRearLiftPx} (3–6 px above base), front always sits at
     * the base.
     */
    private static int vegJitterX(int x, int y) {
        int span = VEG_JITTER_PX * 2 + 1;
        int h = x * 73856093 ^ y * 19349663;
        return Math.floorMod(h, span) - VEG_JITTER_PX;
    }

    /**
     * Lamp overlay for one cell — the floor base was already drawn by {@link #drawFloorAt};
     * here we overlay the lamp sprite from the terrain atlas so the stem rises into the
     * cell above. Drawn before items/mobs so anything standing on a lamp tile renders on
     * top of it. Falls back to a no-op if the active theme didn't ship a lamp ornament.
     */
    private void drawLampAt(Level level, int x, int y) {
        if (level.tiles[x][y] != Tile.LAMP) return;
        if (currentLampOrnament == null) return;
        batch.setColor(Color.WHITE);
        drawLampShadow(x, y);
        // 1 cell wide × 2 cells tall, anchored at the floor cell so the upper half
        // overhangs into the cell above (matching the source 32×64 art).
        float dx = x * (float) CELL;
        float dy = y * (float) CELL;
        drawRegionOutline(currentLampOrnament, dx, dy, CELL, 2f * CELL);
        batch.setColor(Color.WHITE);
        batch.draw(currentLampOrnament, dx, dy, CELL, 2f * CELL);
    }

    /** Width of the contact shadow under a small statue, in screen pixels. */
    private static final int STATUE_SMALL_SHADOW_W = 10;
    /** Width of the contact shadow under a tall statue. Wider than the small variant so a
     *  large pedestal silhouette reads as planted on the floor. */
    private static final int STATUE_LARGE_SHADOW_W = 16;

    /**
     * Stair-ladder overlay for one cell. Source is 2×2 atlas cells (64×64 source) and is
     * drawn as 2×2 world cells (32×32 px), anchored at the bottom of the stair cell with
     * the ladder horizontally centered on the cell. The top half overhangs into the cell
     * to the north — same convention as the lamp ornament. Floor underlay was painted in
     * {@link #drawFloorAt}.
     */
    private void drawStairsAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        TextureRegion r;
        if (t == Tile.STAIRS_UP)        r = currentStairsUp;
        else if (t == Tile.STAIRS_DOWN) r = currentStairsDown;
        else return;
        if (r == null) return;
        batch.setColor(Color.WHITE);
        float dispW = 2f * CELL;   // 32 px wide
        float dispH = 2f * CELL;   // 32 px tall
        float drawX = x * (float) CELL + CELL / 2f - dispW / 2f;   // centered horizontally
        float drawY = y * (float) CELL;                             // base at floor cell
        batch.draw(r, drawX, drawY, dispW, dispH);
        drawStairLabelAt(level, x, y);
    }

    /** Pulsing glow-text overlay on a stair tile. Reads which of the four named stair points
     *  ({@code stairs(Up|Down)(Alt)}) this cell is, looks up the corresponding target level
     *  on the {@link #world}, and draws "↑/↓ lvl N W/E/?" with a sine-modulated alpha so the
     *  writing fades in and out. Drawn twice — a wide dim outer pass + a tight bright inner
     *  pass — for a soft halo. No-op if the world reference isn't set or the cell isn't a
     *  named stair point on this level. */
    private void drawStairLabelAt(Level level, int x, int y) {
        if (world == null) return;
        boolean ascending;
        int target;
        if (matches(level.stairsUp, x, y))         { ascending = true;  target = level.stairsUpTarget; }
        else if (matches(level.stairsUpAlt, x, y)) { ascending = true;  target = level.stairsUpAltTarget; }
        else if (matches(level.stairsDown, x, y))  { ascending = false; target = level.stairsDownTarget; }
        else if (matches(level.stairsDownAlt, x, y)){ ascending = false; target = level.stairsDownAltTarget; }
        else return;
        if (target < 0 || target >= world.levels.length) return;
        Level dst = world.levels[target];
        if (dst == null) return;

        String text = stairLabelText(ascending, dst);
        // Per-stair phase derived from cell coords so neighbouring stairs don't pulse in
        // perfect lockstep. Cycle period ~2.4 s; alpha sweeps through [0.25, 0.95].
        float phase = (x * 0.37f + y * 0.61f);
        float pulse = 0.5f + 0.5f * (float) Math.sin(stairLabelTime * 2.6f + phase);
        float alpha = 0.25f + 0.70f * pulse;

        // Position: centred horizontally on the stair cell, just above the sprite top so it
        // floats over the staircase like a sign nailed to the wall above the entrance.
        com.badlogic.gdx.graphics.g2d.GlyphLayout layout =
                new com.badlogic.gdx.graphics.g2d.GlyphLayout();
        layout.setText(font, text);
        float wx = x * (float) CELL + CELL / 2f - layout.width  / 2f;
        float wy = y * (float) CELL + 2f * CELL + 4f;   // just above the 32-px sprite

        // Outer glow — dim warm halo at 1.6× scale.
        float prevScaleX = font.getData().scaleX;
        float prevScaleY = font.getData().scaleY;
        font.getData().setScale(prevScaleX * 1.6f);
        font.setColor(1f, 0.85f, 0.45f, alpha * 0.35f);
        com.badlogic.gdx.graphics.g2d.GlyphLayout glow = new com.badlogic.gdx.graphics.g2d.GlyphLayout();
        glow.setText(font, text);
        font.draw(batch, text,
                x * (float) CELL + CELL / 2f - glow.width / 2f,
                wy + (glow.height - layout.height) * 0.5f);
        // Inner crisp text.
        font.getData().setScale(prevScaleX, prevScaleY);
        font.setColor(1f, 0.95f, 0.7f, alpha);
        font.draw(batch, text, wx, wy);
        font.setColor(Color.WHITE);
    }

    private static boolean matches(com.bjsp123.rl2.model.Point p, int x, int y) {
        return p != null && p.tileX() == x && p.tileY() == y;
    }

    /** Build the floating label string: arrow + "lvl N" + slot character (W / E / ?). */
    private static String stairLabelText(boolean ascending, Level dst) {
        char slot = switch (dst.side == null ? Level.Side.CENTER : dst.side) {
            case WEST   -> 'W';
            case EAST   -> 'E';
            case CENTER -> '?';
        };
        // ASCII arrows so the default bitmap font renders them reliably across platforms.
        String arrow = ascending ? "^" : "v";
        return arrow + " lvl " + dst.depth + " " + slot;
    }

    /**
     * Statue overlay for one cell. Paints a soft floor shadow first so the figure reads as
     * planted on the floor, then the sprite. The small-statue source is one cell tall and
     * draws into just the floor cell; the large-statue source is two cells tall and is
     * drawn anchored at the floor cell with the upper half overhanging into the cell to
     * the north (same convention as the lamp). Source art faces west; the {@code _R} tile
     * variant is rendered with negative draw width so one sprite covers both facings.
     */
    private void drawStatueAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        boolean small = (t == Tile.STATUE_SMALL_L || t == Tile.STATUE_SMALL_R);
        boolean large = (t == Tile.STATUE_LARGE_L || t == Tile.STATUE_LARGE_R);
        if (!small && !large) return;
        TextureRegion r = small ? currentSmallStatue : currentLargeStatue;
        if (r == null) return;
        boolean flip = (t == Tile.STATUE_SMALL_R || t == Tile.STATUE_LARGE_R);
        // Contact shadow first, beneath the sprite.
        drawStatueShadow(x, y, large);
        batch.setColor(Color.WHITE);
        float dx = x * (float) CELL;
        float dy = y * (float) CELL;
        // Display dimensions: small → 1 cell square; large → 1 cell wide × 2 cells tall.
        float dw = CELL;
        float dh = small ? CELL : 2f * CELL;
        if (flip) {
            drawRegionOutline(r, dx + dw, dy, -dw, dh);
            batch.setColor(Color.WHITE);
            batch.draw(r, dx + dw, dy, -dw, dh);
        } else {
            drawRegionOutline(r, dx, dy, dw, dh);
            batch.setColor(Color.WHITE);
            batch.draw(r, dx, dy, dw, dh);
        }
    }

    /** Soft elliptical contact shadow under a statue at the base of its tile. Re-uses the
     *  mob shadow texture (lighter than the lamp shadow) so the figure reads as standing
     *  on the floor without looking as heavy as a planted lamp. */
    private void drawStatueShadow(int gx, int gy, boolean large) {
        if (shadowTex == null) return;
        float cx = gx * CELL + CELL / 2f;
        float by = gy * CELL + ENTITY_Y_OFFSET;
        int shadowW = large ? STATUE_LARGE_SHADOW_W : STATUE_SMALL_SHADOW_W;
        batch.setColor(Color.WHITE);
        batch.draw(shadowTex, cx - shadowW / 2f, by - SHADOW_H / 2f, shadowW, SHADOW_H);
    }

    /**
     * Soft elliptical shadow cast under a lamp at the base of its tile. Uses the dedicated
     * {@link #lampShadowTex} (wider, taller, darker than the mob shadow) so the lamp reads
     * as firmly planted on the floor rather than floating above it.
     */
    private void drawLampShadow(int gx, int gy) {
        float cx = gx * CELL + CELL / 2f;
        float by = gy * CELL + ENTITY_Y_OFFSET;
        batch.setColor(Color.WHITE);
        batch.draw(lampShadowTex, cx - LAMP_SHADOW_W / 2f, by - LAMP_SHADOW_H / 2f,
                LAMP_SHADOW_W, LAMP_SHADOW_H);
    }

    private static Surface surfaceAt(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return null;
        return level.surface[x][y];
    }

    private void drawBlackFill(int gx, int gy) {
        batch.setColor(Color.BLACK);
        batch.draw(whiteRegion, gx * (float) CELL, gy * (float) CELL, CELL, CELL);
        batch.setColor(Color.WHITE);
    }

    /**
     * "Ceiling" for one cell — paints overhang / cap tiles belonging to a wall or door in
     * the cell to the SOUTH. Three cases:
     * <ul>
     *   <li>Walls: overhang and internal-cap paint at the cell NORTH of the wall, on top of
     *       that cell's mob so tall walls clip anything behind them.</li>
     *   <li>Doors (N/S-facing): door top paints at the cell NORTH of the door — same
     *       convention as walls, so the arched top sits above the door on screen.</li>
     *   <li>Sideways doors (E/W-facing): overhang paints on the door cell itself.</li>
     * </ul>
     */
    private void drawWallOverlayAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];

        if (t == Tile.WALL) {
            if (isWallish(level, x, y - 1)) {
                int bits = 0;
                if (!stitchBarrier(level, x + 1, y    )) bits += 1;
                if (!stitchBarrier(level, x + 1, y - 1)) bits += 2;
                if (!stitchBarrier(level, x - 1, y - 1)) bits += 4;
                if (!stitchBarrier(level, x - 1, y    )) bits += 8;
                int result = TileSprites.internalWallVariant(TileSprites.variantHash(x, y), bits);
                drawTile(result, x, y);
            } else if (level.tiles[x][y - 1] == Tile.DOOR) {
                // Wall-over-sideways-door cutout: only painted when the door is CLOSED. When
                // the sideways door opens, the cutout sprite is dropped — the wall above
                // renders normally without a special "door top" overlay.
                drawTile(TileSprites.DOOR_SIDEWAYS, x, y);
            }
            return;
        }

        if (t == Tile.DOOR && isWallish(level, x, y - 1)) {
            int result = TileSprites.DOOR_SIDEWAYS_OVERHANG_CLOSED;
            if (!stitchBarrier(level, x + 1, y - 1)) result += 1;
            if (!stitchBarrier(level, x - 1, y - 1)) result += 2;
            drawTile(result, x, y);
            return;
        }

        if (isWallish(level, x, y - 1)) {
            int result = TileSprites.WALLS_OVERHANG;
            if (!stitchBarrier(level, x + 1, y - 1)) result += 1;
            if (!stitchBarrier(level, x - 1, y - 1)) result += 2;
            drawTile(result, x, y);
            return;
        }
        // Door top paints at the cell NORTH of the door (y+1 = north in y-up) — same cell
        // as wall overhangs use. The SPD convention: the door body occupies its own cell and
        // the arched top visually sits in the tile above on screen.
        if (isDoorAt(level, x, y - 1)) {
            drawTile(TileSprites.DOOR_OVERHANG, x, y);
        }
    }

    private int terrainVisual(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        int hash = TileSprites.variantHash(x, y);
        return switch (t) {
            case FLOOR       -> TileSprites.floorVariant(hash);
            case FLOOR_WOOD  -> TileSprites.floorWoodVariant(hash);
            case LAMP        -> TileSprites.floorVariant(hash); // base; lamp sprite drawn in drawLampAt
            // Stairs render their floor underlay in drawFloorAt and the 2×2 ladder sprite
            // in the per-cell content pass (drawStairsAt). Returning -1 here skips the
            // legacy 1×1 glyph draw.
            case STAIRS_UP, STAIRS_DOWN -> -1;
            case CHASM       -> -1; // handled inline
            case WALL        -> raisedWall(level, x, y);
            case DOOR, DOOR_OPEN -> raisedDoor(level, x, y);
            // Statues sit on a regular floor base; the statue sprite itself is layered on
            // top in drawStatueAt so the L/R facing flip can be applied at draw time.
            case STATUE_SMALL_L, STATUE_SMALL_R,
                 STATUE_LARGE_L, STATUE_LARGE_R -> TileSprites.floorVariant(hash);
        };
    }

    /**
     * SPD's 4-neighbor stitch, adapted to the surface model: a neighbor counts as "shore" if
     * it's a walkable tile that does NOT share this cell's surface, so adjacent pool cells
     * blend seamlessly while edges against dry floor get the shore overlay. +1 north, +2 east,
     * +4 south, +8 west.
     */
    private int stitchSurface(Level level, int x, int y, Surface self) {
        int result = TileSprites.WATER;
        if (isSurfaceShore(level, x,     y + 1, self)) result += 1;
        if (isSurfaceShore(level, x + 1, y,     self)) result += 2;
        if (isSurfaceShore(level, x,     y - 1, self)) result += 4;
        if (isSurfaceShore(level, x - 1, y,     self)) result += 8;
        return result;
    }

    /**
     * True when the cell at (x, y) marks a "shore" boundary for a surface of type {@code self}
     * sitting in the source cell — meaning {@code self}'s mask should fade on that side. The
     * test is "the neighbour does NOT also hold {@code self}": a dry tile, a different
     * surface, or out-of-bounds all qualify. This is what makes per-surface passes work — in
     * the water pass, a blood-neighbour cell is a shore for water just like a dry tile would
     * be, so the water surface fades there independently of what blood draws in its own pass.
     * Walls / chasms are excluded because they're opaque scenery that already hides anything
     * we'd draw underneath.
     */
    private static boolean isSurfaceShore(Level level, int x, int y, Surface self) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Tile t = level.tiles[x][y];
        if (t == Tile.WALL || t == Tile.CHASM) return false;
        return level.surface[x][y] != self;
    }

    private int raisedWall(Level level, int x, int y) {
        Tile south = tileAt(level, x, y - 1);
        if (south == Tile.WALL) return -1;
        // Skip only when there's truly nothing visible behind the wall — OOB or unexplored
        // chasm. An EXPLORED chasm south (e.g. the interior of a visible chasm-filled room)
        // is a legitimate backdrop: the wall still renders so the player can see it.
        if (south == null) return -1;
        if (south == Tile.CHASM && !level.explored[x][y - 1]) return -1;

        int bits = 0;
        if (!stitchBarrier(level, x + 1, y)) bits += 1;
        if (!stitchBarrier(level, x - 1, y)) bits += 2;
        return TileSprites.raisedWallVariant(TileSprites.variantHash(x, y), bits);
    }

    private int raisedDoor(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        // Sideways door (wall-flanked along the N/S axis): same body sprite for open and
        // closed; the difference shows in the dropped overlays handled by drawWallOverlayAt.
        // Front-facing door swaps the body sprite for the row-9 "open" variant when open.
        if (isWallish(level, x, y - 1)) return TileSprites.RAISED_DOOR_SIDEWAYS;
        return t == Tile.DOOR_OPEN ? TileSprites.RAISED_DOOR_OPEN : TileSprites.RAISED_DOOR;
    }

    private static boolean isWallish(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return true;
        return level.tiles[x][y] == Tile.WALL;
    }

    /**
     * Stitching neighbor test for the 4-bit variant math. Walls and OOB are always barriers.
     * An UNEXPLORED chasm is a barrier (stands in for "outside the map" so walls don't stitch
     * open on the unseen side), but an EXPLORED chasm is an open interior — walls of a visible
     * chasm-filled room should stitch toward it, not dead-end like they would at the map edge.
     */
    private static boolean stitchBarrier(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return true;
        Tile t = level.tiles[x][y];
        if (t == Tile.WALL) return true;
        if (t == Tile.CHASM && !level.explored[x][y]) return true;
        return false;
    }

    private static boolean isDoorAt(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Tile t = level.tiles[x][y];
        return t == Tile.DOOR || t == Tile.DOOR_OPEN;
    }

    private static Tile tileAt(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return null;
        return level.tiles[x][y];
    }

    private void drawTile(int visual, int x, int y) {
        if (visual < 0 || visual >= tiles.length) return;
        TextureRegion r = tiles[visual];
        if (r == null) return;
        batch.draw(r, x * (float) CELL, y * (float) CELL, CELL, CELL);
    }

    private static boolean inBounds(Level level, int x, int y) {
        return x >= 0 && y >= 0 && x < level.width && y < level.height;
    }

    private void drawItemsAt(Level level, int x, int y, Map<Long, List<Item>> itemsByCell) {
        List<Item> list = itemsByCell.get(cellKey(x, y));
        if (list == null) return;
        for (Item it : list) drawItem(level, it);
    }

    private void drawMobsAt(Level level, int x, int y, Map<Long, List<Mob>> mobsByCell) {
        List<Mob> list = mobsByCell.get(cellKey(x, y));
        if (list == null) return;
        for (Mob mob : list) drawMob(level, mob);
    }

    private void drawFxAt(Level level, int x, int y, Map<Long, List<Effect>> fxByCell) {
        List<Effect> list = fxByCell.get(cellKey(x, y));
        if (list == null) return;
        batch.setColor(Color.WHITE);
        for (Effect e : list) fxRenderer.drawEffect(level, e);
        batch.setColor(Color.WHITE);
    }

    private void drawItem(Level level, Item it) {
        if(it==null) return;
        if(it.location == null) {
            System.err .println("Item " + it.type + " has null location, skipping draw");
            return;
        }
        int x = it.location.tileX(), y = it.location.tileY();
        if (!inBounds(level, x, y) || !level.visible[x][y]) return;
        // Prefer the shared ItemSprites lookup so the on-floor icon matches the
        // inventory + action-bar art. Falls back to the glyph if no sprite is registered.
        com.badlogic.gdx.graphics.g2d.TextureRegion region = ItemSprites.regionFor(it);
        if (region != null) {
            // Same silhouette outline mobs/statues/lamps get — items on the floor
            // were the lone holdout. Helps loot pop visually against busy terrain.
            drawRegionOutline(region, x * (float) CELL, y * (float) CELL,
                    (float) CELL, (float) CELL);
            batch.setColor(Color.WHITE);
            // Source art is 32×32, world cell is 16×16 — libGDX scales 2:1 down with
            // nearest-neighbour filtering for crisp pixels.
            batch.draw(region, x * (float) CELL, y * (float) CELL,
                    (float) CELL, (float) CELL);
        } else {
            System.err.println("No sprite for item " + it.type + " at (" + x + ", " + y + ")");
            //here draw a placeholder
        }
        if (it.level > 0) drawItemLevelBadge(it, x, y);
    }

    /** Small {@code +N} marker drawn at the top-right corner of an item's tile to
     *  signal its enchant level. Yellow, half-scale font so it doesn't dominate the
     *  sprite. */
    private void drawItemLevelBadge(Item it, int x, int y) {
        float prevScale = font.getData().scaleX;
        font.getData().setScale(prevScale * 0.55f);
        font.setColor(1f, 0.92f, 0.4f, 1f);
        String text = "+" + it.level;
        com.badlogic.gdx.graphics.g2d.GlyphLayout layout =
                new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, text);
        // Top-right corner: x = right edge - text width, y = top of cell (font draws
        // baseline-aligned, hence the +height adjust).
        float bx = x * CELL + CELL - layout.width  - 1f;
        float by = y * CELL + CELL - 1f;
        font.draw(batch, text, bx, by);
        font.getData().setScale(prevScale);
        font.setColor(Color.WHITE);
    }

    private void drawMob(Level level, Mob mob) {
        int mx = mob.position.tileX(), my = mob.position.tileY();
        com.bjsp123.rl2.world.anim.MobAnimState as = animator.stateOf(mob);
        // Teleport-fade overrides: during the first half of the fade the mob is drawn at
        // its origin tile (alpha decreasing); during the second half at its destination
        // (alpha rising). Skip the visibility test against the actual logical position
        // for phase 1 — origin/destination might fall in different visibility regions and
        // we want the fade-out to play even if the destination tile is dark.
        float teleportAlpha = 1f;
        if (as.teleportFadeMs > 0) {
            int half = com.bjsp123.rl2.logic.MobSystem.TELEPORT_FADE_HALF_MS;
            int total = com.bjsp123.rl2.logic.MobSystem.TELEPORT_FADE_TOTAL_MS;
            if (as.teleportFadeMs > half) {
                // Departing: render at origin, alpha 1 → 0 across the first half.
                mx = as.teleportFromX;
                my = as.teleportFromY;
                teleportAlpha = (as.teleportFadeMs - half) / (float) (total - half);
            } else {
                // Arriving: render at destination (already mob.position), alpha 0 → 1.
                teleportAlpha = 1f - as.teleportFadeMs / (float) half;
            }
        }
        if (!inBounds(level, mx, my) || !level.visible[mx][my]) return;
        // Melee-lunge / hit-flinch offset: world y-up matches MobAnimState.animOffsetY()
        // (positive Y = north = up on screen).
        float ox = as.animOffsetX();
        float oy = as.animOffsetY();
        // Step-interpolation offset — slides the mob from its previous tile into its
        // current logical tile linearly over the animation. Suppressed during a teleport
        // fade (the mob isn't sliding, it's blinking from one cell to another).
        if (as.stepTotal > 0 && as.teleportFadeMs <= 0) {
            float t = Math.min(1f, as.stepFrame / (float) as.stepTotal);
            ox += as.stepFromDx * (1f - t) * CELL;
            oy += as.stepFromDy * (1f - t) * CELL;
        }
        // Player-only lift — sits the character a few pixels off the tile floor so they
        // read as a figure standing on the ground rather than rooted into it.
        if (mob.behavior == Behavior.PLAYER) {
            oy += PLAYER_Y_LIFT;
        }
        // Live mobs render fully opaque; the death-fade lives on rgame's ghost list,
        // not on the mob itself (killMob removes the mob from level.mobs immediately).
        // Teleport fade still multiplies in.
        float alpha = teleportAlpha;
        if (alpha <= 0f) return;
        Sprite s = spriteForMob(mob);
        if (s != null) {
            drawMobSprite(s, mx, my, ox, oy, alpha);
        } else {
            System.err.println("No sprite for mob " + mob.mobType + " at (" + mx + ", " + my + ")");
            //placeholder drawn here?
        }
        if (com.bjsp123.rl2.logic.BuffSystem.hasBuff(mob,
                com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE)) {
            fxRenderer.drawFireOnMob(mob, mx, my, ox, oy);
        }
        // Equipped gems bob around the player's head. The orbit + bob math is purely
        // visual; we leave the rest of the per-mob block alone.
        if (mob.behavior == Behavior.PLAYER) {
            drawPlayerGems(mob, mx, my, ox, oy, alpha);
        }
        // Wounded enemy mobs get a small HP bar over their head. Skip the player (they
        // see HP via the HUD) and full-HP mobs.
        double maxHp = mob.effectiveStats().maxHp;
        if (mob.behavior != Behavior.PLAYER && maxHp > 0 && mob.hp < maxHp) {
            drawMobHpBar(mob, mx, my, ox, oy, alpha);
        }
    }

    /** Render any of the three gem slots (GEM1/GEM2/GEM3) that are filled, orbiting +
     *  bobbing above the player's head. Each gem occupies a fixed slot on a horizontal
     *  arc with a sine offset in y. The clock is the renderer's own real-time
     *  accumulator so motion is wall-clock smooth (independent of game ticks). */
    private void drawPlayerGems(Mob mob, int mx, int my, float ox, float oy, float alpha) {
        com.bjsp123.rl2.model.Item.ItemSlot[] gemSlots = {
                com.bjsp123.rl2.model.Item.ItemSlot.GEM1,
                com.bjsp123.rl2.model.Item.ItemSlot.GEM2,
                com.bjsp123.rl2.model.Item.ItemSlot.GEM3
        };
        java.util.List<com.bjsp123.rl2.model.Item> gems = new java.util.ArrayList<>(3);
        for (com.bjsp123.rl2.model.Item.ItemSlot s : gemSlots) {
            com.bjsp123.rl2.model.Item g = mob.inventory.equipped(s);
            if (g != null) gems.add(g);
        }
        int n = gems.size();
        if (n <= 0) return;
        // Centre the cluster on the player sprite's vertical middle so the GEM2 slot
        // (the centre orbit position when 3 gems are equipped) sits over the player's
        // chest rather than above their head. Horizontal centre is the tile centre.
        float cx     = mx * CELL + CELL * 0.5f + ox;
        float midY   = my * CELL + CELL * 1.55f + oy + PLAYER_Y_LIFT;
        float t      = (float) ((System.nanoTime() / 1_000_000L) % 1_000_000L) / 1000f;
        float iconSize = CELL * 0.6f;
        // Wider spread + larger bob amplitude so the gems are clearly orbiting around
        // the player rather than huddled overhead.
        float spread    = CELL * 0.45f;
        float bobAmp    = 2.0f;
        for (int i = 0; i < n; i++) {
            // Spread n gems evenly over [-spread, +spread]; for a single gem (n==1),
            // u = 0 (centered).
            float u = (n == 1) ? 0f : ((i / (float) (n - 1)) - 0.5f) * 2f;
            float bobPhase = (i * 1.7f) + t * 1.6f;
            float bx = u * spread;
            float by = (float) Math.sin(bobPhase) * bobAmp;
            float drawX = cx + bx - iconSize * 0.5f;
            float drawY = midY - iconSize * 0.5f + by;
            if(i==0 || i==n-1) drawY -= CELL * 0.25f; //lower the ones at the edge
            com.badlogic.gdx.graphics.g2d.TextureRegion r = GemSprites.regionFor(gems.get(i));
            if (r == null) continue;
            batch.setColor(1f, 1f, 1f, alpha);
            batch.draw(r, drawX, drawY, iconSize, iconSize);
        }
        batch.setColor(Color.WHITE);
    }

    /** Thin horizontal HP bar over a wounded mob's head. Red fill on a black backing,
     *  width matches the mob's tile, drawn 4 px above the tile top so it doesn't
     *  collide with the sprite or the mob glyph. */
    private void drawMobHpBar(Mob mob, int mx, int my, float ox, float oy, float alpha) {
        float frac = (float) Math.max(0, mob.hp) / (float) mob.effectiveStats().maxHp;
        if (frac > 0.999f) return;
        float barW = CELL - 2f;          // 14 px — leaves a 1-px gutter on each side
        float barH = 2f;
        float bx   = mx * CELL + 1f + ox;
        float by   = my * CELL + CELL + 2f + oy;
        // Backing — dark frame so the bar is legible against any tile.
        batch.setColor(0f, 0f, 0f, 0.85f * alpha);
        batch.draw(whiteRegion, bx - 1f, by - 1f, barW + 2f, barH + 2f);
        // Empty fill behind the red — flat dim red so the loss reads as missing HP
        // rather than nothing.
        batch.setColor(0.35f, 0.05f, 0.05f, alpha);
        batch.draw(whiteRegion, bx, by, barW, barH);
        // Filled portion.
        batch.setColor(0.85f, 0.18f, 0.18f, alpha);
        batch.draw(whiteRegion, bx, by, barW * frac, barH);
        batch.setColor(Color.WHITE);
    }

    /**
     * Draw a mob so its VISIBLE silhouette is exactly {@link #MOB_VISIBLE_W} × {@link
     * #MOB_VISIBLE_H} on screen, regardless of how much blank canvas its source frame has.
     * Width and height scale independently (non-uniform) so skinny sprites and chubby ones
     * both hit the same footprint. The visible silhouette is recentred on the tile by
     * shifting the draw origin by {@code visibleLeft * scaleX}. A semi-transparent oval
     * shadow is drawn first, anchored at the baseline.
     */
    private void drawMobSprite(Sprite s, int gx, int gy, float offsetX, float offsetY, float alpha) {
        // "Natural" sprites (large blobs etc.) draw at source scale; everything else gets
        // normalised to MOB_VISIBLE_W × MOB_VISIBLE_H so silhouettes read consistently.
        float scaleX = s.natural ? 1f : MOB_VISIBLE_W / (float) s.visibleW;
        float scaleY = s.natural ? 1f : MOB_VISIBLE_H / (float) s.visibleH;
        float dw = s.w * scaleX;
        float dh = s.h * scaleY;
        float yAdj = s.yAdjust * scaleY;
        // Offsets (from the lunge / flinch animation) shift both the mob and its shadow
        // together — the whole silhouette leans into the strike or recoils from a hit.
        float tileCenterX = gx * CELL + CELL / 2f + offsetX;
        float baselineY   = gy * CELL + ENTITY_Y_OFFSET + offsetY;

        // Centre the visible silhouette horizontally on the tile. Source center-x of the
        // silhouette is (visibleLeft + visibleW/2), which scales to that × scaleX in draw px;
        // the frame's draw origin (left edge) therefore sits tileCenterX − that amount.
        float silhouetteCenterScaled = (s.visibleLeft + s.visibleW / 2f) * scaleX;
        float drawX = tileCenterX - silhouetteCenterScaled;
        float drawY = baselineY + yAdj;

        // Mob and its shadow share an alpha so the death flicker / fade dims both together.
        batch.setColor(1f, 1f, 1f, alpha);
        // Shadow width tracks the silhouette plus a small margin so wide mobs (blobs) get a
        // wide shadow and narrow mobs don't look pinpointed at the feet.
        float shadowW = s.visibleW * scaleX + SHADOW_EXTRA_W;
        batch.draw(shadowTex, tileCenterX - shadowW / 2f, baselineY - SHADOW_H / 2f,
                shadowW, SHADOW_H);
        // Black silhouette outline — 8 radial taps at user-configurable width and
        // darkness (see MobOutline). World-pixel offsets are floats so the rim can
        // be thinner than the game's integer pixel grid; at the default zoom
        // (~0.35) one world pixel covers ~3 screen pixels, so a 0.6 wp outline
        // reads as ~2 screen pixels regardless of source-texel snapping.
        // SpriteBatch multiplies the sprite's RGB by the batch colour, so RGB=0
        // zeroes out the original colour while preserving the alpha mask shape;
        // where the original sprite is transparent, the offset draws are also
        // transparent — so the rim only "shows" around the silhouette's edge.
        // Death-fade alpha modulates it too.
        float outlineW = com.bjsp123.rl2.ui.skin.MobOutline.width();
        float outlineA = com.bjsp123.rl2.ui.skin.MobOutline.darkness() * alpha;
        if (outlineA > 0f && outlineW > 0f) {
            ensureOutlineTaps(outlineW);
            Texture mobTex = s.region.getTexture();
            setOutlineFilter(mobTex, true);
            batch.setColor(0f, 0f, 0f, outlineA);
            for (int i = 0; i < outlineTaps; i++) {
                batch.draw(s.region,
                        drawX + outlineDx[i] * outlineW,
                        drawY + outlineDy[i] * outlineW,
                        dw, dh);
            }
            setOutlineFilter(mobTex, false);
        }
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(s.region, drawX, drawY, dw, dh);
        batch.setColor(Color.WHITE);
    }

    /** Swap a sprite-sheet texture's filter to Linear (smooth) or back to Nearest
     *  (crisp), flushing the batch on either side so the change actually takes
     *  effect for the outline pass without affecting the main sprite draw. Linear
     *  filtering on the offset stamps gives the silhouette boundary a sub-texel
     *  smoothing band — visually higher-res than the source pixel grid — which is
     *  what makes thick outlines read as smooth curves instead of chunky pixel
     *  staircases. The main sprite is drawn AFTER (on top), so the inside of the
     *  silhouette stays at the crisp Nearest-filtered look.
     *
     *  <p>When the user has disabled outline smoothing
     *  ({@link com.bjsp123.rl2.ui.skin.MobOutline#smooth()} == false), this is a
     *  no-op — the outline taps draw with whatever filter the texture already had
     *  (Nearest in normal use), keeping the original pixel-aligned look. */
    private void setOutlineFilter(Texture tex, boolean enterOutlinePass) {
        if (tex == null) return;
        if (!com.bjsp123.rl2.ui.skin.MobOutline.smooth()) return;
        batch.flush();
        Texture.TextureFilter f = enterOutlinePass ? Texture.TextureFilter.Linear
                                                   : Texture.TextureFilter.Nearest;
        tex.setFilter(f, f);
    }

    /** Generic radial-tap silhouette outline for any TextureRegion-based sprite
     *  (statues, the lamp ornament, items, etc). The destination rect should be
     *  the same one the actual draw uses — for flipped sprites the negative
     *  width/height is preserved so the outline mirrors the silhouette. The
     *  source texture's filter is briefly swapped to Linear so the outline edge
     *  reads at sub-texel resolution; see {@link #setOutlineFilter}. */
    private void drawRegionOutline(TextureRegion r, float x, float y, float w, float h) {
        float ow = com.bjsp123.rl2.ui.skin.MobOutline.width();
        float oa = com.bjsp123.rl2.ui.skin.MobOutline.darkness();
        if (ow <= 0f || oa <= 0f || r == null) return;
        ensureOutlineTaps(ow);
        Texture tex = r.getTexture();
        setOutlineFilter(tex, true);
        batch.setColor(0f, 0f, 0f, oa);
        for (int i = 0; i < outlineTaps; i++) {
            batch.draw(r, x + outlineDx[i] * ow, y + outlineDy[i] * ow, w, h);
        }
        setOutlineFilter(tex, false);
        batch.setColor(Color.WHITE);
    }

    /** Texture-based variant of {@link #drawRegionOutline} — used for the
     *  vegetation extracted Textures (grass / mushroom / fire-skipped) which the
     *  draw loop hands out as raw {@link Texture} not {@link TextureRegion}. */
    private void drawTextureOutline(Texture tex, float x, float y, float w, float h) {
        float ow = com.bjsp123.rl2.ui.skin.MobOutline.width();
        float oa = com.bjsp123.rl2.ui.skin.MobOutline.darkness();
        if (ow <= 0f || oa <= 0f || tex == null) return;
        ensureOutlineTaps(ow);
        setOutlineFilter(tex, true);
        batch.setColor(0f, 0f, 0f, oa);
        for (int i = 0; i < outlineTaps; i++) {
            batch.draw(tex, x + outlineDx[i] * ow, y + outlineDy[i] * ow, w, h);
        }
        setOutlineFilter(tex, false);
        batch.setColor(Color.WHITE);
    }

    /** Texture-with-source-rect variant — used by the tree canopy / trunk halves
     *  which slice the 32×64 tree texture into upper and lower 32×32 sub-rects. */
    private void drawTextureOutlineSrc(Texture tex, float x, float y, float w, float h,
                                       int srcX, int srcY, int srcW, int srcH) {
        float ow = com.bjsp123.rl2.ui.skin.MobOutline.width();
        float oa = com.bjsp123.rl2.ui.skin.MobOutline.darkness();
        if (ow <= 0f || oa <= 0f || tex == null) return;
        ensureOutlineTaps(ow);
        setOutlineFilter(tex, true);
        batch.setColor(0f, 0f, 0f, oa);
        for (int i = 0; i < outlineTaps; i++) {
            batch.draw(tex,
                    x + outlineDx[i] * ow, y + outlineDy[i] * ow,
                    w, h, srcX, srcY, srcW, srcH, false, false);
        }
        setOutlineFilter(tex, false);
        batch.setColor(Color.WHITE);
    }

    /**
     * Build a soft-edged oval shadow as a small RGBA texture. The source is drawn at arbitrary
     * display size with linear filtering, giving a blurry ellipse. Parabolic alpha falloff
     * (1 − r²) is plenty soft without needing a full blur pass. {@code peakAlpha} is the alpha
     * at the center of the ellipse — mob shadows use a light value; lamp shadows use a
     * heavier one to sell the lamp as physically planted on the floor.
     */
    private static Texture makeShadowTexture(int w, int h, float peakAlpha) {
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(0, 0, 0, 0);
        p.fill();
        float cx = (w - 1) / 2f;
        float cy = (h - 1) / 2f;
        float rx = w / 2f;
        float ry = h / 2f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = (x - cx) / rx;
                float dy = (y - cy) / ry;
                float falloff = Math.max(0f, 1f - (dx * dx + dy * dy));
                int alpha = Math.round(falloff * peakAlpha * 255f);
                if (alpha > 0) p.drawPixel(x, y, alpha); // RGBA8888: R=G=B=0, A=alpha
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return t;
    }

    /**
     * Pick the right frame for a mob, dispatching purely on {@link Mob#mobType}. Every
     * non-PLAYER species comes from mobs.png as a left-facing pose mirrored at draw time.
     * The PLAYER case consults {@link Mob#characterClass} since the three classes share
     * one {@link MobType#PLAYER} but use different sprite slots.
     */
    private Sprite spriteForMob(Mob mob) {
        int f = mob.facingEast ? 1 : 0;
        if (mob.characterClass != null) return playerSprite(mob, f);
        if (mob.mobType == null) return null;
        Sprite[] pair = speciesFacing.get(mob.mobType);
        return pair == null ? null : pair[f];
    }

    private Sprite playerSprite(Mob mob, int f) {
        CharacterClass cls = mob.characterClass;
        if (cls == CharacterClass.ROGUE) return rogueFacing[f];
        if (cls == CharacterClass.MAGE)  return mageFacing[f];
        return warriorFacing[f];
    }


    @Override
    public void dispose() {
        if (batch        != null) batch.dispose();
        if (font         != null) font.dispose();
        // Atlas-derived textures are owned by their respective sprite-source helpers
        // (MobSprites, TileSprites, SurfaceSprites). They live for the JVM lifetime
        // and aren't disposed by an individual renderer instance.
        if (whiteTex     != null) whiteTex.dispose();
        if (shadowTex    != null) shadowTex.dispose();
        if (lampShadowTex != null) lampShadowTex.dispose();
        if (wallBaseShadowTex != null) wallBaseShadowTex.dispose();
        if (floorShadowVertTex != null) floorShadowVertTex.dispose();
        if (floorShadowHorzTex != null) floorShadowHorzTex.dispose();
        if (surfaceMaskShader != null) surfaceMaskShader.dispose();
        fog.dispose();
    }
}
