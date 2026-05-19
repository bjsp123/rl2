package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.ui.v2.UIVars;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.TileQuery;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.List;
import com.bjsp123.rl2.world.anim.MobAnimState;
import com.bjsp123.rl2.logic.TurnSystem;

/**
 * Tile renderer. Atlas knowledge lives in the per-sheet sprite classes, not here:
 * <ul>
 *   <li>Terrain ({@code sprites/terrain_<theme>.png}) - {@link TileSprites}.</li>
 *   <li>Mobs ({@code sprites/mobs_simple.png}) - {@link MobSprites}.</li>
 *   <li>Surfaces / vegetation / fire - {@link SurfaceSprites}.</li>
 *   <li>Items / staff / gems - {@link ItemSprites}, {@link GemSprites}.</li>
 * </ul>
 * This class consumes those regions to compose four render passes:
 * <ol>
 *   <li><b>Floors + chasm</b> (any order). Base floor tile, stair underlays, chasm
 *       black fill, and the "dripping edge" chasm tile pulled from the north neighbor.</li>
 *   <li><b>Surface pass</b> (water / blood / oil / ice). Lifted out of the per-cell
 *       loop so the mask shader binds exactly once.</li>
 *   <li><b>Per-cell content</b>, north->south, west->east. Floor-edge shadow -> wall /
 *       door body + wall-base shadow + rear-corner shadows -> rear vegetation -> lamp ->
 *       items -> mobs -> front vegetation (tucks over mob feet) -> fx. Fx run even on
 *       unexplored tiles so the player sees their own projectiles.</li>
 *   <li><b>Wall + door tops</b>, north->south. Overhangs and internal-wall caps painted
 *       on the cell to the NORTH of each wall / door so tall scenery clips anything
 *       behind it.</li>
 * </ol>
 * Fog of war then runs once as a soft-edge overlay with an additive lamp-light layer.
 */
public class DefaultLevelRenderer implements LevelRenderer {

    /** Liquid tile size in source pixels - matches {@link SurfaceSprites#LIQUID_TILE}.
     *  The shader's per-cell UV math divides world coordinates by this to get a UV
     *  that wraps once per tile. */
    private static final float WATER_TEX_SIZE = SurfaceSprites.LIQUID_TILE;
    /** Speed of liquid texture drift. Slow on purpose - water/blood/oil should look
     *  like a gentle current, not a river. */
    private static final float WATER_SCROLL_PX_PER_SEC = 1.2f;

    private static final int CELL = 16;
    /** Extra off-camera tile margin so tall props, outlines, particles, and edge shadows
     *  do not pop at the viewport boundary. */
    private static final int VIEW_CULL_PAD_TILES = 3;
    /** Pixels above the cell's bottom edge where every drawable object's baseline sits -
     *  mobs, items, effects, and terrain props (lamps, statues, throne, altar) all rest
     *  on this line so figures read as standing on the floor rather than embedded in it. */
    private static final int ENTITY_Y_OFFSET = 4;

    /** Uniform on-screen size for every mob's VISIBLE silhouette (not including blank padding). */
    private static final int MOB_VISIBLE_W = 16;
    private static final int MOB_VISIBLE_H = 20;

    // Set to false to suppress floor-edge and wall-base gradient shadows (profiling / A-B testing).
    static boolean DRAW_EDGE_SHADOWS = true;
    // Set to false to suppress fog overlay (profiling / A-B testing).
    static boolean DRAW_FOG = true;
    // Set to false to suppress floor and chasm tile draws (profiling / A-B testing).
    static boolean DRAW_FLOORS = true;
    // Set to false to suppress wall body and wall-top draws (profiling / A-B testing).
    static boolean DRAW_WALLS = true;

    /** Width of the thin shadow strip painted along the wall-facing edges of floor tiles. The
     *  strip uses a 4-pixel alpha gradient texture (opaque at the wall, fading into the floor). */
    private static final float FLOOR_SHADOW_PX    = 3f;
    /** Height of the gradient shadow painted across the base of a wall with floor to its south. */
    private static final float WALL_BASE_SHADOW_PX = 6f;

    // Per-theme shadow tints - cached so shadow passes don't allocate a new Color per cell.
    // Crystal: pure black; Concrete: warm sodium-amber bias; Straightforward: cool cyan bias.
    private static final Color SHADOW_BLACK   = new Color(0f, 0f, 0f, 0.65f);
    /** Black with a faint warm brown bias - concrete tunnels and brutalist interiors
     *  sit in dim sodium-amber light, so shadows lean warm rather than neutral.
     *  Used for the CONCRETE theme. */
    private static final Color SHADOW_CONCRETE = new Color(0.06f, 0.04f, 0.02f, 0.65f);
    /** Black with a hint of cyan - paired with the STRAIGHTFORWARD theme so its
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
     * Width of the "rear corner shadows" - thin vertical bands painted along the left or right
     * edge of a wall tile that sits directly above a floor and has a perpendicular wall coming
     * out of the diagonally-down corner. Visually fakes the contact shadow of the rear-corner
     * wall against the side of the horizontal wall section.
     */
    private static final float WALL_REAR_CORNER_SHADOW_PX = 3f;
    /** Height of the rear-corner shadow band, measured from the bottom of the wall cell
     *  upward. The band covers only the lower portion of the wall - the top 5 pixels are
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
     *  taller, and noticeably darker than the mob shadow - otherwise the lamp reads as
     *  floating above the floor. */
    private static final int   LAMP_SHADOW_W         = 30;
    private static final int   LAMP_SHADOW_H         = 7;
    private static final float LAMP_SHADOW_MAX_ALPHA = 0.80f;

    private SpriteBatch     batch;
    private BitmapFont      font;
    /** Per-cell terrain regions for the active level's theme - borrowed from
     *  {@link TileSprites#regionsFor} at the top of every {@link #render}. */
    private TextureRegion[] tiles;
    /** Per-frame ornament regions for the active theme, borrowed from {@link TileSprites}
     *  at the top of {@link #render} so draw helpers don't have to look the level up. */
    private TextureRegion currentSmallStatue;
    private TextureRegion currentLargeStatue;
    private TextureRegion currentLampOrnament;
    private TextureRegion currentStairsUp;
    private TextureRegion currentStairsDown;
    private TextureRegion currentAltar;
    private TextureRegion currentThrone;
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
    private TextureRegion   grassTexA, grassTexB;
    private TextureRegion   mushroomTexA, mushroomTexB;
    private TextureRegion   treeTexA, treeTexB;
    /** Two painted 8-frame fire animation sheets - each 256x48 (8 frames of 32x48). Held
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
    /** Single 128×16 atlas packing all baked shadow and utility regions.  Nearest filter so
     *  packed 1-px-wide gradient strips don't bleed into transparent neighbours when sampled at
     *  the edges of a stretched quad. All pixels are stored WHITE (1,1,1,alpha) so the batch
     *  color is free to tint them - use BLACK batch for opaque-black oval shadows, and use
     *  {@link #getShadowColor} for theme-tinted gradient shadows. */
    private Texture         utilityAtlas;
    private TextureRegion   shadowRegion;
    private TextureRegion   lampShadowRegion;
    private TextureRegion   wallBaseShadowRegion;
    private TextureRegion   floorShadowVertRegion;
    private TextureRegion   floorShadowHorzRegion;
    /** 16-way alpha mask tiles lifted from the bottom strip of surfaces.png. Index 0 is a
     *  synthesized solid-white tile (the art leaves variant 0 fully transparent). */
    private TextureRegion[] surfaceMaskTex;
    /** Custom shader that draws a scrolling surface modulated by a per-cell alpha mask. */
    private ShaderProgram   surfaceMaskShader;
    private float           waterTime;
    private float           bobTime;
    /** Reference to the world for cross-level lookups (currently used by the stair labels
     *  to read the destination level's depth + side). Set via {@link #setWorld}; null if
     *  the renderer is being used outside a full game (e.g. tests). */
    private com.bjsp123.rl2.model.World world;
    /** In-world animator providing per-mob {@link com.bjsp123.rl2.world.anim.MobAnimState}.
     *  Set via {@link #setAnimator} during PlayScreen init; required for all mob draws. */
    private com.bjsp123.rl2.world.anim.Animator animator;
    /** Runtime outline service. Owns the generated atlas and radial fallback policy. */
    private final OutlineRenderer outlines = new OutlineRenderer();
    private final GameFbo gameFbo = new GameFbo();
    /** Real-time accumulator for the stair-label fade animation, in seconds. Bumped each
     *  frame from {@link com.badlogic.gdx.Gdx#graphics}. */
    private float           stairLabelTime;
    private TextureRegion   whiteRegion;
    private final FogOverlay fog = new FogOverlay();

    private record TileBounds(int minX, int maxX, int minY, int maxY) {}

    /** All effect/particle/fire/sleep-Z rendering lives here. Constructed lazily in
     *  {@link #create()} once the underlying batch + font + fire textures are loaded. */
    private FxRenderer fxRenderer;

    /** Cached per-cell item index. Rebuilt lazily when {@link #indexesDirty} is true -
     *  items only move on game ticks (pickup, drop, throw) so between-tick frames can
     *  reuse the prior frame's index verbatim. */
    private LevelRenderIndexes.CellBuckets<Item> cachedItemsByCell;
    /** Cached per-cell mob index. Same caching scheme as {@link #cachedItemsByCell} -
     *  mobs' positions only change on ticks. The flicker / fade of a dying mob is a
     *  frame-counter bump inside {@link Mob}, not a position change, so it doesn't need
     *  the index rebuilt. */
    private LevelRenderIndexes.CellBuckets<Mob>  cachedMobsByCell;
    /** When true, {@link #cachedItemsByCell} and {@link #cachedMobsByCell} need to be
     *  repopulated from the current level. Set by {@link #markDirty()} and also on the
     *  very first render after creation. Fx are NOT cached - they get added / removed
     *  every frame via {@code advanceEffects}, so the fx index is rebuilt per frame. */
    private boolean indexesDirty = true;

