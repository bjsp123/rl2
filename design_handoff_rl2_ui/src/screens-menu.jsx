// screens-menu.jsx — MainMenu, SavedGames, ClassSelect, PauseMenu

// ─── AppBar: top row with title + hamburger ───────────────────
function AppBar({ title, onMenu, right }) {
  return (
    <div style={{
      height: 48, padding: '0 12px', display: 'flex',
      alignItems: 'center', gap: 8,
    }}>
      <div style={{ flex: 1, textAlign: 'center', paddingLeft: 44 }}>
        {title && <ScreenTitle>{title}</ScreenTitle>}
      </div>
      <div style={{ flex: '0 0 36px', display: 'flex', justifyContent: 'flex-end' }}>
        {right || (
          <IconBtn onClick={onMenu}><Glyph kind="menu"/></IconBtn>
        )}
      </div>
    </div>
  );
}

// ─── Footer: fixed bottom bar with back / actions ─────────────
function FooterBar({ children, style }) {
  return (
    <div style={{
      position: 'absolute', left: 0, right: 0, bottom: 0,
      padding: '8px 12px', display: 'flex', alignItems: 'center', gap: 8,
      background: 'linear-gradient(to top, rgba(19,16,13,0.95), rgba(19,16,13,0))',
      ...style,
    }}>
      {children}
    </div>
  );
}

// ─── Main screen layout: AppBar + panel + footer ──────────────
function ScreenLayout({ title, onMenu, headerRight, footer, children, contentPad = 12, scroll = true }) {
  return (
    <div style={{
      height: '100%', display: 'flex', flexDirection: 'column',
      background: RL.bg,
      backgroundImage: `radial-gradient(${RL.panel2} 1px, transparent 1px), radial-gradient(${RL.panel2} 1px, transparent 1px)`,
      backgroundSize: '8px 8px',
      backgroundPosition: '0 0, 4px 4px',
      position: 'relative',
    }}>
      <AppBar title={title} onMenu={onMenu} right={headerRight}/>
      <div style={{ flex: 1, minHeight: 0, padding: '0 10px 80px' }}>
        <Panel padding={contentPad} style={{
          height: '100%', overflowY: scroll ? 'auto' : 'hidden',
        }} className="rl-scroll">
          {children}
        </Panel>
      </div>
      {footer && <FooterBar>{footer}</FooterBar>}
    </div>
  );
}

