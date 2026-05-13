// screens-game.jsx — In-game HUD, Inventory/Character screen

// ─── Dungeon viewport ─────────────────────────────────────────
// Pixel-tile rendering of a small dungeon room. Each tile is rendered as a
// colored square + a glyph (since real tilesets aren't available).
const TILES = {
  '.': { bg: '#2a221a', fg: '#5a4a3a', ch: '.' },   // floor
  '#': { bg: '#1a120e', fg: '#3a2820', ch: '█' },   // wall
  '<': { bg: '#2a221a', fg: RL.gold,   ch: '<' },   // stairs up
  '>': { bg: '#2a221a', fg: RL.gold,   ch: '>' },   // stairs down
  '+': { bg: '#2a221a', fg: '#a87a36', ch: '+' },   // door
  '~': { bg: '#1a3344', fg: '#5a8cc4', ch: '~' },   // water
};

// Simple hand-drawn dungeon room
const MAP = [
  '################',
  '#..............#',
  '#......g.......#',
  '#..............#',
  '######+###.....#',
  '#....#...#.....#',
  '#.@..#.r.#..>..#',
  '#....+...#.....#',
  '######.###.....#',
  '#......#.......#',
  '#......#...$...#',
  '#......#.......#',
  '#......#.......#',
  '#..!...#......~#',
  '#......#......~#',
  '################',
];

// Entity glyphs overlaying tiles
const ENTITIES = {
  '@': { fg: RL.gold,   ch: '@', name: 'you'      },
  'g': { fg: RL.green,  ch: 'g', name: 'goblin'   },
  'r': { fg: '#a04020', ch: 'r', name: 'rat'      },
  '$': { fg: RL.gold,   ch: '$', name: 'gold'     },
  '!': { fg: RL.red,    ch: '!', name: 'potion'   },
  '?': { fg: RL.violet, ch: '?', name: 'scroll'   },
};

function Dungeon({ size = 16 }) {
  return (
    <div style={{
      display: 'grid',
      gridTemplateColumns: `repeat(${MAP[0].length}, 1fr)`,
      gap: 0, lineHeight: 1, fontFamily: "'VT323', monospace",
      background: '#0a0805',
      boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}, inset 0 0 0 3px ${RL.bevelDk}`,
      padding: 4,
    }}>
      {MAP.map((row, y) => row.split('').map((ch, x) => {
        const ent = ENTITIES[ch];
        const tile = ent ? TILES['.'] : (TILES[ch] || TILES['.']);
        const inView = Math.abs(x - 2) + Math.abs(y - 6) < 9;     // simple FOV from @
        const visible = inView;
        return (
          <div key={`${x}-${y}`} style={{
            aspectRatio: '1 / 1', background: tile.bg, color: tile.fg,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: size, opacity: visible ? 1 : 0.25,
            position: 'relative',
          }}>
            <span style={{ position: 'relative', zIndex: 1, color: ent ? ent.fg : tile.fg }}>
              {ent ? ent.ch : tile.ch}
            </span>
          </div>
        );
      }))}
    </div>
  );
}

// ─── D-pad for movement ───────────────────────────────────────
function DPad({ onPress }) {
  const btn = (dir, label) => (
    <button onClick={() => onPress(dir)} className="rl-px"
      style={{
        width: 38, height: 38, background: RL.button,
        boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`,
        color: RL.text, border: 'none', fontSize: 16, cursor: 'pointer', padding: 0,
      }}
    >{label}</button>
  );
  return (
    <div style={{
      display: 'grid', gridTemplateColumns: 'repeat(3, 38px)', gridTemplateRows: 'repeat(3, 38px)',
      gap: 2,
    }}>
      <div/>{btn('N', '↑')}<div/>
      {btn('W', '←')}
      <div style={{
        width: 38, height: 38, background: RL.panel2,
        boxShadow: `inset 0 0 0 1px ${RL.bevelDk}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <div style={{ width: 6, height: 6, background: RL.bevelLt }}/>
      </div>
      {btn('E', '→')}
      <div/>{btn('S', '↓')}<div/>
    </div>
  );
}

// ─── Action button (square, with optional badge) ──────────────
function ActionBtn({ glyph, label, onClick, badge, hot, big }) {
  const sz = big ? 56 : 44;
  return (
    <button onClick={onClick} className="rl-px"
      style={{
        width: sz, height: sz,
        background: hot ? RL.panel2 : RL.button,
        boxShadow: hot
          ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}, inset 0 0 0 3px ${RL.bevelDk}`
          : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`,
        color: RL.text, border: 'none', cursor: 'pointer', position: 'relative',
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
        gap: 1, padding: 0,
      }}
    >
      <Glyph kind={glyph} size={big ? 22 : 18} color={hot ? RL.gold : RL.text}/>
      <span style={{ fontSize: big ? 9 : 8, letterSpacing: 0.5, color: hot ? RL.gold : RL.textDim }}>{label}</span>
      {badge != null && (
        <div style={{
          position: 'absolute', top: -4, right: -4,
          width: 16, height: 16, background: RL.red,
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}`,
          fontSize: 10, color: RL.text,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontFamily: "'Silkscreen', monospace",
        }}>{badge}</div>
      )}
    </button>
  );
}

