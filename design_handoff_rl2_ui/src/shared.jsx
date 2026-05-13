// shared.jsx — Pixel-UI primitives, palette, fonts, class icons.
// All components attach to window so other Babel <script> blocks can use them.

const RL = {
  // Surfaces — warm browns
  bg:        '#13100d',          // outside the panel (phone canvas)
  panel:     '#3a2a24',          // main panel fill
  panel2:    '#2c1f1a',          // recessed panel
  button:    '#4a3328',          // button face
  buttonHi:  '#5a4032',          // button hover
  buttonLo:  '#2e2018',          // pressed
  // Borders / bevels
  bevelDk:   '#1a120e',          // outer dark line
  bevelMd:   '#5a4a3a',          // inner shadow
  bevelLt:   '#8a7565',          // top-light highlight
  // Text
  text:      '#f0e6d2',          // parchment white
  textDim:   '#a89683',          // muted
  textFaint: '#6b5a4a',          // very dim
  // Accents
  gold:      '#f0c674',          // titles, currency
  goldDk:    '#a87a36',
  red:       '#d65a4a',          // HP, danger, delete
  redDk:     '#7a2a20',
  green:     '#6db35a',          // HP-good, save
  blue:      '#5a8cc4',          // mana
  violet:    '#a675d6',          // magic
};

// Inject global CSS once — fonts, pixel rendering, scrollbar hide.
if (!document.getElementById('rl-styles')) {
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = 'https://fonts.googleapis.com/css2?family=Press+Start+2P&family=Silkscreen:wght@400;700&family=VT323&display=swap';
  document.head.appendChild(link);

  const s = document.createElement('style');
  s.id = 'rl-styles';
  s.textContent = `
    .rl-root, .rl-root * {
      font-family: 'VT323', 'Courier New', monospace;
      image-rendering: pixelated;
      -webkit-font-smoothing: none;
      font-smooth: never;
      box-sizing: border-box;
    }
    .rl-root { color: ${RL.text}; }
    .rl-px { font-family: 'Silkscreen', monospace; letter-spacing: 0.5px; }
    .rl-logo { font-family: 'Press Start 2P', monospace; letter-spacing: 1px; }
    .rl-scroll::-webkit-scrollbar { width: 6px; }
    .rl-scroll::-webkit-scrollbar-track { background: ${RL.panel2}; }
    .rl-scroll::-webkit-scrollbar-thumb { background: ${RL.bevelMd}; border: 1px solid ${RL.bevelDk}; }
    .rl-btn-pressable:active { transform: translateY(1px); }
    @keyframes rl-blink { 0%, 60% { opacity: 1 } 60.01%, 100% { opacity: 0 } }
    .rl-blink { animation: rl-blink 1s steps(1) infinite; }
    @keyframes rl-pulse { 0%, 100% { opacity: 0.6 } 50% { opacity: 1 } }
    .rl-pulse { animation: rl-pulse 1.4s ease-in-out infinite; }
  `;
  document.head.appendChild(s);
}

// ─── Phone canvas ─────────────────────────────────────────────
// Black bezel + dark interior to match the libgdx screenshots.
// Default 390x780 (close to the user's screenshots' 758x1543 ratio).
function PhoneShell({ children, width = 390, height = 780, style }) {
  return (
    <div className="rl-root" style={{
      width, height, background: '#000', position: 'relative',
      overflow: 'hidden', userSelect: 'none', ...style,
    }}>
      {children}
    </div>
  );
}

// ─── Bevel panel: classic 3-step pixel border ─────────────────
function Panel({ children, style, recessed = false, padding = 12, ...rest }) {
  const bg = recessed ? RL.panel2 : RL.panel;
  return (
    <div {...rest} style={{
      background: bg,
      // Multi-shadow recreates a pixel bevel: outer dark / inner light-top / inner dark-bottom
      boxShadow: recessed
        ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}, inset 0 0 0 3px ${RL.bevelDk}`
        : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`,
      padding,
      ...style,
    }}>
      {children}
    </div>
  );
}

