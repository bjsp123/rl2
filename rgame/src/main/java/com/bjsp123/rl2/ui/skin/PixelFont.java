package com.bjsp123.rl2.ui.skin;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * Tiny hand-encoded monospace pixel font. 5 px wide × 7 px tall glyphs for the
 * printable ASCII range (0x20–0x7E), packed into a single horizontal Pixmap and
 * wrapped as a libGDX {@link BitmapFont} so it slots into any existing
 * {@link com.badlogic.gdx.scenes.scene2d.ui.Skin} / {@code Label} pipeline.
 *
 * <p>Drawn in pure white with full alpha — call sites tint via the usual
 * {@link com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle#fontColor} or
 * {@link com.badlogic.gdx.graphics.g2d.Batch#setColor} mechanisms.
 *
 * <p>Glyph art is stored as 35-character strings (7 rows × 5 columns; '#' = on,
 * '.' = off, '\n' separates rows). The atlas is laid out as a single 7-px-tall
 * row of {@code 95 * (CHAR_W + ADVANCE_PAD)} pixels rounded up to a power-of-two
 * width so older OpenGL drivers don't choke on NPOT textures.
 */
public final class PixelFont {

    /** Visible glyph width in pixels — every glyph is the same width so the
     *  result reads as monospace at glance. */
    public static final int CHAR_W = 5;
    /** Glyph cell height (includes one descender row at the bottom). */
    public static final int CHAR_H = 7;
    /** Pixels of empty space between adjacent glyphs both in the atlas and in
     *  the on-screen advance. One pixel keeps neighbouring characters from
     *  smudging into each other while staying compact. */
    public static final int ADVANCE_PAD = 1;
    /** Pixels of outline ring baked around each glyph. The atlas reserves
     *  {@code OUTLINE_PAD} extra pixels on each side of the visible glyph,
     *  and a second pass paints any transparent pixel that's 8-way adjacent
     *  to a lit pixel in pure black. Each character is drawn with
     *  {@code xoffset = -OUTLINE_PAD} so the visible glyph still lands at the
     *  cursor — the outline extends one pixel into the surrounding space. */
    public static final int OUTLINE_PAD = 1;
    /** First / last printable ASCII code points the font covers. */
    public static final int FIRST_CHAR = 0x20;
    public static final int LAST_CHAR  = 0x7E;

    /** One 35-char string per glyph, indexed by {@code code - FIRST_CHAR}.
     *  '#' = lit pixel, '.' = transparent. Rows are joined by '\n' for visual
     *  inspection — the parser splits on newline at build time. */
    private static final String[] GLYPHS = {
        // 0x20 ' '
        ".....\n.....\n.....\n.....\n.....\n.....\n.....",
        // 0x21 '!'
        "..#..\n..#..\n..#..\n..#..\n.....\n..#..\n.....",
        // 0x22 '"'
        ".#.#.\n.#.#.\n.....\n.....\n.....\n.....\n.....",
        // 0x23 '#'
        ".#.#.\n#####\n.#.#.\n#####\n.#.#.\n.....\n.....",
        // 0x24 '$'
        "..#..\n.####\n#.#..\n.###.\n..#.#\n####.\n..#..",
        // 0x25 '%'
        "##..#\n##.#.\n..#..\n.#.##\n#..##\n.....\n.....",
        // 0x26 '&'
        ".##..\n#..#.\n.##..\n#..#.\n.##.#\n.....\n.....",
        // 0x27 '''
        "..#..\n..#..\n.....\n.....\n.....\n.....\n.....",
        // 0x28 '('
        "...#.\n..#..\n..#..\n..#..\n..#..\n..#..\n...#.",
        // 0x29 ')'
        ".#...\n..#..\n..#..\n..#..\n..#..\n..#..\n.#...",
        // 0x2A '*'
        ".....\n.#.#.\n..#..\n#####\n..#..\n.#.#.\n.....",
        // 0x2B '+'
        ".....\n..#..\n..#..\n#####\n..#..\n..#..\n.....",
        // 0x2C ','
        ".....\n.....\n.....\n.....\n.....\n..##.\n..#..",
        // 0x2D '-'
        ".....\n.....\n.....\n#####\n.....\n.....\n.....",
        // 0x2E '.'
        ".....\n.....\n.....\n.....\n.....\n..##.\n..##.",
        // 0x2F '/'
        "....#\n...#.\n..#..\n.#...\n#....\n.....\n.....",
        // 0x30 '0'
        ".###.\n#...#\n#..##\n#.#.#\n##..#\n#...#\n.###.",
        // 0x31 '1'
        "..#..\n.##..\n..#..\n..#..\n..#..\n..#..\n.###.",
        // 0x32 '2'
        ".###.\n#...#\n....#\n...#.\n..#..\n.#...\n#####",
        // 0x33 '3'
        ".###.\n#...#\n....#\n..##.\n....#\n#...#\n.###.",
        // 0x34 '4'
        "...#.\n..##.\n.#.#.\n#..#.\n#####\n...#.\n...#.",
        // 0x35 '5'
        "#####\n#....\n####.\n....#\n....#\n#...#\n.###.",
        // 0x36 '6'
        ".###.\n#....\n#....\n####.\n#...#\n#...#\n.###.",
        // 0x37 '7'
        "#####\n....#\n...#.\n..#..\n.#...\n.#...\n.#...",
        // 0x38 '8'
        ".###.\n#...#\n#...#\n.###.\n#...#\n#...#\n.###.",
        // 0x39 '9'
        ".###.\n#...#\n#...#\n.####\n....#\n....#\n.###.",
        // 0x3A ':'
        ".....\n.....\n..##.\n.....\n..##.\n.....\n.....",
        // 0x3B ';'
        ".....\n.....\n..##.\n.....\n..##.\n..#..\n.....",
        // 0x3C '<'
        "....#\n...#.\n..#..\n.#...\n..#..\n...#.\n....#",
        // 0x3D '='
        ".....\n.....\n#####\n.....\n#####\n.....\n.....",
        // 0x3E '>'
        "#....\n.#...\n..#..\n...#.\n..#..\n.#...\n#....",
        // 0x3F '?'
        ".###.\n#...#\n....#\n..##.\n..#..\n.....\n..#..",
        // 0x40 '@'
        ".###.\n#...#\n#.###\n#.#.#\n#.###\n#....\n.###.",
        // 0x41 'A'
        ".###.\n#...#\n#...#\n#####\n#...#\n#...#\n#...#",
        // 0x42 'B'
        "####.\n#...#\n#...#\n####.\n#...#\n#...#\n####.",
        // 0x43 'C'
        ".###.\n#...#\n#....\n#....\n#....\n#...#\n.###.",
        // 0x44 'D'
        "####.\n#...#\n#...#\n#...#\n#...#\n#...#\n####.",
        // 0x45 'E'
        "#####\n#....\n#....\n###..\n#....\n#....\n#####",
        // 0x46 'F'
        "#####\n#....\n#....\n###..\n#....\n#....\n#....",
        // 0x47 'G'
        ".###.\n#...#\n#....\n#.###\n#...#\n#...#\n.###.",
        // 0x48 'H'
        "#...#\n#...#\n#...#\n#####\n#...#\n#...#\n#...#",
        // 0x49 'I'
        ".###.\n..#..\n..#..\n..#..\n..#..\n..#..\n.###.",
        // 0x4A 'J'
        "..###\n...#.\n...#.\n...#.\n...#.\n#..#.\n.##..",
        // 0x4B 'K'
        "#...#\n#..#.\n#.#..\n##...\n#.#..\n#..#.\n#...#",
        // 0x4C 'L'
        "#....\n#....\n#....\n#....\n#....\n#....\n#####",
        // 0x4D 'M'
        "#...#\n##.##\n#.#.#\n#...#\n#...#\n#...#\n#...#",
        // 0x4E 'N'
        "#...#\n##..#\n#.#.#\n#..##\n#...#\n#...#\n#...#",
        // 0x4F 'O'
        ".###.\n#...#\n#...#\n#...#\n#...#\n#...#\n.###.",
        // 0x50 'P'
        "####.\n#...#\n#...#\n####.\n#....\n#....\n#....",
        // 0x51 'Q'
        ".###.\n#...#\n#...#\n#...#\n#.#.#\n#..#.\n.##.#",
        // 0x52 'R'
        "####.\n#...#\n#...#\n####.\n#.#..\n#..#.\n#...#",
        // 0x53 'S'
        ".###.\n#...#\n#....\n.###.\n....#\n#...#\n.###.",
        // 0x54 'T'
        "#####\n..#..\n..#..\n..#..\n..#..\n..#..\n..#..",
        // 0x55 'U'
        "#...#\n#...#\n#...#\n#...#\n#...#\n#...#\n.###.",
        // 0x56 'V'
        "#...#\n#...#\n#...#\n#...#\n#...#\n.#.#.\n..#..",
        // 0x57 'W'
        "#...#\n#...#\n#...#\n#...#\n#.#.#\n##.##\n#...#",
        // 0x58 'X'
        "#...#\n#...#\n.#.#.\n..#..\n.#.#.\n#...#\n#...#",
        // 0x59 'Y'
        "#...#\n#...#\n.#.#.\n..#..\n..#..\n..#..\n..#..",
        // 0x5A 'Z'
        "#####\n....#\n...#.\n..#..\n.#...\n#....\n#####",
        // 0x5B '['
        ".###.\n.#...\n.#...\n.#...\n.#...\n.#...\n.###.",
        // 0x5C '\'
        "#....\n.#...\n..#..\n...#.\n....#\n.....\n.....",
        // 0x5D ']'
        ".###.\n...#.\n...#.\n...#.\n...#.\n...#.\n.###.",
        // 0x5E '^'
        "..#..\n.#.#.\n#...#\n.....\n.....\n.....\n.....",
        // 0x5F '_'
        ".....\n.....\n.....\n.....\n.....\n.....\n#####",
        // 0x60 '`'
        ".#...\n..#..\n.....\n.....\n.....\n.....\n.....",
        // 0x61 'a'
        ".....\n.....\n.###.\n....#\n.####\n#...#\n.####",
        // 0x62 'b'
        "#....\n#....\n####.\n#...#\n#...#\n#...#\n####.",
        // 0x63 'c'
        ".....\n.....\n.###.\n#...#\n#....\n#...#\n.###.",
        // 0x64 'd'
        "....#\n....#\n.####\n#...#\n#...#\n#...#\n.####",
        // 0x65 'e'
        ".....\n.....\n.###.\n#...#\n#####\n#....\n.###.",
        // 0x66 'f'
        "..##.\n.#..#\n.#...\n###..\n.#...\n.#...\n.#...",
        // 0x67 'g'
        ".....\n.####\n#...#\n#...#\n.####\n....#\n.###.",
        // 0x68 'h'
        "#....\n#....\n####.\n#...#\n#...#\n#...#\n#...#",
        // 0x69 'i'
        "..#..\n.....\n.##..\n..#..\n..#..\n..#..\n.###.",
        // 0x6A 'j'
        "...#.\n.....\n..##.\n...#.\n...#.\n#..#.\n.##..",
        // 0x6B 'k'
        "#....\n#....\n#..#.\n#.#..\n##...\n#.#..\n#..#.",
        // 0x6C 'l'
        ".##..\n..#..\n..#..\n..#..\n..#..\n..#..\n.###.",
        // 0x6D 'm'
        ".....\n.....\n##.#.\n#.#.#\n#.#.#\n#.#.#\n#...#",
        // 0x6E 'n'
        ".....\n.....\n####.\n#...#\n#...#\n#...#\n#...#",
        // 0x6F 'o'
        ".....\n.....\n.###.\n#...#\n#...#\n#...#\n.###.",
        // 0x70 'p'
        ".....\n.....\n####.\n#...#\n####.\n#....\n#....",
        // 0x71 'q'
        ".....\n.....\n.####\n#...#\n.####\n....#\n....#",
        // 0x72 'r'
        ".....\n.....\n#.##.\n##..#\n#....\n#....\n#....",
        // 0x73 's'
        ".....\n.....\n.####\n#....\n.###.\n....#\n####.",
        // 0x74 't'
        ".#...\n.#...\n###..\n.#...\n.#...\n.#..#\n..##.",
        // 0x75 'u'
        ".....\n.....\n#...#\n#...#\n#...#\n#...#\n.####",
        // 0x76 'v'
        ".....\n.....\n#...#\n#...#\n#...#\n.#.#.\n..#..",
        // 0x77 'w'
        ".....\n.....\n#...#\n#...#\n#.#.#\n#.#.#\n.#.#.",
        // 0x78 'x'
        ".....\n.....\n#...#\n.#.#.\n..#..\n.#.#.\n#...#",
        // 0x79 'y'
        ".....\n.....\n#...#\n#...#\n.####\n....#\n.###.",
        // 0x7A 'z'
        ".....\n.....\n#####\n...#.\n..#..\n.#...\n#####",
        // 0x7B '{'
        "...##\n..#..\n..#..\n.#...\n..#..\n..#..\n...##",
        // 0x7C '|'
        "..#..\n..#..\n..#..\n..#..\n..#..\n..#..\n..#..",
        // 0x7D '}'
        "##...\n..#..\n..#..\n...#.\n..#..\n..#..\n##...",
        // 0x7E '~'
        ".....\n.#..#\n#.##.\n.....\n.....\n.....\n.....",
    };

    private PixelFont() {}

    /** Build the font. The caller owns the returned {@link BitmapFont} and is
     *  responsible for {@link BitmapFont#dispose()} when finished. */
    public static BitmapFont create() {
        int n = LAST_CHAR - FIRST_CHAR + 1;
        // Each glyph occupies a {@code cellW × cellH} cell in the atlas:
        // OUTLINE_PAD px of margin on every side around the visible CHAR_W ×
        // CHAR_H glyph. The margin holds the baked black outline ring.
        int cellW  = CHAR_W + 2 * OUTLINE_PAD;
        int cellH  = CHAR_H + 2 * OUTLINE_PAD;
        int atlasW = nextPow2(cellW * n);
        int atlasH = nextPow2(cellH);
        Pixmap pm = new Pixmap(atlasW, atlasH, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        pm.setColor(0, 0, 0, 0);
        pm.fill();

        // Pass 1 — paint the visible glyph in white, offset by OUTLINE_PAD so
        // there's room around each glyph for the outline ring drawn in pass 2.
        // Track lit pixels via a per-cell boolean mask so pass 2 can sample
        // 8-way neighbours without re-reading the Pixmap.
        boolean[][] lit = new boolean[atlasW][atlasH];
        pm.setColor(1, 1, 1, 1);
        for (int i = 0; i < n; i++) {
            String def = i < GLYPHS.length ? GLYPHS[i] : null;
            if (def == null || def.isEmpty()) continue;
            int gx = i * cellW + OUTLINE_PAD;
            int gy =             OUTLINE_PAD;
            int row = 0;
            int rowStart = 0;
            for (int p = 0; p <= def.length() && row < CHAR_H; p++) {
                if (p == def.length() || def.charAt(p) == '\n') {
                    int len = Math.min(CHAR_W, p - rowStart);
                    for (int x = 0; x < len; x++) {
                        if (def.charAt(rowStart + x) == '#') {
                            pm.drawPixel(gx + x, gy + row);
                            lit[gx + x][gy + row] = true;
                        }
                    }
                    row++;
                    rowStart = p + 1;
                }
            }
        }

        // Pass 2 — bake a black 1-px ring around every lit pixel. We paint
        // black on every transparent pixel that's 8-way adjacent to at least
        // one lit (white) pixel. Lit pixels themselves stay white. Sampling
        // the {@code lit} mask (rather than the Pixmap) avoids the second
        // pass smearing into outlines drawn earlier on the same row.
        pm.setColor(0, 0, 0, 1);
        int usedW = cellW * n;
        for (int y = 0; y < cellH; y++) {
            for (int x = 0; x < usedW; x++) {
                if (lit[x][y]) continue;
                boolean nearLit = false;
                outer:
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= atlasW || ny >= atlasH) continue;
                        if (lit[nx][ny]) { nearLit = true; break outer; }
                    }
                }
                if (nearLit) pm.drawPixel(x, y);
            }
        }

        Texture tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pm.dispose();

        BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData();
        // Per libGDX font conventions: lineHeight is the inter-line distance,
        // capHeight the height of capitals, ascent is positive for distance
        // from baseline up to the top of the line. We treat the entire visible
        // glyph box as cap height so descenders peek a row below the baseline
        // (and the line height adds one row of breathing space between rows).
        // The {@code OUTLINE_PAD} ring is drawn AROUND the visible cell — it
        // does not contribute to advance/lineHeight/capHeight (the outline is
        // intended to overlap minimally with neighbouring glyphs).
        int advance = CHAR_W + ADVANCE_PAD;
        data.scaleX = 1f;
        data.scaleY = 1f;
        data.lineHeight  = CHAR_H + 1;
        data.capHeight   = CHAR_H - 1;
        data.ascent      = 0;
        data.descent     = -1;
        data.down        = -data.lineHeight;
        data.padTop = data.padBottom = data.padLeft = data.padRight = 0;
        data.spaceXadvance = advance;
        data.xHeight = CHAR_H - 2;

        for (int i = 0; i < n; i++) {
            int code = FIRST_CHAR + i;
            BitmapFont.Glyph g = new BitmapFont.Glyph();
            g.id = code;
            // Source rect is the FULL cell (visible glyph + outline ring).
            // The shifts below pull the image up + left so the visible glyph
            // stays at the cursor position; the outline extends one pixel
            // into the surrounding area.
            g.srcX = i * cellW;
            g.srcY = 0;
            g.width  = cellW;
            g.height = cellH;
            g.xoffset  = -OUTLINE_PAD;
            g.yoffset  = -(int) data.capHeight - OUTLINE_PAD;
            g.xadvance = advance;
            g.u  = (float) g.srcX / atlasW;
            g.v  = (float) g.srcY / atlasH;
            g.u2 = (float) (g.srcX + g.width)  / atlasW;
            g.v2 = (float) (g.srcY + g.height) / atlasH;
            data.setGlyph(code, g);
        }
        // Fallback glyph for anything outside the ASCII printable range — point
        // at '?' so missing chars aren't invisible blanks the user can't account
        // for. {@link BitmapFontData#missingGlyph} is consulted by the renderer.
        data.missingGlyph = data.getGlyph('?');

        TextureRegion region = new TextureRegion(tex);
        BitmapFont font = new BitmapFont(data, region, false);
        font.setUseIntegerPositions(true);
        // Hand on the texture to the font so disposing the font frees it.
        font.setOwnsTexture(true);
        return font;
    }

    private static int nextPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }
}