// ============================================================
// IN-GAME HUD
// ============================================================
function GameHUD({ go }) {
  const hp = 18, hpMax = 25, xp = 320, xpMax = 500;
  const log = [
    { text: 'A rat appears!',                tone: 'warn'  },
    { text: 'You hit goblin for 7 damage.',  tone: 'good'  },
    { text: 'Goblin misses you.',            tone: 'info'  },
    { text: 'You pick up a potion.',         tone: 'info'  },
  ];

  return (
    <div style={{
      height: '100%', display: 'flex', flexDirection: 'column',
      background: RL.bg, position: 'relative',
    }}>
      {/* Top status bar */}
      <div style={{
        background: RL.panel, padding: '6px 10px',
        boxShadow: `inset 0 -1px 0 ${RL.bevelDk}`,
        display: 'flex', alignItems: 'center', gap: 8,
      }}>
        <div style={{
          width: 38, height: 38, background: RL.panel2,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
        }}>
          <ClassPortrait klass="warrior" size={30}/>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span className="rl-px" style={{ fontSize: 11, color: RL.text }}>Aldric</span>
            <span style={{ fontSize: 14, color: RL.textDim }}>Lvl 4 Warrior</span>
          </div>
          <div style={{ marginTop: 2, display: 'flex', alignItems: 'center', gap: 4 }}>
            <Glyph kind="heart" size={10} color={RL.red}/>
            <div style={{ flex: 1 }}>
              <MeterBar value={hp} max={hpMax} color={hp/hpMax > 0.3 ? RL.green : RL.red} height={8}/>
            </div>
            <span style={{ fontSize: 14, color: RL.text, fontFamily: "'Silkscreen', monospace" }}>
              {hp}/{hpMax}
            </span>
          </div>
          <div style={{ marginTop: 2, display: 'flex', alignItems: 'center', gap: 4 }}>
            <Glyph kind="star" size={10} color={RL.gold}/>
            <div style={{ flex: 1 }}>
              <MeterBar value={xp} max={xpMax} color={RL.gold} height={4}/>
            </div>
          </div>
        </div>
        <IconBtn size={32} onClick={() => go('pause', { from: 'hud' })}>
          <Glyph kind="menu" size={14}/>
        </IconBtn>
      </div>

      {/* Meta strip: depth / turn / gold / status effects */}
      <div style={{
        background: RL.panel2, padding: '4px 10px',
        boxShadow: `inset 0 -1px 0 ${RL.bevelDk}`,
        display: 'flex', alignItems: 'center', gap: 10, fontSize: 14,
      }}>
        <span style={{ color: RL.gold }}>
          <span className="rl-px" style={{ fontSize: 9, letterSpacing: 1 }}>DEPTH</span>
          <span style={{ marginLeft: 4, fontFamily: "'Silkscreen', monospace" }}>06</span>
        </span>
        <span style={{ color: RL.textDim }}>
          <span className="rl-px" style={{ fontSize: 9, letterSpacing: 1 }}>TURN</span>
          <span style={{ marginLeft: 4, color: RL.text, fontFamily: "'Silkscreen', monospace" }}>1284</span>
        </span>
        <div style={{ flex: 1 }}/>
        <span style={{ display: 'flex', alignItems: 'center', gap: 3 }}>
          <Glyph kind="coin" size={11}/>
          <span style={{ color: RL.gold, fontFamily: "'Silkscreen', monospace", fontSize: 12 }}>247</span>
        </span>
        {/* Status effects */}
        <div style={{
          width: 18, height: 18, background: RL.panel,
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.violet}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: RL.violet, fontSize: 12,
        }} title="Blessed">+</div>
      </div>

      {/* Dungeon viewport */}
      <div style={{ padding: 8, flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ flex: 1, minHeight: 0, position: 'relative' }}>
          <Dungeon size={14}/>
          {/* Mini-map corner overlay */}
          <div style={{
            position: 'absolute', top: 8, right: 8,
            width: 56, height: 56, background: RL.panel2,
            boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
            display: 'grid', gridTemplateColumns: `repeat(${MAP[0].length}, 1fr)`,
            padding: 2, gap: 0,
          }}>
            {MAP.map((row, y) => row.split('').map((ch, x) => (
              <div key={`m-${x}-${y}`} style={{
                aspectRatio: '1 / 1',
                background: ch === '#' ? RL.bevelMd : ch === '@' ? RL.gold :
                            (ch === '<' || ch === '>') ? RL.red :
                            ch === '.' ? RL.panel : RL.panel,
              }}/>
            )))}
          </div>
        </div>

        {/* Combat log (sticky 3-line strip) */}
        <div style={{
          background: '#0a0805', padding: '4px 8px',
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
          fontFamily: "'VT323', monospace", fontSize: 16, lineHeight: 1.2,
          maxHeight: 70, overflow: 'hidden',
        }}>
          {log.map((l, i) => (
            <div key={i} style={{
              color: l.tone === 'good' ? RL.green : l.tone === 'warn' ? RL.red : RL.text,
              opacity: 1 - i * 0.18,
            }}>&gt; {l.text}</div>
          ))}
        </div>
      </div>

      {/* Bottom action region: D-pad left, hot actions right */}
      <div style={{
        padding: '8px 10px 12px', background: RL.panel,
        boxShadow: `inset 0 1px 0 ${RL.bevelDk}`,
        display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end',
      }}>
        <DPad onPress={() => {}}/>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'flex-end' }}>
          <div style={{ display: 'flex', gap: 4 }}>
            <ActionBtn glyph="sword"  label="ATTACK" hot/>
            <ActionBtn glyph="potion" label="USE"   badge={3}/>
            <ActionBtn glyph="down"   label="STAIRS"/>
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            <ActionBtn glyph="shield" label="DEFEND"/>
            <ActionBtn glyph="star"   label="MAGIC" badge={2}/>
            <ActionBtn glyph="gear"   label="INV"   onClick={() => go('inv')}/>
          </div>
        </div>
      </div>
    </div>
  );
}