    /**
     * One drawable entity frame.
     * <ul>
     *   <li>{@code w, h} - the source frame's total native pixel dimensions.</li>
     *   <li>{@code visibleW, visibleH} - pixel size of the non-transparent silhouette
     *       (bounding box of all opaque pixels). Mobs scale X by {@code visibleW} and Y by
     *       {@code visibleH} independently so every mob's silhouette lands at exactly
     *       {@link #MOB_VISIBLE_W} x {@link #MOB_VISIBLE_H} regardless of how much blank
     *       canvas the artist left around the character.</li>
     *   <li>{@code visibleLeft} - source-pixel x of the leftmost opaque column, used to
     *       recentre the silhouette on the tile after scaling (since side padding may be
     *       asymmetric).</li>
     *   <li>{@code yAdjust} - source-pixel offset added to the cell baseline when drawing:
     *       negative pulls the frame down to compensate for blank bottom rows so feet land
     *       on the shared baseline; positive lifts for flying mobs. Scales with the sprite
     *       at draw time.</li>
     * </ul>
     */
    private static final class Sprite {
        final TextureRegion region;
        final int w, h, visibleW, visibleH, visibleLeft, yAdjust;
        /** When true, {@link #drawMobSprite} draws this at its native pixel size instead of
         *  scaling the silhouette to {@link #MOB_VISIBLE_W} x {@link #MOB_VISIBLE_H}. Used
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

    /** Per-render pass timing and flush-count breakdown, populated every frame.
     *  Read by PlayScreen after {@link #render} returns to add granular metrics to
     *  the frame profiler. {@code flushXxx} counts SpriteBatch flushes (GL draw calls)
     *  within that pass; {@code nsXxx} is wall-clock nanoseconds. */
    public static final class RenderStats {
        public int flushPass1, flushSurface, flushPass3, flushPass4, flushFog, totalFlushes;
        public long nsPass1, nsSurface, nsPass3, nsPass4, nsFog;
    }
    private final RenderStats _stats = new RenderStats();
    public RenderStats lastRenderStats() { return _stats; }

    /** Extra pixels a flying mob hovers above the shared ground baseline. */
    private static final int FLYING_HOVER_PX = 4;

    /** Pixel Operator at 16 px with a 1-px black FreeType outline - used for
     *  in-world floating text (damage numbers, mob name labels). Held
     *  separately from the V2 UI fonts in {@link com.bjsp123.rl2.ui.v2.UiCtx}
     *  because the renderer destructively rescales it per-callsite, which
     *  would corrupt a shared font. */
    private static BitmapFont newWorldFont() {
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(
                Gdx.files.internal("ui/fonts/PixelOperator.ttf"));
        FreeTypeFontParameter p = new FreeTypeFontParameter();
        p.size           = 16;
        p.borderWidth    = 1f;
        p.borderColor    = Color.BLACK;
        p.borderStraight = true;
        p.minFilter      = Texture.TextureFilter.Nearest;
        p.magFilter      = Texture.TextureFilter.Nearest;
        BitmapFont f = gen.generateFont(p);
        gen.dispose();
        f.setUseIntegerPositions(true);
        return f;
    }

    @Override
    public void create() {
        batch = new SpriteBatch();
        font  = newWorldFont();

        // Player class poses.
        warriorFacing          = mobsFacingPair(CharacterClass.WARRIOR);
        mageFacing             = mobsFacingPair(CharacterClass.MAGE);
        rogueFacing            = mobsFacingPair(CharacterClass.ROGUE);
        // Every NPC species - driven entirely by the registry. Flying species
        // (bat, ghost, ...) get the floating y-lift; everyone else stands flat
        // on their tile. The registry is populated from {@code mobs.csv} at
        // bootstrap, so this loop picks up new species without code edits.
        for (String type : com.bjsp123.rl2.logic.Registries.mobTypes()) {
            com.bjsp123.rl2.logic.MobDefinition def =
                    com.bjsp123.rl2.logic.Registries.mob(type);
            Sprite[] pair = (def != null && def.flying)
                    ? mobsFloatingFacingPair(type)
                    : mobsFacingPair(type);
            speciesFacing.put(type, pair);
        }

        buildUtilityAtlas();

        // Surface / vegetation / mask / fire textures all come from SurfaceSprites
        // - the single owner of surfaces.png and the two fire sheets across the whole
        // game. We borrow Texture references; SurfaceSprites.disposeShared() releases
        // them, never this dispose().
        surfaceMaskTex = new TextureRegion[com.bjsp123.rl2.world.render.SurfaceSprites.MASK_VARIANTS];
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
        grassTexA    = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationRegionA(
                com.bjsp123.rl2.model.Level.Vegetation.GRASS);
        grassTexB    = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationRegionB(
                com.bjsp123.rl2.model.Level.Vegetation.GRASS);
        mushroomTexA = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationRegionA(
                com.bjsp123.rl2.model.Level.Vegetation.MUSHROOMS);
        mushroomTexB = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationRegionB(
                com.bjsp123.rl2.model.Level.Vegetation.MUSHROOMS);
        treeTexA = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationRegionA(
                com.bjsp123.rl2.model.Level.Vegetation.TREES);
        treeTexB = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationRegionB(
                com.bjsp123.rl2.model.Level.Vegetation.TREES);
        fire1Tex = com.bjsp123.rl2.world.render.SurfaceSprites.fire1Texture();
        fire2Tex = com.bjsp123.rl2.world.render.SurfaceSprites.fire2Texture();

        fxRenderer = new FxRenderer(batch, font, whiteRegion, fire1Tex, fire2Tex);

        registerOutlineFrames();
        outlines.rebuild();
    }

    private void registerOutlineFrames() {
        registerOutlineSprites(warriorFacing);
        registerOutlineSprites(mageFacing);
        registerOutlineSprites(rogueFacing);
        for (Sprite[] pair : speciesFacing.values()) registerOutlineSprites(pair);

        for (String type : com.bjsp123.rl2.logic.Registries.itemTypes()) {
            outlines.register(ItemSprites.regionFor(type));
        }

        for (Level.VisualTheme theme : Level.VisualTheme.values()) {
            TextureRegion[] themeTiles = TileSprites.regionsFor(theme);
            if (themeTiles != null) {
                for (TextureRegion region : themeTiles) outlines.register(region);
            }
            outlines.register(TileSprites.smallStatue(theme));
            outlines.register(TileSprites.largeStatue(theme));
            outlines.register(TileSprites.lampOrnament(theme));
            outlines.register(TileSprites.altar(theme));
            outlines.register(TileSprites.throne(theme));
        }

        outlines.register(grassTexA);
        outlines.register(grassTexB);
        outlines.register(mushroomTexA);
        outlines.register(mushroomTexB);
        outlines.register(treeTexA);
        outlines.register(treeTexB);
        registerTreeHalfOutline(treeTexA, 0);
        registerTreeHalfOutline(treeTexA, 1);
        registerTreeHalfOutline(treeTexB, 0);
        registerTreeHalfOutline(treeTexB, 1);
    }

    private void registerOutlineSprites(Sprite[] sprites) {
        if (sprites == null) return;
        for (Sprite sprite : sprites) {
            if (sprite != null) outlines.register(sprite.region);
        }
    }

    private void registerTreeHalfOutline(TextureRegion tree, int halfIndex) {
        if (tree == null || tree.getRegionHeight() <= 1) return;
        int half = tree.getRegionHeight() / 2;
        int srcY = tree.getRegionY() + halfIndex * half;
        int srcH = (halfIndex == 0) ? half : tree.getRegionHeight() - half;
        outlines.register(tree.getTexture(), tree.getRegionX(), srcY, tree.getRegionWidth(), srcH);
    }


    /** Wrap a {@link MobSprites} region in a {@link Sprite} at half source-pixel size -
     *  the sheet is authored at 2x display resolution so a 32-px source cell becomes a
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

    // Liquid + vegetation extraction lives in SurfaceSprites - the single owner of
    // surfaces.png-derived Textures. Both kinds (Linear+Repeat liquids, Nearest veg
    // sprites) come back from there as already-built Textures.

    /** Invalidate cached fog + per-cell item/mob indexes. Called by {@code PlayScreen}
     *  after game ticks and level transitions - between ticks we skip both rebuilds. */
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
        bobTime        += dt;

        // Bind the tile atlas matching this level's theme. TileSprites falls back to
        // CRYSTAL internally if a theme slipped through without a registered atlas.
        tiles               = TileSprites.regionsFor(level.theme);
        currentSmallStatue  = TileSprites.smallStatue(level.theme);
        currentLargeStatue  = TileSprites.largeStatue(level.theme);
        currentLampOrnament = TileSprites.lampOrnament(level.theme);
        currentStairsUp     = TileSprites.stairsUp(level.theme);
        currentStairsDown   = TileSprites.stairsDown(level.theme);
        currentAltar        = TileSprites.altar(level.theme);
        currentThrone       = TileSprites.throne(level.theme);
        TileBounds view = visibleTileBounds(level, camera);
        outlines.ensureCurrent();

        // Bucket every item / mob / effect into the cell it will draw in. Items and mobs
        // only shift on ticks (pickup / drop / throw / step), so their indexes are cached
        // across frames and rebuilt only when the PlayScreen flags {@link #indexesDirty}
        // via {@link #markDirty}. Effects are volatile - added / removed every frame by
        // {@code TurnSystem.advanceEffects} - so the fx index is rebuilt per frame.
        // Effects that span two tiles (missiles, thrown items) anchor at their SOUTHMOST
        // endpoint so the N->S loop paints them after every mob along their path.
        if (indexesDirty || cachedItemsByCell == null || cachedMobsByCell == null) {
            cachedItemsByCell = LevelRenderIndexes.itemsByCell(level);
            cachedMobsByCell  = LevelRenderIndexes.mobsByCell(level);
            indexesDirty = false;
        }
        LevelRenderIndexes.CellBuckets<Item>   itemsByCell = cachedItemsByCell;
        LevelRenderIndexes.CellBuckets<Mob>    mobsByCell  = cachedMobsByCell;

        LevelRenderIndexes.CellBuckets<Effect> fxByCell    = LevelRenderIndexes.effectsByCell(level, animator.stage);

        gameFbo.beginWorldPass(batch, camera);
        batch.setColor(Color.WHITE);

        // FOUR PASSES over the level:
        //   Pass 1 - FLOORS + CHASM. Every floor, stair glyph, chasm black fill, and chasm
        //            edge tile lands first so the rest of the scene can layer on top without
        //            ever peeking through to a floorless background.
        //   Pass 2 - SURFACES (water/blood/oil). Lifted out of the per-cell pass so the
        //            mask shader is bound exactly once for all surface cells instead of
        //            thrashing on/off per-cell. Sits between floors and shadows so liquid
        //            visually rests on the floor and tucks under wall-base shadows.
        //   Pass 3 - PER-CELL CONTENT (N->S). Shadows / wall body / vegetation / lamps /
        //            items / mobs / front veg / fx / cloud. Still one-tile-at-a-time so
        //            overlapping cases (grass tucking over feet, tall walls over mobs in
        //            the same cell, etc.) resolve consistently.
        //   Pass 4 - WALL + DOOR TOPS (N->S). Wall overhangs, internal-wall caps, N/S-facing
        //            door overhangs, sideways-door overhangs - every "ceiling" tile painted
        //            on top of the already-laid scene.
        // Fog then runs once after pass 4.
        long _t0 = System.nanoTime();
        if (DRAW_FLOORS) for (int y = view.minY; y <= view.maxY; y++) {
            for (int x = view.minX; x <= view.maxX; x++) {
                if (!level.explored[x][y]) continue;
                drawFloorAt(level, x, y);
                drawChasmEdgeAt(level, x, y);
            }
        }

