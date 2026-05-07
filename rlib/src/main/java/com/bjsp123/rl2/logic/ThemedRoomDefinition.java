package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One row from {@code assets/data/themedrooms.csv} parsed into a typed POJO.
 * Themed rooms layer specific decoration + mob/item content onto an otherwise-
 * regular generated room. The schema splits room composition into orthogonal
 * axes ({@link RoomShape} × {@link ChasmShape} × {@link Vegetation} ×
 * {@link Decoration} list) so any combination is expressible without inventing
 * a new {@code RoomKind}.
 *
 * <p>{@link ThemedRoomRegistry} loads + indexes these; {@link ThemedRoomPainter}
 * stamps decoration; {@link ThemedRoomPopulator} resolves the mob/item specs
 * into actual spawns.
 */
public final class ThemedRoomDefinition {

    public String  type;
    public boolean unique;
    public double  powerMin = 0.0;
    public double  powerMax = 1.0;
    public int     minWidth, minHeight;
    public int     maxWidth = 999, maxHeight = 999;
    public boolean requireLong;

    public RoomShape    roomShape    = RoomShape.RECTANGLE;
    public ChasmShape   chasmShape   = ChasmShape.NONE;
    public Vegetation   vegetation   = Vegetation.NONE;
    public Surface      surface      = Surface.NONE;
    public SpecialFloor specialFloor = SpecialFloor.NONE;
    public List<Decoration> decorations = new ArrayList<>();
    public Placement    placement    = Placement.CENTER;

    public List<CsvTable.SpawnSpec> mobs  = new ArrayList<>();
    public List<CsvTable.SpawnSpec> items = new ArrayList<>();

    /** Overall floor geometry of the room. {@link #RECTANGLE} keeps the carved
     *  rectangle as-is; {@link #ROUND} walls in the corners; {@link #WALKWAY}
     *  fills the interior with chasm and runs plank corridors out to each door;
     *  {@link #SUBROOM} stamps a smaller walled rectangle inside with a single
     *  door so the player has to navigate around / through it. */
    public enum RoomShape  { RECTANGLE, ROUND, WALKWAY, SUBROOM }

    /** Chasm pattern stamped on top of the floor. {@link #CROSS} = plus-shaped,
     *  {@link #CENTER_SQUARE} = central square, {@link #RANDOM_PATCH} = random
     *  blob (matches the existing {@code RoomKind.CHASM} painter). */
    public enum ChasmShape { NONE, CROSS, CENTER_SQUARE, RANDOM_PATCH }

    /** Vegetation pattern. {@link #GRASS_FILL} paints grass over every interior
     *  FLOOR; {@link #MUSHROOM_PATCH} grows one mushroom patch via the existing
     *  flood-fill helper; {@link #MUSHROOMS_DRY_FILL} fills mushrooms on every
     *  dry interior FLOOR (i.e. cells with no surface), used by the shroom-
     *  farm layout where a central blood pool sits inside a mushroom carpet. */
    public enum Vegetation { NONE, GRASS_FILL, MUSHROOM_PATCH, MUSHROOMS_DRY_FILL }

    /** Floor-surface pattern. {@link #BLOOD_POOL_CENTER} drops a central blood
     *  patch (~40% of the interior area) — used by the shroom-farm layout. */
    public enum Surface { NONE, BLOOD_POOL_CENTER }

    /** Special-floor pattern. {@link #CENTER_4X4} drops a 4×4 patch in the lower-
     *  middle of the room (chapel-style); {@link #INSET_RECTANGLE} fills every
     *  interior FLOOR cell except the 1-tile strip ringing the walls (pedestal-
     *  style); {@link #CHECKERBOARD} alternates by parity for a chess-board look. */
    public enum SpecialFloor { NONE, CENTER_4X4, INSET_RECTANGLE, CHECKERBOARD }

    /** Statue / lamp / altar / throne pattern token. Multiple decorations can
     *  compose. */
    public enum Decoration {
        STATUES_SMALL_CORNERS, STATUES_LARGE_CORNERS, STATUES_LARGE_CARDINAL,
        STATUE_AVENUE_SMALL, STATUE_AVENUE_LARGE, STATUE_CENTER_LARGE,
        LAMPS_CORNERS, LAMPS_CARDINAL, LAMP_CENTER,
        /** 2-6 small statues scattered randomly on interior floor — count
         *  scales with room area. Mirrors the pre-existing SMALL_STATUE_ROOM. */
        SMALL_STATUES_SCATTERED,
        /** Altar 1 cell south of the top wall, centred horizontally; two lamps
         *  one cell south of the altar flanking its centre. Used by the chapel
         *  room layout. */
        CHAPEL_SHRINE
    }

    /** Where mob / item spawns land. {@link #CENTER} BFSes from the room's
     *  geometric centre (collecting floor only, but expanding through anything
     *  so a chasm-centred room still finds the floor ring). {@link #OPPOSITE_ENDS}
     *  splits the spec list and seeds each half from the west / east edges
     *  (used by ant-war for the two anthills). {@link #CHASM_OK} BFSes from the
     *  centre but also collects chasm tiles — for flying mobs in the Belfry. */
    public enum Placement  { CENTER, OPPOSITE_ENDS, CHASM_OK }

    public static List<ThemedRoomDefinition> parseAll(String csv) {
        CsvTable table = CsvTable.parse(csv);
        List<ThemedRoomDefinition> out = new ArrayList<>(table.rows.size());
        for (Map<String, String> row : table.rows) {
            out.add(parseRow(row));
        }
        return out;
    }

    private static ThemedRoomDefinition parseRow(Map<String, String> row) {
        ThemedRoomDefinition d = new ThemedRoomDefinition();
        d.type        = CsvTable.str(row, "type", null);
        d.unique      = CsvTable.boolCell(row, "unique", false);

        double[] power = CsvTable.dblRangeCell(row, "powerLevel", 0.0, 1.0);
        d.powerMin    = power[0];
        d.powerMax    = power[1];

        d.minWidth    = CsvTable.intCell(row, "minWidth", 5);
        d.minHeight   = CsvTable.intCell(row, "minHeight", 5);
        d.maxWidth    = CsvTable.intCell(row, "maxWidth", 999);
        d.maxHeight   = CsvTable.intCell(row, "maxHeight", 999);
        d.requireLong = CsvTable.boolCell(row, "requireLong", false);

        d.roomShape    = CsvTable.enumCell(row, "roomShape",
                RoomShape.class, RoomShape.RECTANGLE);
        d.chasmShape   = CsvTable.enumCell(row, "chasmShape",
                ChasmShape.class, ChasmShape.NONE);
        d.vegetation   = CsvTable.enumCell(row, "vegetation",
                Vegetation.class, Vegetation.NONE);
        d.surface      = CsvTable.enumCell(row, "surface",
                Surface.class, Surface.NONE);
        d.specialFloor = CsvTable.enumCell(row, "specialFloor",
                SpecialFloor.class, SpecialFloor.NONE);
        d.placement    = CsvTable.enumCell(row, "placement",
                Placement.class, Placement.CENTER);

        for (String tok : CsvTable.listCell(row, "decorations")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            d.decorations.add(Decoration.valueOf(t));
        }

        d.mobs  = CsvTable.parseSpawnSpecList(CsvTable.str(row, "mobs", null));
        d.items = CsvTable.parseSpawnSpecList(CsvTable.str(row, "items", null));

        return d;
    }
}