// ============================================================
// CHARACTER / INVENTORY
// ============================================================
const ITEMS = [
  { slot: 0,  kind: 'sword',  name: 'Iron Sword',   tier: 1, equipped: true,  qty: 1 },
  { slot: 1,  kind: 'shield', name: 'Wood Shield',  tier: 1, equipped: true,  qty: 1 },
  { slot: 2,  kind: 'potion', name: 'Heal Potion',  tier: 1, qty: 3, color: RL.red },
  { slot: 3,  kind: 'potion', name: 'Mana Potion',  tier: 1, qty: 1, color: RL.blue },
  { slot: 4,  kind: 'sword',  name: 'Rusty Dagger', tier: 0, qty: 1, broken: true },
  { slot: 5,  kind: 'star',   name: 'Scroll: Map',  tier: 2, qty: 2 },
  { slot: 6,  kind: 'coin',   name: 'Gold (247)',   tier: 0, qty: 247 },
  { slot: 8,  kind: 'star',   name: 'Scroll: ???',  tier: 1, qty: 1, unknown: true },
  { slot: 11, kind: 'potion', name: 'Unknown Vial', tier: 1, qty: 1, color: RL.green, unknown: true },
];

const EQUIP_SLOTS = [
  { id: 'head',  label: 'HEAD',  glyph: null     },
  { id: 'body',  label: 'BODY',  glyph: 'shield' },
  { id: 'main',  label: 'MAIN',  glyph: 'sword'  },
  { id: 'off',   label: 'OFF',   glyph: 'shield' },
  { id: 'ring1', label: 'RING',  glyph: null     },
  { id: 'ring2', label: 'RING',  glyph: null     },
];