        long _t1 = System.nanoTime(); int _f1 = batch.renderCalls;
        drawSurfacesPass(level, view);

        long _t2 = System.nanoTime(); int _f2 = batch.renderCalls;
        // Pass 3 is split into four sub-loops so each uses a single texture family,
        // eliminating the tile-atlas↔utility-atlas texture thrash that caused 80-260
        // flushes per frame:
        //   3A - shadow pre-pass  (utility atlas only): floor-edge gradients +
        //        static oval contact shadows (lamp / statue / throne)
        //   3B - wall bodies      (tile atlas only)
        //   3C - wall-base shadow (utility atlas only): drawn after all wall bodies so it
        //        darkens the bottom of each wall sprite; drawn before the content loop so
        //        mobs standing south of a wall still appear in front of the shadow
        //   3D - content          (tile atlas + mob/item atlases): everything else

        // 3A — shadow pre-pass (utility atlas)
        for (int y = view.maxY; y >= view.minY; y--) {
            for (int x = view.minX; x <= view.maxX; x++) {
                if (!level.explored[x][y]) continue;
                drawFloorEdgeShadowAt(level, x, y);
                drawContactShadowAt(level, x, y);
            }
        }

        // 3B — wall bodies (tile atlas)
        if (DRAW_WALLS) for (int y = view.maxY; y >= view.minY; y--) {
            for (int x = view.minX; x <= view.maxX; x++) {
                if (!level.explored[x][y]) continue;
                drawWallAt(level, x, y);
            }
        }

        // 3C — wall-base shadow (utility atlas)
        for (int y = view.maxY; y >= view.minY; y--) {
            for (int x = view.minX; x <= view.maxX; x++) {
                if (!level.explored[x][y]) continue;
                drawWallBaseShadowAt(level, x, y);
            }
        }

        // 3D — content (tile atlas + mob/item atlases)
        for (int y = view.maxY; y >= view.minY; y--) {
            for (int x = view.minX; x <= view.maxX; x++) {
                boolean explored = level.explored[x][y];
                if (explored) {
                    drawRearVegetationAt(level, x, y);
                    drawLampAt(level, x, y);
                    drawStairsAt(level, x, y);
                    drawStatueAt(level, x, y);
                    drawAltarAt(level, x, y);
                    drawThroneAt(level, x, y);
                    drawItemsAt(level, x, y, itemsByCell);
                    drawMobsAt(level, x, y, mobsByCell);
                    drawFrontVegetationAt(level, x, y);
                }
                // Fx run regardless of explored - magic missiles are intentionally drawn
                // across dark tiles so the player can see their own projectile. Each
                // effect's own draw code decides whether to honor level.visible[].
                drawFxAt(level, x, y, fxByCell);
            }
        }

        // Ghost pass - dying mobs (already removed from level.mobs by
        // killMob) still need a render path so their queued knockback
        // slide and death flicker / fade are visible. Each ghost holds
        // its kill-tile (gx, gy); the slide pulls it back toward start
        // via the same per-mob anim state the live mob render path uses.
        drawGhosts(level);

        // Foot-dust pass - drawn AFTER every mob and ghost so each cloud
        // lands on top of the player's sprite, obscuring its lower edge.
        drawAllDustClouds(level);

        // The Level#cloud layer renders as a swarm of CLOUD_PUFF particles
        // emitted per render frame from {@link Animator#emitCloudPuffs};
        // those flow through the normal effect pipeline, so there's no
        // dedicated cloud-tint pass here.

        long _t3 = System.nanoTime(); int _f3 = batch.renderCalls;
        if (DRAW_WALLS) for (int y = view.maxY; y >= view.minY; y--) {
            for (int x = view.minX; x <= view.maxX; x++) {
                if (!level.explored[x][y]) continue;
                drawWallOverlayAt(level, x, y);
            }
        }

        // Pulsing danger-symbol overlay on one-time doors. Sideways (E/W-facing)
        // doors render their sprite body 1 cell up from the door tile, so the
        // danger symbol shifts up by CELL to track with the visible door.
        TextureRegion dangerSym = SurfaceSprites.dangerSymbol();
        if (DRAW_WALLS && dangerSym != null) {
            float alpha = 0.45f + 0.45f * (float) Math.sin(System.currentTimeMillis() * Math.PI / 900.0);
            for (int y = view.maxY; y >= view.minY; y--) {
                for (int x = view.minX; x <= view.maxX; x++) {
                    if (!level.explored[x][y]) continue;
                    if (level.tiles[x][y] != Tile.ONETIME_DOOR) continue;
                    float dy = TileQuery.isSidewaysDoor(level, x, y) ? CELL : 0f;
                    batch.setColor(1f, 1f, 1f, alpha);
                    batch.draw(dangerSym, x * (float) CELL, y * (float) CELL + dy, CELL, CELL);
                }
            }
            batch.setColor(1f, 1f, 1f, 1f);
        }

        // Look-mode annotations layer - drawn above the world but under the fog overlay
        // so unseen tiles still darken normally. Only fires when the player is looking at
        // a mob; otherwise it's a no-op.
        if (lookedAtMob != null) drawLookAnnotations(level);

        long _t4 = System.nanoTime(); int _f4 = batch.renderCalls;
        if (DRAW_FOG) fog.render(batch);
        gameFbo.endWorldPass(batch);
        long _t5 = System.nanoTime();

        _stats.nsPass1      = _t1 - _t0; _stats.flushPass1    = _f1;
        _stats.nsSurface    = _t2 - _t1; _stats.flushSurface  = _f2 - _f1;
        _stats.nsPass3      = _t3 - _t2; _stats.flushPass3    = _f3 - _f2;
        _stats.nsPass4      = _t4 - _t3; _stats.flushPass4    = _f4 - _f3;
        _stats.nsFog        = _t5 - _t4; _stats.flushFog      = batch.renderCalls - _f4;
        _stats.totalFlushes = batch.renderCalls;
    }

    /**
     * Overlay text annotations driven by {@link #lookedAtMob}. Above the looked-at mob's
     * tile we print its state-of-mind ("asleep" / "awake" / "hiding" / "following"); above
     * every other visible mob we print a single-character attitude marker indicating how
     * the looked-at mob feels about it ({@code !} hostile red, {@code ?} fleeing yellow,
     * {@code *} neutral grey, {@code heart} ally green). Cleared when the look cursor leaves
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
                    default     -> { text = "*"; color = Color.LIGHT_GRAY; }
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
            case ASLEEP         -> TextCatalog.get("world.mobState.asleep");
            case AWAKE          -> TextCatalog.get("world.mobState.awake");
            case SEEKING_HIDING -> TextCatalog.get("world.mobState.fleeing");
            case HIDING         -> TextCatalog.get("world.mobState.hiding");
            case FOLLOWING      -> TextCatalog.get("world.mobState.following");
        };
    }

    /**
     * Floor layer for one cell - everything that should read as "floor". FLOOR / FLOOR_WOOD,
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
        // a continuous floor - picked from the neighbour to the N first, failing that the
        // neighbour to the W, failing that the default stone-floor variant. Matching the
        // neighbour's variant keeps wood-floored corridors reading as wood right up to
        // (and under) the door.
        if (TileQuery.isDoorAt(level, x, y)) {
            int variant = floorVisualAt(level, x, y + 1);   // N (y-up)
            if (variant < 0) variant = floorVisualAt(level, x - 1, y);
            if (variant < 0) variant = TileSprites.floorVariant(TileSprites.variantHash(x, y));
            drawTile(variant, x, y);
            //return;
        }
        // Stairs sit on top of a regular floor tile - the stair glyphs only carve out the
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
        // Regular FLOOR cells get an extra overlay pass for any cardinal /
        // diagonal neighbour that's FLOOR_SPECIAL - the overlays paint a
        // decorative seam on the base floor's edge facing the special patch.
        if (t == Tile.FLOOR) drawSpecialFloorEdges(level, x, y);
    }

    /** Overlay edge / corner sprites on top of a regular FLOOR cell when any
     *  of its 8 neighbours is FLOOR_SPECIAL. Cardinals draw "edge" overlays;
     *  diagonals draw "corner" overlays. Multiple overlays compose - a tile
     *  with both N and W special-floor neighbours plus the NW diagonal gets
     *  all three. {@link Tile#FLOOR_SPECIAL} cells themselves don't run this
     *  pass since their own sprite already carries the styled centre. */
    private void drawSpecialFloorEdges(Level level, int x, int y) {
        // Cardinals - y-up world: +y is north, +x is east.
        if (isSpecialFloor(level, x - 1, y    )) drawTile(TileSprites.specialFloorEdge(TileSprites.Edge.W), x, y);
        if (isSpecialFloor(level, x + 1, y    )) drawTile(TileSprites.specialFloorEdge(TileSprites.Edge.E), x, y);
        if (isSpecialFloor(level, x,     y - 1)) drawTile(TileSprites.specialFloorEdge(TileSprites.Edge.S), x, y);
        if (isSpecialFloor(level, x,     y + 1)) drawTile(TileSprites.specialFloorEdge(TileSprites.Edge.N), x, y);
        // Diagonals.
        if (isSpecialFloor(level, x + 1, y - 1)) drawTile(TileSprites.specialFloorCorner(TileSprites.Corner.SE), x, y);
        if (isSpecialFloor(level, x - 1, y - 1)) drawTile(TileSprites.specialFloorCorner(TileSprites.Corner.SW), x, y);
        if (isSpecialFloor(level, x + 1, y + 1)) drawTile(TileSprites.specialFloorCorner(TileSprites.Corner.NE), x, y);
        if (isSpecialFloor(level, x - 1, y + 1)) drawTile(TileSprites.specialFloorCorner(TileSprites.Corner.NW), x, y);
    }

    private boolean isSpecialFloor(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        return level.tiles[x][y] == Tile.FLOOR_SPECIAL;
    }