// ─── PixelButton: bevelled tappable button ────────────────────
function PixelButton({ children, onClick, variant = 'default', size = 'md', style, disabled, fullWidth = true }) {
  const [hover, setHover] = React.useState(false);
  const [down, setDown]   = React.useState(false);

  const face =
    variant === 'danger' ? '#5a2620' :
    variant === 'primary' ? '#3a5a32' :
    variant === 'ghost'  ? 'transparent' :
    (hover ? RL.buttonHi : RL.button);

  const accent =
    variant === 'danger' ? RL.red :
    variant === 'primary' ? RL.green :
    null;

  const pad = size === 'sm' ? '4px 8px' : size === 'lg' ? '14px 16px' : '10px 14px';
  const fs  = size === 'sm' ? 16 : size === 'lg' ? 22 : 18;

  return (
    <button
      onClick={disabled ? undefined : onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => { setHover(false); setDown(false); }}
      onMouseDown={() => setDown(true)}
      onMouseUp={() => setDown(false)}
      className="rl-px rl-btn-pressable"
      style={{
        background: down ? RL.buttonLo : face,
        color: disabled ? RL.textFaint : (accent || RL.text),
        border: 'none',
        padding: pad,
        fontSize: fs,
        fontFamily: "'Silkscreen', monospace",
        letterSpacing: '0.5px',
        cursor: disabled ? 'not-allowed' : 'pointer',
        width: fullWidth ? '100%' : 'auto',
        opacity: disabled ? 0.5 : 1,
        boxShadow: variant === 'ghost' ? 'none' :
          (down
            ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`
            : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`),
        textAlign: 'center',
        ...style,
      }}
    >
      {children}
    </button>
  );
}

// ─── Icon button: small square (for hamburger, back, X) ───────
function IconBtn({ children, onClick, variant = 'default', size = 36, style }) {
  return (
    <PixelButton
      onClick={onClick}
      variant={variant}
      fullWidth={false}
      style={{
        width: size, height: size, padding: 0,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        ...style,
      }}
    >
      {children}
    </PixelButton>
  );
}