function ItemCell({ item, selected, onClick, empty }) {
  return (
    <button onClick={onClick}
      style={{
        aspectRatio: '1 / 1', width: '100%', minWidth: 0,
        background: empty ? RL.panel2 : RL.button,
        boxShadow: selected
          ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}, inset 0 0 0 3px ${RL.bevelDk}`
          : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
        border: 'none', cursor: empty ? 'default' : 'pointer', padding: 0,
        position: 'relative', overflow: 'hidden',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}
    >
      {item && <Glyph kind={item.kind} size={22} color={item.color}/>}
      {item && item.equipped && (
        <div style={{
          position: 'absolute', top: 1, left: 1,
          width: 6, height: 6, background: RL.green,
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}`,
        }}/>
      )}
      {item && item.unknown && (
        <div style={{
          position: 'absolute', inset: 0,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          background: 'rgba(0,0,0,0.4)', color: RL.violet,
          fontFamily: "'Silkscreen', monospace", fontSize: 18,
        }}>?</div>
      )}
      {item && item.qty > 1 && (
        <div className="rl-px" style={{
          position: 'absolute', bottom: 1, right: 2,
          fontSize: 9, color: RL.text,
          textShadow: `1px 1px 0 ${RL.bevelDk}`,
        }}>×{item.qty}</div>
      )}
      {item && item.broken && (
        <div style={{
          position: 'absolute', inset: 0,
          background: 'repeating-linear-gradient(45deg, transparent, transparent 6px, rgba(214,90,74,0.3) 6px, rgba(214,90,74,0.3) 7px)',
        }}/>
      )}
    </button>
  );
}