    /** Floor sprite that the cell at (x, y) would render - used by the door-underlay
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
     * Wall body for one cell - raised walls and door bodies. Runs after the surface layer
     * so liquid pools at a wall base don't paint over the wall itself. Chasm edges are drawn
     * in pass 1 by {@link #drawChasmEdgeAt} instead so every floor-level detail (floors, chasm
     * black fill, chasm edges) is committed before per-cell content starts layering on top.
     */
    private void drawWallAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        if (t != Tile.WALL && !t.isDoor()) return;
        int visual = terrainVisual(level, x, y);
        if (visual < 0) return;
        drawTile(visual, x, y);
    }

    /**
     * Chasm-edge tile for one cell - picks the "dripping edge" glyph for a CHASM cell based
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
        if (!DRAW_EDGE_SHADOWS) return;
        if (!level.tiles[x][y].isFloorLike()) return;
        batch.setColor(getShadowColor(level));
        float px = x * (float) CELL;
        float py = y * (float) CELL;
        if (isWallCell(level, x, y + 1)) {
            // Wall to north: strip at top of cell, opaque at top - flip vertical
            // gradient by drawing with negative height anchored at the cell's top.
            batch.draw(floorShadowVertRegion, px, py + CELL,
                       CELL, -FLOOR_SHADOW_PX);
        }
        if (isWallCell(level, x, y - 1)) {
            // Wall to south: strip at bottom of cell, opaque at bottom - natural orientation.
            batch.draw(floorShadowVertRegion, px, py, CELL, FLOOR_SHADOW_PX);
        }
        if (isWallCell(level, x - 1, y)) {
            // Wall to west: strip on left, opaque at left - natural orientation.
            batch.draw(floorShadowHorzRegion, px, py, FLOOR_SHADOW_PX, CELL);
        }
        if (isWallCell(level, x + 1, y)) {
            // Wall to east: strip on right, opaque at right - flip horizontally.
            batch.draw(floorShadowHorzRegion, px + CELL, py,
                       -FLOOR_SHADOW_PX, CELL);
        }
        batch.setColor(Color.WHITE);
    }

    /**
     * Builds the 128×16 utility atlas that packs all baked shadow and white-fill regions.
     * Nearest filtering prevents 1-px-wide gradient strips from bleeding into transparent
     * neighbours when the strip is stretched across a full cell width.
     *
     * <pre>
     * Atlas layout (all pixels WHITE 0xFFFFFF with varying alpha):
     *  ( 0, 0, 32, 8)  mob contact-shadow oval   – batch color BLACK for opaque black shadow
     *  (34, 0, 40,10)  lamp contact-shadow oval   – same
     *  (76, 0,  1, 8)  wall-base vertical gradient (opaque at Pixmap bottom = screen bottom)
     *  (79, 0,  1, 4)  floor-edge N/S gradient    (opaque at Pixmap bottom = screen bottom)
     *  (82, 0,  4, 1)  floor-edge E/W gradient    (opaque at Pixmap left  = screen left)
     *  (88, 0,  1, 1)  solid white pixel           – batch color drives final tint
     * </pre>
     */
    // Gradient strips are baked at 32× their logical pixel count so Linear filter
    // can interpolate smoothly across the 3–8 display-pixel shadow widths.
    private static final int GRAD_SCALE = 32;

    private void buildUtilityAtlas() {
        // Atlas layout (256×512, RGBA8888):
        //   (  0,  0) Mob oval shadow      32×8   — unchanged, Nearest-friendly edge fade
        //   ( 34,  0) Lamp oval shadow     40×10  — unchanged
        //   ( 76,  1) Wall gradient        1×(8*GRAD_SCALE)=1×256  content; padded cols 75,77 + rows 0,258
        //   ( 79,  1) Floor N/S gradient   1×(4*GRAD_SCALE)=1×128  content; padded cols 78,80 + rows 0,130
        //   ( 83,  1) Floor E/W gradient   (4*GRAD_SCALE)×1=128×1  content; padded rows 0,2 + cols 82,211
        //   (213,  0) White pixel          1×1
        final int WALL_GH = 8 * GRAD_SCALE;   // 256
        final int FNS_GH  = 4 * GRAD_SCALE;   // 128
        final int FEW_GW  = 4 * GRAD_SCALE;   // 128

        Pixmap p = new Pixmap(256, 512, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(0, 0, 0, 0);
        p.fill();

        // Ovals are in SpriteAtlas (shadowOvalRegion / lampShadowOvalRegion).

        // White pixel (clear of gradient area)
        p.drawPixel(213, 0, white(255));

        // Wall-base gradient: 1×WALL_GH, content at (76, 1)
        for (int y = 0; y < WALL_GH; y++) {
            p.drawPixel(76, 1 + y, white(Math.round((float) y / (WALL_GH - 1) * 255f)));
        }
        for (int y = 0; y < WALL_GH; y++) {          // left/right padding columns
            int rgba = p.getPixel(76, 1 + y);
            p.drawPixel(75, 1 + y, rgba);
            p.drawPixel(77, 1 + y, rgba);
        }
        for (int x = 75; x <= 77; x++) {             // top/bottom padding rows
            p.drawPixel(x, 0,            p.getPixel(76, 1));
            p.drawPixel(x, 1 + WALL_GH, p.getPixel(76, WALL_GH));
        }

        // Floor N/S gradient: 1×FNS_GH, content at (79, 1)
        for (int y = 0; y < FNS_GH; y++) {
            p.drawPixel(79, 1 + y, white(Math.round((float) y / (FNS_GH - 1) * 255f)));
        }
        for (int y = 0; y < FNS_GH; y++) {
            int rgba = p.getPixel(79, 1 + y);
            p.drawPixel(78, 1 + y, rgba);
            p.drawPixel(80, 1 + y, rgba);
        }
        for (int x = 78; x <= 80; x++) {
            p.drawPixel(x, 0,           p.getPixel(79, 1));
            p.drawPixel(x, 1 + FNS_GH, p.getPixel(79, FNS_GH));
        }

        // Floor E/W gradient: FEW_GW×1, content at (83, 1); 255 at left, 0 at right
        for (int x = 0; x < FEW_GW; x++) {
            p.drawPixel(83 + x, 1, white(Math.round((1f - (float) x / (FEW_GW - 1)) * 255f)));
        }
        for (int x = 0; x < FEW_GW; x++) {           // top/bottom padding rows
            int rgba = p.getPixel(83 + x, 1);
            p.drawPixel(83 + x, 0, rgba);
            p.drawPixel(83 + x, 2, rgba);
        }
        int fewRight = 83 + FEW_GW - 1;
        for (int y = 0; y <= 2; y++) {                // left/right padding columns
            p.drawPixel(82,          y, p.getPixel(83,       1));
            p.drawPixel(fewRight + 1, y, p.getPixel(fewRight, 1));
        }

        utilityAtlas = new Texture(p);
        utilityAtlas.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        p.dispose();

        SpriteAtlas.load();
        shadowRegion          = SpriteAtlas.shadowOvalRegion();
        lampShadowRegion      = SpriteAtlas.lampShadowOvalRegion();
        wallBaseShadowRegion  = new TextureRegion(utilityAtlas,  76,  1,       1, WALL_GH);
        floorShadowVertRegion = new TextureRegion(utilityAtlas,  79,  1,       1,  FNS_GH);
        floorShadowHorzRegion = new TextureRegion(utilityAtlas,  83,  1, FEW_GW,       1);
        whiteRegion           = new TextureRegion(utilityAtlas, 213,  0,       1,       1);
    }

    private static void drawOvalIntoPixmap(Pixmap p, int dstX, int dstY, int w, int h, float peakAlpha) {
        float cx = (w - 1) / 2f, cy = (h - 1) / 2f;
        float rx = w / 2f,       ry = h / 2f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = (x - cx) / rx, dy = (y - cy) / ry;
                int a = Math.round(Math.max(0f, 1f - dx * dx - dy * dy) * peakAlpha * 255f);
                if (a > 0) p.drawPixel(dstX + x, dstY + y, white(a));
            }
        }
    }

    /** Pack a WHITE pixel with the given alpha (0-255) into an RGBA8888 int. */
    private static int white(int alpha) {
        return 0xFFFFFF00 | (alpha & 0xFF);
    }

    /** A wall or a door - both read as solid wall sections for shadow purposes, regardless
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
     * south is explicitly excluded - a wall at the edge of a pit has nothing to shadow onto.
     */
    private void drawWallBaseShadowAt(Level level, int x, int y) {
        if (!DRAW_EDGE_SHADOWS) return;
        if (!isWallCell(level, x, y)) return;
        Tile south = tileAt(level, x, y - 1);
        if (south == null || south == Tile.CHASM || isWallCell(level, x, y - 1)) return;
        batch.setColor(getShadowColor(level));
        float px = x * (float) CELL;
        float py = y * (float) CELL;
        batch.draw(wallBaseShadowRegion, px, py, CELL, WALL_BASE_SHADOW_PX);

        // Rear corner shadows - thin vertical bands along the wall's left/right edge
        // when the floor at the south has a perpendicular wall stub at the opposite
        // diagonal. Only fires when south is a true floor tile, since chasm/wall to
        // the south already disqualified us above.
        if (south.isFloorLike()) {
            Tile sw = tileAt(level, x - 1, y - 1);
            Tile se = tileAt(level, x + 1, y - 1);
            if (sw == Tile.WALL) {
                // Wall is northeast of the SW wall stub -> shadow on this wall's LEFT edge.
                // Only the lower 11 px of the 16-px cell - keeps the top 5 px of the wall
                // clean so the cap doesn't read as fully darkened.
                batch.draw(floorShadowHorzRegion, px, py,
                           WALL_REAR_CORNER_SHADOW_PX, WALL_REAR_CORNER_SHADOW_H);
            }
            if (se == Tile.WALL) {
                // Wall is northwest of the SE wall stub -> shadow on this wall's RIGHT edge.
                batch.draw(floorShadowHorzRegion, px + CELL, py,
                           -WALL_REAR_CORNER_SHADOW_PX, WALL_REAR_CORNER_SHADOW_H);
            }
        }
        batch.setColor(Color.WHITE);
    }

    // Shore-mask extraction also lives in SurfaceSprites - see SurfaceSprites.maskTexture.

    /**
     * Shader that pairs each sampled pixel of the sprite (the mask tile) with a sample of a
     * secondary texture (the scrolling liquid, bound to TEXTURE1). Output RGB comes from the
     * liquid; output alpha is {@code 1 - mask.alpha} - i.e. opaque white in the mask means
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
     * Surface pass - draws every water/blood/oil cell on the level using the surface-mask
     * shader. Runs as a single dedicated pass right after floors are laid down, before
     * shadows / walls / vegetation / mobs / etc., so liquid sits on the floor and tucks
     * under wall-base shadows. Bundling the cells means the mask shader gets bound exactly
     * once for the whole level instead of swapped on/off per cell, and the liquid texture
     * (TEXTURE1) is rebound only when the surface kind changes between cells.
     */
    private void drawSurfacesPass(Level level, TileBounds view) {
        if (level.surface == null) return;
        // Cheap bail-out when the level has no surfaces at all - keeps the shader off the
        // GPU pipeline on dry levels.
        boolean any = false;
        outer:
        for (int x = view.minX; x <= view.maxX && !any; x++) {
            for (int y = view.minY; y <= view.maxY; y++) {
                if (level.explored[x][y] && level.surface[x][y] != null) { any = true; break outer; }
            }
        }
        if (!any) return;

        batch.setShader(surfaceMaskShader);
        surfaceMaskShader.setUniformi("u_surfaceTex", 1);
        Surface lastSurf = null;
        for (int y = view.minY; y <= view.maxY; y++) {
            for (int x = view.minX; x <= view.maxX; x++) {
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
     * {@link #vegRearLiftPx} (3-6 px above the tile floor).
     */
    private void drawRearVegetationAt(Level level, int x, int y) {
        // Tree canopies extend into the cell ABOVE their tile. Render order is top-down
        // (highest y first), so a tree at (x, y-1) needs its canopy painted in this cell's
        // rear-veg phase - that way it sits behind any mob that's standing in (x, y).
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

        TextureRegion region = rearVegTexture(v, x, y);
        if (region == null) return;
        int jx = vegJitterX(x, y);
        int lift = vegRearLiftPx(x, y);
        // Rear vegetation rides 3-6 px above the tile floor so the front sprite (drawn at
        // the base) reads as the foreground blades and the rear sprite reads as the body
        // poking up behind it.
        float dx = x * (float) CELL + jx;
        float dy = y * (float) CELL + lift;
        outlines.drawRegion(batch, region, dx, dy, CELL, CELL);
        batch.setColor(Color.WHITE);
        batch.draw(region, dx, dy, CELL, CELL);
    }

    /** Paint the upper (canopy) half of the tree-at-({@code treeX}, {@code treeY}) sprite
     *  into the cell directly above it (world (treeX, treeY+1)). Called from that cell's
     *  rear-veg phase so the canopy sits behind any mob standing on the cell above. The
     *  same vertical lift used for normal rear vegetation is applied so the trunk + canopy
     *  read as a single shifted sprite. */
    private void drawTreeCanopy(int treeX, int treeY) {
        TextureRegion region = rearVegTexture(Vegetation.TREES, treeX, treeY);
        if (region == null) return;
        int jx = vegJitterX(treeX, treeY);
        int lift = vegRearLiftPx(treeX, treeY);
        int half = region.getRegionHeight() / 2;
        float dx = treeX * (float) CELL + jx;
        float dy = (treeY + 1) * (float) CELL + lift;
        outlines.drawRegionSrc(batch, region, dx, dy, CELL, CELL,
                0, 0, region.getRegionWidth(), half);
        batch.setColor(Color.WHITE);
        batch.draw(region.getTexture(),
                dx, dy,
                CELL, CELL,
                region.getRegionX(), region.getRegionY(), // src origin: top of texture = canopy
                region.getRegionWidth(), half,
                false, false);
    }

    /** Paint the lower (trunk) half of the tree sprite at world (x, y), lifted by the
     *  shared 3-6 px rear-veg offset. */
    private void drawTreeTrunk(int x, int y) {
        TextureRegion region = rearVegTexture(Vegetation.TREES, x, y);
        if (region == null) return;
        int jx = vegJitterX(x, y);
        int lift = vegRearLiftPx(x, y);
        int half = region.getRegionHeight() / 2;
        float dx = x * (float) CELL + jx;
        float dy = y * (float) CELL + lift;
        outlines.drawRegionSrc(batch, region, dx, dy, CELL, CELL,
                0, half, region.getRegionWidth(), half);
        batch.setColor(Color.WHITE);
        batch.draw(region.getTexture(),
                dx, dy,
                CELL, CELL,
                region.getRegionX(), region.getRegionY() + half,
                region.getRegionWidth(), half,
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
     * the tile. Drawn at the tile's base - no Y jitter - so the foreground blades anchor
     * to the floor. The art is authored mostly transparent above the blade tops, so a
     * full-cell draw produces the "mob's feet tucked into grass" silhouette.
     */
    private void drawFrontVegetationAt(Level level, int x, int y) {
        Vegetation v = level.vegetation[x][y];
        if (v == null) return;
        // Fire is drawn whole in the rear pass - no front overlay (the flame shouldn't
        // tuck back over the mob's feet the way grass does).
        if (v == Vegetation.FIRE) return;
        TextureRegion region = frontVegTexture(v, x, y);
        if (region == null) return;
        // Front vegetation always sits at the tile's base (no Y lift, unlike the rear
        // pass) and renders the full sprite. The art for the front variants is authored
        // with mostly-transparent upper rows + foreground blades near the bottom, so a
        // full-cell draw naturally produces the "feet-tucked-into-grass" effect on the
        // tile's occupant. X jitter matches the rear sprite so the two layers line up
        // horizontally.
        int jx = vegJitterX(x, y);
        float dx = x * (float) CELL + jx;
        float dy = y * (float) CELL;
        outlines.drawRegion(batch, region, dx, dy, CELL, CELL);
        batch.setColor(Color.WHITE);
        batch.draw(region, dx, dy, CELL, CELL);
    }

    /** Rear-pass texture pick: each veg type has two source variants in surfaces.png; the
     *  per-tile {@link #vegRearVariantBit} hash picks which one renders so adjacent tiles
     *  break up the grid. The chosen sprite goes behind any mob standing on the tile. */
    private TextureRegion rearVegTexture(Vegetation v, int x, int y) {
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
    private TextureRegion frontVegTexture(Vegetation v, int x, int y) {
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
     * passes: rear uses {@link #vegRearLiftPx} (3-6 px above base), front always sits at
     * the base.
     */
    private static int vegJitterX(int x, int y) {
        int span = VEG_JITTER_PX * 2 + 1;
        int h = x * 73856093 ^ y * 19349663;
        return Math.floorMod(h, span) - VEG_JITTER_PX;
    }

    /**
     * Lamp overlay for one cell - the floor base was already drawn by {@link #drawFloorAt};
     * here we overlay the lamp sprite from the terrain atlas so the stem rises into the
     * cell above. Drawn before items/mobs so anything standing on a lamp tile renders on
     * top of it. Falls back to a no-op if the active theme didn't ship a lamp ornament.
     */
    private void drawLampAt(Level level, int x, int y) {
        if (level.tiles[x][y] != Tile.LAMP) return;
        if (currentLampOrnament == null) return;
        batch.setColor(Color.WHITE);
        // 1 cell wide x 2 cells tall, anchored at the floor cell so the upper half
        // overhangs into the cell above (matching the source 32x64 art). Y is lifted
        // to the shared baseline so the lamp's foot sits on the same line as mobs/items.
        float dx = x * (float) CELL;
        float dy = y * (float) CELL + ENTITY_Y_OFFSET;
        outlines.drawRegion(batch, currentLampOrnament, dx, dy, CELL, 2f * CELL);
        batch.setColor(Color.WHITE);
        batch.draw(currentLampOrnament, dx, dy, CELL, 2f * CELL);
    }

    /** Width of the contact shadow under a small statue, in screen pixels. */
    private static final int STATUE_SMALL_SHADOW_W = 10;
    /** Width of the contact shadow under a tall statue. Wider than the small variant so a
     *  large pedestal silhouette reads as planted on the floor. */
    private static final int STATUE_LARGE_SHADOW_W = 16;

    /**
     * Stair-ladder overlay for one cell. Source is 2x2 atlas cells (64x64 source) and is
     * drawn as 2x2 world cells (32x32 px), anchored at the bottom of the stair cell with
     * the ladder horizontally centered on the cell. The top half overhangs into the cell
     * to the north - same convention as the lamp ornament. Floor underlay was painted in
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
     *  on the {@link #world}, and draws "up/down lvl N W/E/?" with a sine-modulated alpha so the
     *  writing fades in and out. Drawn twice - a wide dim outer pass + a tight bright inner
     *  pass - for a soft halo. No-op if the world reference isn't set or the cell isn't a
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

        // Outer glow - dim warm halo at 1.6x scale.
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
     * Altar overlay for one cell. The altar tile sits at the centre of a 3-wide
     * sprite - the source spans cols 12..14 of the atlas, drawn anchored so the
     * sprite extends one cell west and one cell east from the anchor cell.
     * No L/R flip - the altar reads the same from either side.
     */
    private void drawAltarAt(Level level, int x, int y) {
        if (level.tiles[x][y] != Tile.ALTAR) return;
        if (currentAltar == null) return;
        float dx = (x - 1) * (float) CELL;
        float dy = y * (float) CELL + ENTITY_Y_OFFSET;
        float dw = 3f * CELL;
        float dh = CELL;
        outlines.drawRegion(batch, currentAltar, dx, dy, dw, dh);
        batch.setColor(Color.WHITE);
        batch.draw(currentAltar, dx, dy, dw, dh);
    }

    /**
     * Throne overlay for one cell. 1-wide x 2-tall sprite anchored at the floor
     * cell with the upper half overhanging into the cell to the north (same
     * convention as the lamp / large statue). Source art faces west; the
     * {@code THRONE_R} variant is drawn with negative width so one sprite covers
     * both facings.
     */
    private void drawThroneAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        if (t != Tile.THRONE_L && t != Tile.THRONE_R) return;
        if (currentThrone == null) return;
        boolean flip = (t == Tile.THRONE_R);
        batch.setColor(Color.WHITE);
        float dx = x * (float) CELL;
        float dy = y * (float) CELL + ENTITY_Y_OFFSET;
        float dw = CELL;
        float dh = 2f * CELL;
        if (flip) {
            outlines.drawRegion(batch, currentThrone, dx + dw, dy, -dw, dh);
            batch.setColor(Color.WHITE);
            batch.draw(currentThrone, dx + dw, dy, -dw, dh);
        } else {
            outlines.drawRegion(batch, currentThrone, dx, dy, dw, dh);
            batch.setColor(Color.WHITE);
            batch.draw(currentThrone, dx, dy, dw, dh);
        }
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
        batch.setColor(Color.WHITE);
        float dx = x * (float) CELL;
        float dy = y * (float) CELL + ENTITY_Y_OFFSET;
        // Display dimensions: small -> 1 cell square; large -> 1 cell wide x 2 cells tall.
        float dw = CELL;
        float dh = small ? CELL : 2f * CELL;
        if (flip) {
            outlines.drawRegion(batch, r, dx + dw, dy, -dw, dh);
            batch.setColor(Color.WHITE);
            batch.draw(r, dx + dw, dy, -dw, dh);
        } else {
            outlines.drawRegion(batch, r, dx, dy, dw, dh);
            batch.setColor(Color.WHITE);
            batch.draw(r, dx, dy, dw, dh);
        }
    }

    /** Soft elliptical contact shadow under a statue at the base of its tile. Re-uses the
     *  mob shadow texture (lighter than the lamp shadow) so the figure reads as standing
     *  on the floor without looking as heavy as a planted lamp. */
    private void drawStatueShadow(int gx, int gy, boolean large) {
        float cx = gx * CELL + CELL / 2f;
        float by = gy * CELL + ENTITY_Y_OFFSET;
        int shadowW = large ? STATUE_LARGE_SHADOW_W : STATUE_SMALL_SHADOW_W;
        batch.setColor(Color.BLACK);
        batch.draw(shadowRegion, cx - shadowW / 2f, by - SHADOW_H / 2f, shadowW, SHADOW_H);
        batch.setColor(Color.WHITE);
    }

    /**
     * Soft elliptical shadow cast under a lamp at the base of its tile. Uses the dedicated
     * {@link #lampShadowRegion} (wider, taller, darker than the mob shadow) so the lamp reads
     * as firmly planted on the floor rather than floating above it.
     */
    private void drawLampShadow(int gx, int gy) {
        float cx = gx * CELL + CELL / 2f;
        float by = gy * CELL + ENTITY_Y_OFFSET;
        batch.setColor(Color.BLACK);
        batch.draw(lampShadowRegion, cx - LAMP_SHADOW_W / 2f, by - LAMP_SHADOW_H / 2f,
                LAMP_SHADOW_W, LAMP_SHADOW_H);
        batch.setColor(Color.WHITE);
    }

    /** Draws the static oval contact shadow for LAMP, STATUE, or THRONE tiles. Called from
     *  the shadow pre-pass so all contact shadows are batched with the utility atlas before
     *  the tile-atlas content loop starts. */
    private void drawContactShadowAt(Level level, int x, int y) {
        switch (level.tiles[x][y]) {
            case LAMP -> { if (currentLampOrnament != null) drawLampShadow(x, y); }
            case STATUE_SMALL_L, STATUE_SMALL_R -> {
                if (currentSmallStatue != null) drawStatueShadow(x, y, false);
            }
            case STATUE_LARGE_L, STATUE_LARGE_R -> {
                if (currentLargeStatue != null) drawStatueShadow(x, y, true);
            }
            case THRONE_L, THRONE_R -> {
                if (currentThrone != null) drawStatueShadow(x, y, false);
            }
            default -> {}
        }
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
     * "Ceiling" for one cell - paints overhang / cap tiles belonging to a wall or door in
     * the cell to the SOUTH. Three cases:
     * <ul>
     *   <li>Walls: overhang and internal-cap paint at the cell NORTH of the wall, on top of
     *       that cell's mob so tall walls clip anything behind them.</li>
     *   <li>Doors (N/S-facing): door top paints at the cell NORTH of the door - same
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
            } else if (TileQuery.isSidewaysDoor(level, x, y-1) && TileQuery.isClosedDoorAt(level, x, y-1)) {
                if(TileQuery.isCrystalDoor(level, x, y-1)) {
                    drawTile(TileSprites.CRYSTAL_DOOR_SIDEWAYS_CLOSED_UPPER, x, y);
                } else {
                    drawTile(TileSprites.DOOR_SIDEWAYS_CLOSED_UPPER, x, y);
                }
            }
            return;
        }

        if(TileQuery.isSidewaysDoor(level, x, y)) {
                int result = TileSprites.DOOR_SIDEWAYS_OVERHANG_CLOSED;
                if (!stitchBarrier(level, x + 1, y - 1)) result += 1;
                if (!stitchBarrier(level, x - 1, y - 1)) result += 2;
                drawTile(result, x, y);
            return;
        }


        if (TileQuery.isWallAt(level, x, y - 1)) {
            int result = TileSprites.WALLS_OVERHANG;
            if (!stitchBarrier(level, x + 1, y - 1)) result += 1;
            if (!stitchBarrier(level, x - 1, y - 1)) result += 2;
            drawTile(result, x, y);
            return;
        }
        // Door top paints at the cell NORTH of the door (y+1 = north in y-up) - same cell
        // as wall overhangs use. The SPD convention: the door body occupies its own cell and
        // the arched top visually sits in the tile above on screen.
        if (TileQuery.isDoorAt(level, x, y - 1)) {
            drawTile(TileSprites.DOOR_OVERHANG, x, y);
        }
    }

    private int terrainVisual(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        int hash = TileSprites.variantHash(x, y);
        return switch (t) {
            case FLOOR         -> TileSprites.floorVariant(hash);
            case FLOOR_WOOD    -> TileSprites.floorWoodVariant(hash);
            case FLOOR_SPECIAL -> TileSprites.specialFloor();
            case LAMP          -> TileSprites.floorVariant(hash); // base; lamp sprite drawn in drawLampAt
            // Stairs render their floor underlay in drawFloorAt and the 2x2 ladder sprite
            // in the per-cell content pass (drawStairsAt). Returning -1 here skips the
            // legacy 1x1 glyph draw.
            case STAIRS_UP, STAIRS_DOWN -> -1;
            case CHASM       -> -1; // handled inline
            case WALL        -> raisedWall(level, x, y);
            case DOOR, DOOR_OPEN, CRYSTAL_DOOR_OPEN -> raisedDoor(level, x, y);
            case CRYSTAL_DOOR, ONETIME_DOOR ->  crystalDoor(level, x, y);
            // Statues / altar / throne all sit on a regular floor base; the ornament
            // sprite itself is layered on top in the per-cell content pass (drawStatueAt /
            // drawAltarAt / drawThroneAt) so any L/R facing flip can be applied at draw.
            case STATUE_SMALL_L, STATUE_SMALL_R,
                 STATUE_LARGE_L, STATUE_LARGE_R,
                 ALTAR,
                 THRONE_L, THRONE_R -> TileSprites.floorVariant(hash);
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
     * sitting in the source cell - meaning {@code self}'s mask should fade on that side. The
     * test is "the neighbour does NOT also hold {@code self}": a dry tile, a different
     * surface, or out-of-bounds all qualify. This is what makes per-surface passes work - in
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
        // Skip only when there's truly nothing visible behind the wall - OOB or unexplored
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
        if (TileQuery.isSidewaysDoor(level, x, y))
            if(TileQuery.isClosedDoorAt(level, x, y)) 
                return TileSprites.DOOR_SIDE_CLOSED_LOWER;
            else
                return -1;
            
        return TileQuery.isClosedDoorAt(level, x, y) ? TileSprites.DOOR_CLOSED : TileSprites.DOOR_OPEN;
    }

    private int crystalDoor(Level level, int x, int y) {
        if (TileQuery.isSidewaysDoor(level, x, y)) return TileSprites.CRYSTAL_DOOR_SIDEWAYS_CLOSED_LOWER;
        return TileQuery.isClosedDoorAt(level, x, y) ? TileSprites.CRYSTAL_DOOR_CLOSED : TileSprites.DOOR_OPEN;
    }

    private static boolean isWallish(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return true;
        return level.tiles[x][y] == Tile.WALL;
    }

    /**
     * Stitching neighbor test for the 4-bit variant math. Walls and OOB are always barriers.
     * An UNEXPLORED chasm is a barrier (stands in for "outside the map" so walls don't stitch
     * open on the unseen side), but an EXPLORED chasm is an open interior - walls of a visible
     * chasm-filled room should stitch toward it, not dead-end like they would at the map edge.
     */
    private static boolean stitchBarrier(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return true;
        Tile t = level.tiles[x][y];
        if (t == Tile.WALL) return true;
        if (t == Tile.CHASM && !level.explored[x][y]) return true;
        return false;
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

    private static TileBounds visibleTileBounds(Level level, OrthographicCamera camera) {
        float halfW = camera.viewportWidth * camera.zoom * 0.5f;
        float halfH = camera.viewportHeight * camera.zoom * 0.5f;
        int minX = (int) Math.floor((camera.position.x - halfW) / CELL) - VIEW_CULL_PAD_TILES;
        int maxX = (int) Math.ceil((camera.position.x + halfW) / CELL) + VIEW_CULL_PAD_TILES;
        int minY = (int) Math.floor((camera.position.y - halfH) / CELL) - VIEW_CULL_PAD_TILES;
        int maxY = (int) Math.ceil((camera.position.y + halfH) / CELL) + VIEW_CULL_PAD_TILES;
        return new TileBounds(
                Math.max(0, minX),
                Math.min(level.width - 1, maxX),
                Math.max(0, minY),
                Math.min(level.height - 1, maxY));
    }

    private void drawItemsAt(Level level, int x, int y, LevelRenderIndexes.CellBuckets<Item> itemsByCell) {
        List<Item> list = itemsByCell.at(x, y);
        if (list == null) return;
        for (Item it : list) drawItem(level, it);
    }

    private void drawMobsAt(Level level, int x, int y, LevelRenderIndexes.CellBuckets<Mob> mobsByCell) {
        List<Mob> list = mobsByCell.at(x, y);
        if (list == null) return;
        for (Mob mob : list) drawMob(level, mob);
    }

    /** Draw every ghost in the {@link com.bjsp123.rl2.world.anim.Animator}'s
     *  list. Ghosts are dying mobs that have been removed from
     *  {@code level.mobs} by {@code killMob} but still owe the player a
     *  visual - typically the death flicker / fade, but also the queued
     *  knockback slide when the mob was knocked back into a fatal
     *  collision. The slide offset is read from the mob's
     *  {@link com.bjsp123.rl2.world.anim.MobAnimState} just like the live
     *  mob render path uses. */
    /** Iterate every active dust-cloud effect and hand it to
     *  {@link FxRenderer#drawEffect} for rendering. Called from
     *  {@link #render} AFTER mobs, items, and ghosts have been drawn so
     *  each cloud sits on top of whatever it was kicked up next to -
     *  including the player sprite, whose feet the dust is meant to
     *  obscure. */
    private void drawAllDustClouds(Level level) {
        if (animator == null) return;
        for (Effect e : animator.stage.active) {
            if (e.type != Effect.EffectType.DUST_CLOUD) continue;
            fxRenderer.drawEffect(level, e);
        }
    }


    private void drawGhosts(Level level) {
        if (animator == null) return;
        for (com.bjsp123.rl2.world.anim.Ghost g : animator.ghosts()) {
            if (g.mob == null) continue;
            int gx = g.x, gy = g.y;
            if (!inBounds(level, gx, gy) || !level.visible[gx][gy]) continue;
            com.bjsp123.rl2.world.anim.MobAnimState as = animator.stateOf(g.mob);
            // Slide offset, identical to drawMob's: at t=0 the sprite is
            // pulled back by stepFromDx tiles, ramping to zero by t=1.
            float ox = 0f, oy = 0f;
            if (as != null && as.stepTotal > 0) {
                float t = Math.min(1f, as.stepFrame / (float) as.stepTotal);
                ox = as.stepFromDx * (1f - t) * CELL;
                oy = as.stepFromDy * (1f - t) * CELL;
            }
            // Death-fade alpha (Animator parks ghost.frame while a slide
            // is queued, so this stays at 1 during the slide and only
            // begins fading once the slide finishes).
            float alpha = g.alpha();
            if (alpha <= 0f) continue;
            Sprite s = spriteForGhost(g);
            if (s != null) {
                drawMobSprite(s, gx, gy, ox, oy, alpha);
            }
        }
    }

    /** Sprite lookup for a ghost - same as {@link #spriteForMob} but uses
     *  the ghost's saved {@code facingEast} so a mob that died facing east
     *  doesn't flip mid-fade if its kill-time facing differs from whatever
     *  the dead Mob field still holds. */
    private Sprite spriteForGhost(com.bjsp123.rl2.world.anim.Ghost g) {
        Mob mob = g.mob;
        int f = g.facingEast ? 1 : 0;
        if (mob.characterClass != null) return playerSprite(mob, f);
        if (mob.mobType == null) return null;
        Sprite[] pair = speciesFacing.get(mob.mobType);
        return pair == null ? null : pair[f];
    }

    private void drawFxAt(Level level, int x, int y, LevelRenderIndexes.CellBuckets<Effect> fxByCell) {
        List<Effect> list = fxByCell.at(x, y);
        if (list == null) return;
        batch.setColor(Color.WHITE);
        for (Effect e : list) fxRenderer.drawEffect(level, e);
        batch.setColor(Color.WHITE);
    }

    private void drawItem(Level level, Item it) {
        if (it == null) return;
        if (it.location == null) {
            System.err.println("Item " + it.type + " has null location, skipping draw");
            return;
        }
        int x = it.location.tileX(), y = it.location.tileY();
        if (!inBounds(level, x, y) || !level.visible[x][y]) return;
        float dx = x * (float) CELL;
        float dy = y * (float) CELL + ENTITY_Y_OFFSET;
        if (it.useBehavior == Item.UseBehavior.POWERUP) {
            float phase = x * 1.3f + y * 0.7f;
            dy += (float) Math.sin(stairLabelTime * 2.0f + phase) * 1.5f;
            drawPowerupGlow(it, dx, dy, phase);
        }
        // Prefer the shared ItemSprites lookup so the on-floor icon matches the
        // inventory + action-bar art. Falls back to the glyph if no sprite is registered.
        com.badlogic.gdx.graphics.g2d.TextureRegion region = ItemSprites.regionFor(it);
        if (region != null) {
            // Brand outline pulse: every 3 seconds the outline briefly flashes the
            // brand color before returning to black.
            if (it.brand != null) {
                float t = stairLabelTime % 3.0f;
                float pulse = t < 0.3f ? (float) Math.sin(t / 0.3f * Math.PI) : 0f;
                if (pulse > 0f) {
                    int hex = it.brand.colorHex;
                    float br = ((hex >> 16) & 0xFF) / 255f;
                    float bg = ((hex >> 8)  & 0xFF) / 255f;
                    float bb = ( hex        & 0xFF) / 255f;
                    outlines.drawRegionTinted(batch, region, dx, dy, (float) CELL, (float) CELL,
                            br, bg, bb, pulse);
                } else {
                    outlines.drawRegion(batch, region, dx, dy, (float) CELL, (float) CELL);
                }
                BrandFx.drawWorldItemSparks(batch, whiteRegion,
                        dx, dy, (float) CELL, (float) CELL, it, stairLabelTime);
            } else {
                // Same silhouette outline mobs/statues/lamps get - items on the floor
                // were the lone holdout. Helps loot pop visually against busy terrain.
                outlines.drawRegion(batch, region, dx, dy, (float) CELL, (float) CELL);
            }
            batch.setColor(Color.WHITE);
            // Source art is 32x32, world cell is 16x16 - libGDX scales 2:1 down with
            // nearest-neighbour filtering for crisp pixels.
            batch.draw(region, dx, dy, (float) CELL, (float) CELL);
        } else {
            System.err.println("No sprite for item " + it.type + " at (" + x + ", " + y + ")");
        }
        int effLvl = com.bjsp123.rl2.logic.ItemStats.effectiveLevel(it, null);
        drawItemLevelBadge(effLvl, x, y);
    }

    /** Additive halo drawn behind a POWERUP floor item, using the dedicated
     *  glow sprites from {@code buffs16.png} (col 4 for perk powerups,
     *  col 5 for HP/mana powerups). Two passes at different sizes give depth
     *  without drawing squares. */
    private void drawPowerupGlow(Item it, float dx, float dy, float phase) {
        com.badlogic.gdx.graphics.g2d.TextureRegion glow = isHpManaGlow(it)
                ? BuffIcons.hpManaGlowRegion()
                : BuffIcons.perkGlowRegion();
        if (glow == null) return;

        float pulse = 0.4f + 0.15f * (float) Math.sin(stairLabelTime * 2.0f + phase);
        float cx = dx + CELL * 0.5f;
        float cy = dy + CELL * 0.5f;
        float[] rgb = powerupGlowColor(it);

        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);

        float outerSize = CELL * 2.5f;
        batch.setColor(rgb[0], rgb[1], rgb[2], 0.55f * pulse);
        batch.draw(glow, cx - outerSize * 0.5f, cy - outerSize * 0.5f, outerSize, outerSize);

        float innerSize = CELL * 1.5f;
        batch.setColor(rgb[0], rgb[1], rgb[2], 0.85f * pulse);
        batch.draw(glow, cx - innerSize * 0.5f, cy - innerSize * 0.5f, innerSize, innerSize);

        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(Color.WHITE);
    }

    private static boolean isHpManaGlow(Item it) {
        return it.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.HP_UP
            || it.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.MANA_UP;
    }

    private static float[] powerupGlowColor(Item it) {
        if (it.wandEffect == null) return new float[]{1f, 0.9f, 0.4f};
        return switch (it.wandEffect) {
            case LEVEL_UP -> new float[]{1f, 0.85f, 0.2f};
            case HP_UP    -> new float[]{0.3f, 0.85f, 0.15f};
            case MANA_UP  -> new float[]{0.25f, 0.5f,  1f};
            default       -> new float[]{1f, 0.9f, 0.4f};
        };
    }

    /** Small {@code +N} marker drawn at the top-right corner of an item's tile to
     *  signal its enchant level. Yellow, half-scale font so it doesn't dominate the
     *  sprite. */
    private void drawItemLevelBadge(int effLvl, int x, int y) {
        if (effLvl <= 1) return;
        float prevScale = font.getData().scaleX;
        font.getData().setScale(prevScale * 0.55f);
        font.setColor(UIVars.ACCENT);
        String text = "+" + (effLvl - 1);
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
        // for phase 1 - origin/destination might fall in different visibility regions and
        // we want the fade-out to play even if the destination tile is dark.
        float teleportAlpha = 1f;
        if (as.teleportFadeMs > 0) {
            int half = com.bjsp123.rl2.logic.MobSystem.TELEPORT_FADE_HALF_MS;
            int total = com.bjsp123.rl2.logic.MobSystem.TELEPORT_FADE_TOTAL_MS;
            if (as.teleportFadeMs > half) {
                // Departing: render at origin, alpha 1 -> 0 across the first half.
                mx = as.teleportFromX;
                my = as.teleportFromY;
                teleportAlpha = (as.teleportFadeMs - half) / (float) (total - half);
            } else {
                // Arriving: render at destination (already mob.position), alpha 0 -> 1.
                teleportAlpha = 1f - as.teleportFadeMs / (float) half;
            }
        }
        if (!inBounds(level, mx, my) || !level.visible[mx][my]) return;
        // Melee-lunge / hit-flinch offset: world y-up matches MobAnimState.animOffsetY()
        // (positive Y = north = up on screen).
        float ox = as.animOffsetX();
        float oy = as.animOffsetY();
        // Step-interpolation offset - slides the mob from its previous tile into its
        // current logical tile linearly over the animation. Suppressed during a teleport
        // fade (the mob isn't sliding, it's blinking from one cell to another).
        if (as.stepTotal > 0 && as.teleportFadeMs <= 0) {
            float t = Math.min(1f, as.stepFrame / (float) as.stepTotal);
            ox += as.stepFromDx * (1f - t) * CELL;
            oy += as.stepFromDy * (1f - t) * CELL;
        }
        // Live mobs render fully opaque; the death-fade lives on rgame's ghost list,
        // not on the mob itself (killMob removes the mob from level.mobs immediately).
        // Teleport fade still multiplies in.
        float alpha = teleportAlpha;
        if (alpha <= 0f) return;
        // Spawn-grow: scale from 0 to 1 over the spawn animation's lifetime,
        // anchored at the tile's bottom edge so the mob reads as rising out
        // of the floor. Defaults to 1.0 when no spawn anim is active.
        float spawnScale = 1f;
        if (as.spawnTotalFrames > 0) {
            spawnScale = Math.min(1f, as.spawnFrame / (float) as.spawnTotalFrames);
        }
        // INVISIBLE buff: alpha oscillates between 0.2 and 0.5.
        if (com.bjsp123.rl2.logic.BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.INVISIBLE)) {
            float pulse = (float)(0.5 + 0.5 * Math.sin(stairLabelTime * 1.5));
            alpha = 0.2f + 0.3f * pulse;
        }
        Sprite s = spriteForMob(mob);
        if (s != null) {
            // Unique mobs continuously pulse their outline between black and white.
            float pulseR = 0f, pulseG = 0f, pulseB = 0f;
            if (mob.unique) {
                float p = 0.5f + 0.5f * (float) Math.sin(stairLabelTime * Math.PI * 2.0 / 1.5);
                pulseR = p; pulseG = p; pulseB = p;
            }
            if (as.borderFlashFrames > 0) {
                pulseR = 1f; pulseG = 1f; pulseB = 1f;
            }
            boolean phaseActive    = com.bjsp123.rl2.logic.BuffSystem.hasBuff(
                    mob, com.bjsp123.rl2.model.Buff.BuffType.PHASE);
            boolean frozenActive   = com.bjsp123.rl2.logic.BuffSystem.hasBuff(
                    mob, com.bjsp123.rl2.model.Buff.BuffType.FROZEN);
            boolean shieldedActive    = com.bjsp123.rl2.logic.BuffSystem.hasBuff(
                    mob, com.bjsp123.rl2.model.Buff.BuffType.SHIELDED);
            boolean levitatingActive  = com.bjsp123.rl2.logic.BuffSystem.hasBuff(
                    mob, com.bjsp123.rl2.model.Buff.BuffType.LEVITATING);
            drawMobSprite(s, mx, my, ox, oy, alpha, spawnScale,
                    pulseR, pulseG, pulseB, phaseActive, frozenActive, shieldedActive, levitatingActive);
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

    /** Render any equipped gem slots that are filled, orbiting + bobbing above the player's
     *  head. Each gem occupies a fixed slot on a horizontal arc with a sine offset in y. */
    private void drawPlayerGems(Mob mob, int mx, int my, float ox, float oy, float alpha) {
        int gemCount = com.bjsp123.rl2.model.Inventory.positionCount(
                com.bjsp123.rl2.model.Item.InventoryCategory.GEM);
        java.util.List<com.bjsp123.rl2.model.Item> gems = new java.util.ArrayList<>(gemCount);
        for (int i = 0; i < gemCount; i++) {
            com.bjsp123.rl2.model.Item g = mob.inventory.equipped(
                    com.bjsp123.rl2.model.Item.InventoryCategory.GEM, i);
            if (g != null) gems.add(g);
        }
        int n = gems.size();
        if (n <= 0) return;
        // Centre the cluster on the player sprite's vertical middle so the GEM2 slot
        // (the centre orbit position when 3 gems are equipped) sits over the player's
        // chest rather than above their head. Horizontal centre is the tile centre.
        float cx     = mx * CELL + CELL * 0.5f + ox;
        float midY   = my * CELL + CELL * 1.55f + oy;
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
        float barW = CELL - 2f;          // 14 px - leaves a 1-px gutter on each side
        float barH = 2f;
        float bx   = mx * CELL + 1f + ox;
        float by   = my * CELL + CELL + 2f + oy;
        // Backing - dark frame so the bar is legible against any tile.
        batch.setColor(0f, 0f, 0f, 0.85f * alpha);
        batch.draw(whiteRegion, bx - 1f, by - 1f, barW + 2f, barH + 2f);
        // Empty fill behind the red - flat dim red so the loss reads as missing HP
        // rather than nothing.
        batch.setColor(0.35f, 0.05f, 0.05f, alpha);
        batch.draw(whiteRegion, bx, by, barW, barH);
        // Filled portion.
        batch.setColor(0.85f, 0.18f, 0.18f, alpha);
        batch.draw(whiteRegion, bx, by, barW * frac, barH);
        batch.setColor(Color.WHITE);
    }

    /**
     * Draw a mob so its VISIBLE silhouette is exactly {@link #MOB_VISIBLE_W} x {@link
     * #MOB_VISIBLE_H} on screen, regardless of how much blank canvas its source frame has.
     * Width and height scale independently (non-uniform) so skinny sprites and chubby ones
     * both hit the same footprint. The visible silhouette is recentred on the tile by
     * shifting the draw origin by {@code visibleLeft * scaleX}. A semi-transparent oval
     * shadow is drawn first, anchored at the baseline.
     */
    private void drawMobSprite(Sprite s, int gx, int gy, float offsetX, float offsetY, float alpha) {
        drawMobSprite(s, gx, gy, offsetX, offsetY, alpha, 1f, 0f, 0f, 0f, false, false, false, false);
    }

    /** Spawn-scale variant: {@code spawnScale} 0..1 multiplies both x and y
     *  scale, with the sprite anchored to the tile's bottom edge so the mob
     *  reads as rising out of the floor during a spawn animation. {@code 1f}
     *  is the no-op default for normal rendering. */
    private void drawMobSprite(Sprite s, int gx, int gy, float offsetX, float offsetY,
                               float alpha, float spawnScale) {
        drawMobSprite(s, gx, gy, offsetX, offsetY, alpha, spawnScale, 0f, 0f, 0f, false, false, false, false);
    }

    /**
     * Full variant: {@code outlinePulseR/G/B} inject a tinted outline color that
     * blends with black for pulse effects (e.g. gold for unique mobs). When all
     * three are 0 the outline is pure black as usual.
     */
    private void drawMobSprite(Sprite s, int gx, int gy, float offsetX, float offsetY,
                               float alpha, float spawnScale,
                               float outlinePulseR, float outlinePulseG, float outlinePulseB,
                               boolean phaseEffect) {
        drawMobSprite(s, gx, gy, offsetX, offsetY, alpha, spawnScale,
                outlinePulseR, outlinePulseG, outlinePulseB, phaseEffect, false, false, false);
    }

    private void drawMobSprite(Sprite s, int gx, int gy, float offsetX, float offsetY,
                               float alpha, float spawnScale,
                               float outlinePulseR, float outlinePulseG, float outlinePulseB,
                               boolean phaseEffect, boolean frozenEffect, boolean shieldedEffect,
                               boolean levitatingEffect) {
        // "Natural" sprites (large blobs etc.) draw at source scale; everything else gets
        // normalised to MOB_VISIBLE_W x MOB_VISIBLE_H so silhouettes read consistently.
        float scaleX = (s.natural ? 1f : MOB_VISIBLE_W / (float) s.visibleW) * spawnScale;
        float scaleY = (s.natural ? 1f : MOB_VISIBLE_H / (float) s.visibleH) * spawnScale;
        float dw = s.w * scaleX;
        float dh = s.h * scaleY;
        float yAdj = s.yAdjust * scaleY;
        // Offsets (from the lunge / flinch animation) shift both the mob and its shadow
        // together - the whole silhouette leans into the strike or recoils from a hit.
        float tileCenterX = gx * CELL + CELL / 2f + offsetX;
        float baselineY   = gy * CELL + ENTITY_Y_OFFSET + offsetY;

        // Centre the visible silhouette horizontally on the tile. Source center-x of the
        // silhouette is (visibleLeft + visibleW/2), which scales to that x scaleX in draw px;
        // the frame's draw origin (left edge) therefore sits tileCenterX - that amount.
        float silhouetteCenterScaled = (s.visibleLeft + s.visibleW / 2f) * scaleX;
        float drawX = tileCenterX - silhouetteCenterScaled;
        float levitateLift = levitatingEffect ? FLYING_HOVER_PX * scaleY : 0f;
        float bob = 0f;
        if (s.yAdjust > 0 || levitatingEffect) {
            float phase = (gx * 7 + gy * 13) * 0.7f;
            bob = (float) Math.sin(bobTime * (Math.PI * 2.0 / 2.5) + phase) * 2f * scaleY;
        }
        float drawY = baselineY + yAdj + levitateLift + bob;

        // Shadow shares the same alpha as the mob so death-fade dims both together.
        float shadowW = s.visibleW * scaleX + SHADOW_EXTRA_W;
        batch.setColor(0f, 0f, 0f, alpha);
        batch.draw(shadowRegion, tileCenterX - shadowW / 2f, baselineY - SHADOW_H / 2f,
                shadowW, SHADOW_H);
        batch.setColor(1f, 1f, 1f, alpha);
        // SHIELDED shell: draw the mob's silhouette slightly enlarged in cyan before
        // the mob itself so the glow sits behind it and reads as a surrounding barrier.
        if (shieldedEffect) {
            float pulse = (float)(0.5 + 0.5 * Math.sin(stairLabelTime * 5.0));
            batch.setColor(0.5f, 0.9f, 1.0f, alpha * (0.18f + 0.12f * pulse));
            batch.draw(s.region, drawX - 8f, drawY - 8f, dw + 16f, dh + 16f);
            batch.setColor(0.5f, 0.9f, 1.0f, alpha * (0.55f + 0.35f * pulse));
            batch.draw(s.region, drawX - 4f, drawY - 4f, dw + 8f, dh + 8f);
        }
        float outlineW = com.bjsp123.rl2.ui.skin.Settings.mobOutlineWidth();
        float outlineA = com.bjsp123.rl2.ui.skin.Settings.mobOutlineDarkness() * alpha;
        if (!phaseEffect && outlineA > 0f && outlineW > 0f) {
            outlines.drawMob(batch, s.region, drawX, drawY, dw, dh,
                    outlinePulseR, outlinePulseG, outlinePulseB, outlineA);
        }
        batch.setColor(1f, 1f, 1f, alpha);
        if (phaseEffect) {
            drawPhaseStrips(s, drawX, drawY, dw, dh, alpha);
        } else {
            batch.draw(s.region, drawX, drawY, dw, dh);
        }
        if (frozenEffect) {
            batch.setColor(0.82f, 0.88f, 0.92f, alpha * 0.40f);
            batch.draw(s.region, drawX, drawY, dw, dh);
        }
        batch.setColor(Color.WHITE);
    }

    private void drawPhaseStrips(Sprite s, float drawX, float drawY, float dw, float dh, float alpha) {
        final int STRIP_H = 2;
        com.badlogic.gdx.graphics.Texture tex = s.region.getTexture();
        int rx = s.region.getRegionX();
        int ry = s.region.getRegionY();
        int rw = s.region.getRegionWidth();
        int rh = s.region.getRegionHeight();
        if (rh <= 0) { batch.draw(s.region, drawX, drawY, dw, dh); return; }
        float maxShift = CELL * 0.18f;
        float sinVal = (float) Math.sin(stairLabelTime * 5.0);
        batch.setColor(1f, 1f, 1f, alpha);
        boolean fx = s.region.isFlipX(), fy = s.region.isFlipY();
        for (int i = 0; i * STRIP_H < rh; i++) {
            int srcH = Math.min(STRIP_H, rh - i * STRIP_H);
            float destH = dh * srcH / (float) rh;
            // Strip i from the top of the image maps to the top of the screen sprite.
            // destY is the screen-bottom of this strip.
            float destY = drawY + dh - (i * STRIP_H + srcH) * dh / (float) rh;
            float xShift = (i % 2 == 0 ? 1f : -1f) * maxShift * sinVal;
            batch.draw(tex, drawX + xShift, destY, dw, destH,
                    rx, ry + i * STRIP_H, rw, srcH, fx, fy);
        }
    }

    /**
     * Build a soft-edged oval shadow as a small RGBA texture. The source is drawn at arbitrary
     * display size with linear filtering, giving a blurry ellipse. Parabolic alpha falloff
     * (1 - r^2) is plenty soft without needing a full blur pass. {@code peakAlpha} is the alpha
     * at the center of the ellipse - mob shadows use a light value; lamp shadows use a
     * heavier one to sell the lamp as physically planted on the floor.
     */

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
        if (utilityAtlas != null) utilityAtlas.dispose();
        if (surfaceMaskShader != null) surfaceMaskShader.dispose();
        outlines.dispose();
        fog.dispose();
        gameFbo.dispose();
    }
}