// ─── Hamburger / back / X / gear glyphs as inline pixel SVGs ──
function Glyph({ kind, size = 18, color = RL.text }) {
  const sz = { width: size, height: size, display: 'block' };
  switch (kind) {
    case 'menu':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="2" y="3" width="12" height="2" fill={color}/>
          <rect x="2" y="7" width="12" height="2" fill={color}/>
          <rect x="2" y="11" width="12" height="2" fill={color}/>
        </svg>
      );
    case 'back':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="3" y="7" width="10" height="2" fill={color}/>
          <rect x="4" y="5" width="2" height="2" fill={color}/>
          <rect x="3" y="6" width="2" height="2" fill={color}/>
          <rect x="2" y="7" width="2" height="2" fill={color}/>
          <rect x="3" y="9" width="2" height="2" fill={color}/>
          <rect x="4" y="10" width="2" height="2" fill={color}/>
        </svg>
      );
    case 'close':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="3" y="3" width="2" height="2" fill={color}/>
          <rect x="5" y="5" width="2" height="2" fill={color}/>
          <rect x="7" y="7" width="2" height="2" fill={color}/>
          <rect x="9" y="9" width="2" height="2" fill={color}/>
          <rect x="11" y="11" width="2" height="2" fill={color}/>
          <rect x="11" y="3" width="2" height="2" fill={color}/>
          <rect x="9" y="5" width="2" height="2" fill={color}/>
          <rect x="5" y="9" width="2" height="2" fill={color}/>
          <rect x="3" y="11" width="2" height="2" fill={color}/>
        </svg>
      );
    case 'gear':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="7" y="1" width="2" height="2" fill={color}/>
          <rect x="7" y="13" width="2" height="2" fill={color}/>
          <rect x="1" y="7" width="2" height="2" fill={color}/>
          <rect x="13" y="7" width="2" height="2" fill={color}/>
          <rect x="3" y="3" width="2" height="2" fill={color}/>
          <rect x="11" y="3" width="2" height="2" fill={color}/>
          <rect x="3" y="11" width="2" height="2" fill={color}/>
          <rect x="11" y="11" width="2" height="2" fill={color}/>
          <rect x="5" y="5" width="6" height="6" fill={color}/>
          <rect x="6" y="6" width="4" height="4" fill={RL.panel}/>
          <rect x="7" y="7" width="2" height="2" fill={color}/>
        </svg>
      );
    case 'check':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="2" y="8" width="2" height="2" fill={color}/>
          <rect x="4" y="10" width="2" height="2" fill={color}/>
          <rect x="6" y="12" width="2" height="2" fill={color}/>
          <rect x="8" y="10" width="2" height="2" fill={color}/>
          <rect x="10" y="8" width="2" height="2" fill={color}/>
          <rect x="12" y="6" width="2" height="2" fill={color}/>
        </svg>
      );
    case 'heart':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="2" y="3" width="3" height="2" fill={color}/>
          <rect x="11" y="3" width="3" height="2" fill={color}/>
          <rect x="1" y="5" width="14" height="2" fill={color}/>
          <rect x="2" y="7" width="12" height="2" fill={color}/>
          <rect x="3" y="9" width="10" height="2" fill={color}/>
          <rect x="5" y="11" width="6" height="2" fill={color}/>
          <rect x="7" y="13" width="2" height="2" fill={color}/>
        </svg>
      );
    case 'skull':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="3" y="2" width="10" height="2" fill={color}/>
          <rect x="2" y="4" width="12" height="6" fill={color}/>
          <rect x="4" y="6" width="2" height="2" fill={RL.panel2}/>
          <rect x="10" y="6" width="2" height="2" fill={RL.panel2}/>
          <rect x="3" y="10" width="3" height="2" fill={color}/>
          <rect x="7" y="10" width="2" height="2" fill={color}/>
          <rect x="10" y="10" width="3" height="2" fill={color}/>
          <rect x="4" y="12" width="2" height="2" fill={color}/>
          <rect x="8" y="12" width="2" height="2" fill={color}/>
          <rect x="11" y="12" width="2" height="2" fill={color}/>
        </svg>
      );
    case 'coin':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="4" y="2" width="8" height="2" fill={RL.gold}/>
          <rect x="2" y="4" width="12" height="8" fill={RL.gold}/>
          <rect x="4" y="12" width="8" height="2" fill={RL.gold}/>
          <rect x="3" y="5" width="2" height="6" fill={RL.goldDk}/>
          <rect x="11" y="5" width="2" height="6" fill={RL.goldDk}/>
          <rect x="7" y="5" width="2" height="6" fill={RL.goldDk}/>
        </svg>
      );
    case 'sword':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="7" y="1" width="2" height="8" fill="#cdd5dd"/>
          <rect x="8" y="1" width="1" height="8" fill="#7e8a99"/>
          <rect x="5" y="9" width="6" height="1" fill={RL.goldDk}/>
          <rect x="7" y="10" width="2" height="3" fill={RL.gold}/>
          <rect x="6" y="13" width="4" height="1" fill={RL.goldDk}/>
        </svg>
      );
    case 'shield':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="3" y="2" width="10" height="2" fill={RL.bevelLt}/>
          <rect x="2" y="4" width="12" height="6" fill={RL.bevelLt}/>
          <rect x="3" y="10" width="10" height="2" fill={RL.bevelLt}/>
          <rect x="5" y="12" width="6" height="2" fill={RL.bevelLt}/>
          <rect x="7" y="14" width="2" height="1" fill={RL.bevelLt}/>
          <rect x="6" y="6" width="4" height="2" fill={RL.red}/>
          <rect x="7" y="4" width="2" height="6" fill={RL.red}/>
        </svg>
      );
    case 'potion':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="6" y="1" width="4" height="2" fill="#888"/>
          <rect x="6" y="3" width="4" height="1" fill={RL.bevelMd}/>
          <rect x="5" y="4" width="6" height="2" fill={color}/>
          <rect x="4" y="6" width="8" height="7" fill={color}/>
          <rect x="5" y="13" width="6" height="1" fill={color}/>
          <rect x="6" y="7" width="1" height="3" fill="#fff"/>
        </svg>
      );
    case 'star':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="7" y="2" width="2" height="2" fill={color}/>
          <rect x="6" y="4" width="4" height="2" fill={color}/>
          <rect x="1" y="6" width="14" height="2" fill={color}/>
          <rect x="3" y="8" width="10" height="2" fill={color}/>
          <rect x="4" y="10" width="3" height="2" fill={color}/>
          <rect x="9" y="10" width="3" height="2" fill={color}/>
          <rect x="2" y="12" width="3" height="2" fill={color}/>
          <rect x="11" y="12" width="3" height="2" fill={color}/>
        </svg>
      );
    case 'down':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="3" y="5" width="2" height="2" fill={color}/>
          <rect x="11" y="5" width="2" height="2" fill={color}/>
          <rect x="5" y="7" width="2" height="2" fill={color}/>
          <rect x="9" y="7" width="2" height="2" fill={color}/>
          <rect x="7" y="9" width="2" height="2" fill={color}/>
        </svg>
      );
    case 'plus':
      return (
        <svg viewBox="0 0 16 16" style={sz} shapeRendering="crispEdges">
          <rect x="7" y="3" width="2" height="10" fill={color}/>
          <rect x="3" y="7" width="10" height="2" fill={color}/>
        </svg>
      );
    default: return null;
  }
}