// ============================================================
// MAIN MENU
// ============================================================
function MainMenu({ go, lastSave }) {
  return (
    <ScreenLayout onMenu={() => go('pause')}>
      {/* Big logo */}
      <div style={{ textAlign: 'center', padding: '24px 0 18px' }}>
        <div className="rl-logo" style={{
          fontSize: 56, color: RL.gold,
          textShadow: `4px 4px 0 ${RL.bevelDk}, 8px 8px 0 ${RL.redDk}`,
          letterSpacing: '4px',
        }}>rl2</div>
        <div className="rl-px" style={{ color: RL.textDim, fontSize: 10, marginTop: 14, letterSpacing: 2 }}>
          A TURN-BASED ROGUELIKE
        </div>
      </div>

      {/* Continue card — promotes last save to one-tap action */}
      {lastSave && (
        <div onClick={() => go('hud', { save: lastSave })} style={{
          cursor: 'pointer', marginBottom: 14,
          background: RL.panel2,
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}, inset 0 0 0 3px ${RL.bevelDk}`,
          padding: '10px 12px',
          display: 'flex', alignItems: 'center', gap: 12,
        }}>
          <div style={{
            width: 56, height: 56, background: RL.panel,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
          }}>
            <ClassPortrait klass={lastSave.klass} size={48}/>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="rl-px" style={{ color: RL.gold, fontSize: 11, marginBottom: 4 }}>CONTINUE</div>
            <div style={{ fontSize: 20, color: RL.text }}>
              {lastSave.name} <span style={{ color: RL.textDim }}>· Lvl {lastSave.lvl}</span>
            </div>
            <div style={{ fontSize: 16, color: RL.textDim }}>
              Depth {lastSave.depth} · {lastSave.played}
            </div>
          </div>
          <div style={{ color: RL.gold, fontSize: 28, transform: 'rotate(180deg)' }}>
            <Glyph kind="back" color={RL.gold} size={20}/>
          </div>
        </div>
      )}

      {/* Stack of menu buttons */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <PixelButton size="lg" onClick={() => go('saves')}>Saved Games</PixelButton>
        <PixelButton size="lg" onClick={() => go('hall')}>Hall of Fame</PixelButton>
        <PixelButton size="lg" onClick={() => go('arena')}>Arena</PixelButton>
        <PixelButton size="lg" onClick={() => go('settings')}>Settings</PixelButton>
        <PixelButton size="lg" onClick={() => go('credits')}>Credits</PixelButton>
        <PixelButton size="lg" variant="danger" onClick={() => go('quit')}>Quit</PixelButton>
      </div>

      {/* Footer version line */}
      <div className="rl-px" style={{
        textAlign: 'center', color: RL.textFaint, fontSize: 8,
        marginTop: 16, letterSpacing: 1,
      }}>
        v0.1.4-alpha · build 184
      </div>
    </ScreenLayout>
  );
}

// ============================================================
// SAVED GAMES
// ============================================================
function SavedGames({ go, saves, onDelete }) {
  return (
    <ScreenLayout
      title="Saved Games"
      onMenu={() => go('pause')}
      footer={<>
        <IconBtn onClick={() => go('main')}><Glyph kind="back"/></IconBtn>
        <PixelButton size="md" onClick={() => go('class-select')}>
          + New Game
        </PixelButton>
      </>}
    >
      {saves.length === 0 && (
        <div style={{ textAlign: 'center', padding: 40, color: RL.textDim, fontSize: 18 }}>
          No saved games yet.
          <div style={{ marginTop: 12 }}>
            <PixelButton onClick={() => go('class-select')}>Start a New Run</PixelButton>
          </div>
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {saves.map((s) => (
          <div key={s.id}
            onClick={() => go('hud', { save: s })}
            style={{
              cursor: 'pointer',
              background: RL.button,
              boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`,
              padding: 10,
              display: 'flex', alignItems: 'center', gap: 10,
            }}
          >
            {/* Class portrait */}
            <div style={{
              width: 48, height: 48, background: RL.panel2,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
            }}>
              <ClassPortrait klass={s.klass} size={40}/>
            </div>

            {/* Info */}
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
                <span className="rl-px" style={{ fontSize: 14, color: RL.text }}>{s.name}</span>
                <span style={{ fontSize: 16, color: RL.textDim }}>Lvl {s.lvl}</span>
              </div>
              <div style={{ fontSize: 16, color: RL.textDim, marginTop: 2 }}>
                Depth {s.depth} · {s.played}
              </div>
              {/* HP minibar */}
              <div style={{ marginTop: 4, width: '70%' }}>
                <MeterBar value={s.hp} max={s.hpMax} color={s.hp / s.hpMax > 0.3 ? RL.green : RL.red} height={6}/>
              </div>
            </div>

            {/* Delete X */}
            <button
              onClick={(e) => { e.stopPropagation(); onDelete(s.id); }}
              style={{
                width: 30, height: 30, border: 'none', background: RL.redDk,
                boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.red}`,
                cursor: 'pointer', padding: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}
              title="Delete save"
            >
              <Glyph kind="close" color={RL.text}/>
            </button>
          </div>
        ))}
      </div>
    </ScreenLayout>
  );
}

// ============================================================
// CLASS SELECT
// ============================================================
const CLASSES = [
  {
    id: 'rogue',
    name: 'Rogue',
    blurb: 'Stealthy striker. Crits from shadows.',
    stats: { HP: 18, Acc: 14, Eva: 9, Atk: '2-12', Armor: '1-4' },
    gear: ['dagger', 'leather armor', 'smoke bomb'],
    perks: ['Backstab', 'Light step'],
  },
  {
    id: 'warrior',
    name: 'Warrior',
    blurb: 'Hardy fighter with sword and scale mail.',
    stats: { HP: 25, Acc: 12, Eva: 5, Atk: '3-10', Armor: '5-7' },
    gear: ['sword', 'scale mail', 'healing potion'],
    perks: ['Knockback'],
  },
  {
    id: 'mage',
    name: 'Mage',
    blurb: 'Glass cannon. Spells over steel.',
    stats: { HP: 14, Acc: 11, Eva: 6, Atk: '1-8', Armor: '0-2' },
    gear: ['oak staff', 'spellbook', 'mana flask'],
    perks: ['Arcane Missile', 'Read scrolls +'],
  },
  {
    id: 'ranger',
    name: 'Ranger',
    blurb: 'Patient hunter. Bow at any range.',
    stats: { HP: 20, Acc: 15, Eva: 8, Atk: '2-9', Armor: '2-4' },
    gear: ['shortbow', 'studded leather', 'snare'],
    perks: ['Eagle Eye', 'Forage'],
  },
];

function ClassSelect({ go }) {
  const [idx, setIdx] = React.useState(1);
  const k = CLASSES[idx];

  return (
    <ScreenLayout
      title="New Game"
      onMenu={() => go('pause')}
      footer={<>
        <IconBtn onClick={() => go('saves')}><Glyph kind="back"/></IconBtn>
        <PixelButton variant="primary" size="md" onClick={() => go('hud', { newClass: k.id })}>
          ▶ PLAY
        </PixelButton>
      </>}
    >
      {/* Class tabs */}
      <div style={{ display: 'grid', gridTemplateColumns: `repeat(${CLASSES.length}, 1fr)`, gap: 6, marginBottom: 12 }}>
        {CLASSES.map((c, i) => {
          const active = i === idx;
          return (
            <button key={c.id}
              onClick={() => setIdx(i)}
              style={{
                background: active ? RL.panel2 : RL.button,
                boxShadow: active
                  ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}, inset 0 0 0 3px ${RL.bevelDk}`
                  : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
                border: 'none', padding: '6px 4px', cursor: 'pointer',
                display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
              }}
            >
              <ClassPortrait klass={c.id} size={32}/>
              <span className="rl-px" style={{
                fontSize: 9, color: active ? RL.gold : RL.text,
              }}>{c.name}</span>
            </button>
          );
        })}
      </div>

      {/* Big portrait + name */}
      <div style={{ textAlign: 'center', marginBottom: 10 }}>
        <ScreenTitle style={{ fontSize: 24 }}>{k.name}</ScreenTitle>
        <div style={{
          width: 132, height: 132, margin: '8px auto 0', background: RL.button,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`,
        }}>
          <ClassPortrait klass={k.id} size={104}/>
        </div>
        <div style={{ fontSize: 18, color: RL.text, marginTop: 8, textWrap: 'pretty', padding: '0 8px' }}>
          {k.blurb}
        </div>
      </div>

      {/* Stats */}
      <div style={{ marginTop: 10 }}>
        {Object.entries(k.stats).map(([key, val]) => (
          <StatRow key={key} label={key} value={val} color={key === 'HP' ? RL.green : RL.text}/>
        ))}
      </div>

      {/* Gear */}
      <div style={{ marginTop: 10 }}>
        <div className="rl-px" style={{ color: RL.gold, fontSize: 11, marginBottom: 4 }}>GEAR</div>
        {k.gear.map((g) => (
          <div key={g} style={{ fontSize: 18, color: RL.text, paddingLeft: 14, position: 'relative' }}>
            <span style={{ position: 'absolute', left: 0, color: RL.textDim }}>›</span>{g}
          </div>
        ))}
      </div>

      {/* Perks */}
      <div style={{ marginTop: 10 }}>
        <div className="rl-px" style={{ color: RL.gold, fontSize: 11, marginBottom: 4 }}>PERKS</div>
        {k.perks.map((p) => (
          <div key={p} style={{ fontSize: 18, color: RL.text, paddingLeft: 14, position: 'relative' }}>
            <span style={{ position: 'absolute', left: 0, color: RL.gold }}>★</span>{p}
          </div>
        ))}
      </div>

      <div style={{ height: 24 }}/>
    </ScreenLayout>
  );
}

// ============================================================
// PAUSE MENU (overlay)
// ============================================================
function PauseMenu({ go, from = 'main' }) {
  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'rgba(0,0,0,0.7)',
      display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
      padding: 20, zIndex: 10,
    }}>
      <div style={{ width: '100%', maxWidth: 320 }}>
        <Panel padding={16}>
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: 14 }}>
            <ScreenTitle style={{ fontSize: 18, flex: 1 }}>Paused</ScreenTitle>
            <IconBtn size={30} onClick={() => go(from)}><Glyph kind="close"/></IconBtn>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <PixelButton onClick={() => go(from)}>Resume</PixelButton>
            <PixelButton onClick={() => go('inv')}>Character</PixelButton>
            <PixelButton onClick={() => go('settings')}>Settings</PixelButton>
            <PixelButton onClick={() => go('hall')}>Hall of Fame</PixelButton>
            <PixelButton variant="danger" onClick={() => go('main')}>Save & Quit to Title</PixelButton>
          </div>
        </Panel>
      </div>
    </div>
  );
}

Object.assign(window, {
  AppBar, FooterBar, ScreenLayout,
  MainMenu, SavedGames, ClassSelect, PauseMenu, CLASSES,
});