function Inventory({ go }) {
  const [sel, setSel] = React.useState(0);
  const slots = Array.from({ length: 16 }).map((_, i) => ITEMS.find((it) => it.slot === i) || null);
  const selectedItem = slots[sel];

  return (
    <ScreenLayout
      title="Character"
      onMenu={() => go('pause', { from: 'inv' })}
      footer={<>
        <IconBtn onClick={() => go('hud')}><Glyph kind="back"/></IconBtn>
        {selectedItem && !selectedItem.equipped && (
          <PixelButton size="md" variant="primary">Equip / Use</PixelButton>
        )}
        {selectedItem && selectedItem.equipped && (
          <PixelButton size="md">Unequip</PixelButton>
        )}
        {selectedItem && (
          <PixelButton size="md" variant="danger" fullWidth={false} style={{ width: 60 }}>Drop</PixelButton>
        )}
      </>}
    >
      {/* Identity strip */}
      <div style={{
        background: RL.panel2, padding: 10, marginBottom: 10,
        boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
        display: 'flex', alignItems: 'center', gap: 10,
      }}>
        <div style={{
          width: 54, height: 54, background: RL.button,
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <ClassPortrait klass="warrior" size={44}/>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span className="rl-px" style={{ fontSize: 13, color: RL.text }}>Aldric</span>
            <span style={{ fontSize: 14, color: RL.textDim }}>Lvl 4 Warrior</span>
          </div>
          <div style={{ marginTop: 4 }}>
            <MeterBar value={18} max={25} color={RL.green} height={6} label="HP 18/25"/>
          </div>
          <div style={{ marginTop: 2 }}>
            <MeterBar value={320} max={500} color={RL.gold} height={4} label="XP 320/500"/>
          </div>
        </div>
      </div>

      {/* Stats row */}
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 4, marginBottom: 10,
      }}>
        {[
          ['STR', 14], ['DEX', 9], ['INT', 7], ['WIL', 8],
        ].map(([k, v]) => (
          <div key={k} style={{
            background: RL.button, padding: '4px 0', textAlign: 'center',
            boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
          }}>
            <div className="rl-px" style={{ fontSize: 9, color: RL.textDim, letterSpacing: 1 }}>{k}</div>
            <div className="rl-logo" style={{ fontSize: 14, color: RL.text, marginTop: 1 }}>{v}</div>
          </div>
        ))}
      </div>

      {/* Equipment slots */}
      <div className="rl-px" style={{ fontSize: 10, color: RL.gold, letterSpacing: 1, marginBottom: 4 }}>EQUIPMENT</div>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 4, marginBottom: 10,
      }}>
        {EQUIP_SLOTS.map((slot) => (
          <div key={slot.id} style={{
            aspectRatio: '1 / 1', background: RL.panel2,
            boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
            display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
            color: RL.textFaint, position: 'relative',
          }}>
            {slot.id === 'main' && <Glyph kind="sword" size={18}/>}
            {slot.id === 'off' && <Glyph kind="shield" size={18}/>}
            {(slot.id !== 'main' && slot.id !== 'off') &&
              <span className="rl-px" style={{ fontSize: 8, color: RL.textFaint }}>{slot.label}</span>}
          </div>
        ))}
      </div>

      {/* Inventory grid */}
      <div className="rl-px" style={{ fontSize: 10, color: RL.gold, letterSpacing: 1, marginBottom: 4 }}>
        INVENTORY · {ITEMS.length}/16
      </div>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 4, marginBottom: 10,
      }}>
        {slots.map((it, i) => (
          <ItemCell key={i} item={it} selected={sel === i}
            onClick={() => it && setSel(i)} empty={!it}/>
        ))}
      </div>

      {/* Item detail */}
      {selectedItem && (
        <div style={{
          background: RL.panel2, padding: 10,
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{
              width: 36, height: 36, background: RL.button,
              boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <Glyph kind={selectedItem.kind} size={20} color={selectedItem.color}/>
            </div>
            <div style={{ flex: 1 }}>
              <div className="rl-px" style={{ fontSize: 12, color: RL.gold }}>
                {selectedItem.unknown ? '?? Unidentified ??' : selectedItem.name}
              </div>
              <div style={{ fontSize: 14, color: RL.textDim }}>
                {['Common','Rare','Epic'][selectedItem.tier] || 'Common'}
                {selectedItem.equipped && <span style={{ color: RL.green, marginLeft: 6 }}>· Equipped</span>}
                {selectedItem.broken && <span style={{ color: RL.red, marginLeft: 6 }}>· Broken</span>}
              </div>
            </div>
          </div>
          <div style={{ fontSize: 16, color: RL.text, marginTop: 6, textWrap: 'pretty' }}>
            {selectedItem.kind === 'sword' &&  'Atk 3-10 · Acc +1. Reliable in close quarters.'}
            {selectedItem.kind === 'shield' && 'Block 1-3. Reduces damage on adjacent hits.'}
            {selectedItem.kind === 'potion' && 'Drink to apply. Identify scrolls reveal the effect.'}
            {selectedItem.kind === 'star' &&   'Read on a turn of safety. Single use.'}
            {selectedItem.kind === 'coin' &&   'Spend at shrines and shops.'}
          </div>
        </div>
      )}

      <div style={{ height: 16 }}/>
    </ScreenLayout>
  );
}

Object.assign(window, { GameHUD, Inventory, Dungeon, DPad, ActionBtn, ItemCell });