// ─── Class portrait placeholder ───────────────────────────────
// Renders a simple "sprite" placeholder so the user knows where their
// own pixel art will drop in. Color-coded by class.
function ClassPortrait({ klass = 'rogue', size = 84 }) {
  const palette = {
    rogue:   { skin: '#d4a884', cloak: '#3b3a47', accent: '#1a1a1f' },
    warrior: { skin: '#d4a884', cloak: '#7a3a2a', accent: '#cdd5dd' },
    mage:    { skin: '#d4a884', cloak: '#4a3a7a', accent: '#c89cff' },
    ranger:  { skin: '#d4a884', cloak: '#3a5a32', accent: '#6db35a' },
  };
  const p = palette[klass] || palette.rogue;
  // Tiny 16x16 sprite — generic humanoid silhouette
  return (
    <svg viewBox="0 0 16 16" width={size} height={size} shapeRendering="crispEdges" style={{ imageRendering: 'pixelated' }}>
      {/* head */}
      <rect x="6" y="2" width="4" height="4" fill={p.skin}/>
      <rect x="5" y="1" width="6" height="2" fill={p.accent}/>
      <rect x="6" y="5" width="1" height="1" fill="#000"/>
      <rect x="9" y="5" width="1" height="1" fill="#000"/>
      {/* body / cloak */}
      <rect x="5" y="6" width="6" height="6" fill={p.cloak}/>
      <rect x="6" y="6" width="4" height="1" fill={p.skin}/>
      <rect x="4" y="7" width="1" height="4" fill={p.cloak}/>
      <rect x="11" y="7" width="1" height="4" fill={p.cloak}/>
      {/* legs */}
      <rect x="6" y="12" width="1" height="3" fill={p.cloak}/>
      <rect x="9" y="12" width="1" height="3" fill={p.cloak}/>
      <rect x="6" y="14" width="2" height="1" fill="#3a2820"/>
      <rect x="8" y="14" width="2" height="1" fill="#3a2820"/>
    </svg>
  );
}

// ─── Section header w/ gold "rl2" style ───────────────────────
function ScreenTitle({ children, style }) {
  return (
    <h1 className="rl-px" style={{
      color: RL.gold,
      fontSize: 22,
      margin: 0,
      textShadow: `2px 2px 0 ${RL.bevelDk}`,
      letterSpacing: '1px',
      ...style,
    }}>{children}</h1>
  );
}

// ─── Stat row (Label .................. Value) ────────────────
function StatRow({ label, value, color, sub }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'baseline', gap: 6,
      fontSize: 20, fontFamily: "'VT323', monospace",
    }}>
      <span style={{ color: RL.text }}>{label}</span>
      <span style={{ flex: 1, borderBottom: `1px dotted ${RL.bevelMd}`, transform: 'translateY(-4px)' }}/>
      <span style={{ color: color || RL.text }}>{value}</span>
      {sub && <span style={{ color: RL.textDim, fontSize: 16 }}>{sub}</span>}
    </div>
  );
}

// ─── Progress / HP bar — segmented pixel style ────────────────
function MeterBar({ value, max, color = RL.green, height = 10, segments = 0, label }) {
  const pct = Math.max(0, Math.min(1, value / max));
  return (
    <div style={{ width: '100%' }}>
      {label && <div className="rl-px" style={{ fontSize: 10, color: RL.textDim, marginBottom: 2 }}>{label}</div>}
      <div style={{
        width: '100%', height, background: RL.bevelDk,
        boxShadow: `inset 0 0 0 1px ${RL.bevelMd}`, position: 'relative',
      }}>
        <div style={{
          position: 'absolute', inset: 1,
          width: `calc(${pct * 100}% - 2px)`, background: color,
          boxShadow: `inset 0 -2px 0 rgba(0,0,0,0.3), inset 0 1px 0 rgba(255,255,255,0.2)`,
        }}/>
        {segments > 0 && Array.from({ length: segments - 1 }).map((_, i) => (
          <div key={i} style={{
            position: 'absolute', top: 0, bottom: 0,
            left: `${((i + 1) / segments) * 100}%`,
            width: 1, background: RL.bevelDk,
          }}/>
        ))}
      </div>
    </div>
  );
}

// Subtle tile-pattern background — repeating dots for "stone floor" feel
const TILE_BG = `
  background-color: ${RL.bg};
  background-image:
    radial-gradient(${RL.panel2} 1px, transparent 1px),
    radial-gradient(${RL.panel2} 1px, transparent 1px);
  background-size: 8px 8px;
  background-position: 0 0, 4px 4px;
`;

Object.assign(window, {
  RL, PhoneShell, Panel, PixelButton, IconBtn, Glyph,
  ClassPortrait, ScreenTitle, StatRow, MeterBar, TILE_BG,
});
