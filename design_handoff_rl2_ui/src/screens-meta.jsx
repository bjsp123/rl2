// screens-meta.jsx — HallOfFame, Arena, Settings, Credits

// ============================================================
// HALL OF FAME
// ============================================================
const RUNS = [
  { id: 1, klass: 'warrior', name: 'Aldric',  lvl: 14, depth: 12, cause: 'slain by Lich King',     turns: 4821, date: 'Apr 28' },
  { id: 2, klass: 'mage',    name: 'Vespa',   lvl: 12, depth: 11, cause: 'starved in the deep',    turns: 5104, date: 'May 02' },
  { id: 3, klass: 'rogue',   name: 'Quill',   lvl: 11, depth: 10, cause: 'poisoned by drake',      turns: 3982, date: 'Apr 19' },
  { id: 4, klass: 'ranger',  name: 'Brynn',   lvl: 10, depth:  9, cause: 'fell into pit trap',     turns: 3210, date: 'Apr 14' },
  { id: 5, klass: 'warrior', name: 'Korg',    lvl:  9, depth:  8, cause: 'crushed by golem',       turns: 2998, date: 'Apr 11' },
  { id: 6, klass: 'mage',    name: 'Lior',    lvl:  9, depth:  8, cause: 'killed by skeleton mage',turns: 2745, date: 'Apr 09' },
  { id: 7, klass: 'rogue',   name: 'Selka',   lvl:  8, depth:  7, cause: 'starved in the deep',    turns: 2410, date: 'Apr 05' },
  { id: 8, klass: 'warrior', name: 'Hadrian', lvl:  7, depth:  5, cause: 'slain by orc captain',   turns: 1820, date: 'Mar 31' },
];

const SCROLLS = [
  { id: 'identify',     name: 'Scroll of Identify',      seen: true,  uses: 14 },
  { id: 'magic-map',    name: 'Scroll of Magic Mapping', seen: true,  uses:  6 },
  { id: 'teleport',     name: 'Scroll of Teleport',      seen: true,  uses:  9 },
  { id: 'enchant',      name: 'Scroll of Enchant',       seen: true,  uses:  3 },
  { id: 'remove-curse', name: 'Scroll of Remove Curse',  seen: true,  uses:  2 },
  { id: 'rage',         name: 'Scroll of Rage',          seen: false, uses:  0 },
  { id: 'recharge',     name: 'Scroll of Recharge',      seen: false, uses:  0 },
  { id: 'terror',       name: 'Scroll of Terror',        seen: false, uses:  0 },
];

function RankBadge({ rank }) {
  const colors = {
    1: { bg: RL.gold,    fg: RL.bevelDk, label: '1ST' },
    2: { bg: '#c0c0c0',  fg: RL.bevelDk, label: '2ND' },
    3: { bg: '#cd7f32',  fg: RL.bevelDk, label: '3RD' },
  };
  const c = colors[rank];
  return (
    <div style={{
      width: 28, height: 28,
      background: c ? c.bg : RL.panel2,
      color:      c ? c.fg : RL.textDim,
      boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${c ? c.bg : RL.bevelMd}`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: "'Silkscreen', monospace", fontSize: 11, fontWeight: 700,
      flexShrink: 0,
    }}>{rank}</div>
  );
}

function RunCard({ run, rank }) {
  return (
    <div style={{
      background: rank <= 3 ? RL.panel2 : RL.button,
      boxShadow: rank <= 3
        ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}, inset 0 0 0 3px ${RL.bevelDk}`
        : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
      padding: 8, display: 'flex', alignItems: 'center', gap: 10,
    }}>
      <RankBadge rank={rank}/>
      <div style={{
        width: 40, height: 40, background: RL.panel,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
        flexShrink: 0,
      }}>
        <ClassPortrait klass={run.klass} size={32}/>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span className="rl-px" style={{ fontSize: 12, color: RL.text }}>{run.name}</span>
          <span style={{ fontSize: 14, color: RL.textDim, textTransform: 'capitalize' }}>· {run.klass} Lvl {run.lvl}</span>
        </div>
        <div style={{
          fontSize: 14, color: RL.textDim, overflow: 'hidden',
          textOverflow: 'ellipsis', whiteSpace: 'nowrap', marginTop: 1,
        }}>
          <Glyph kind="skull" size={11} color={RL.redDk}/> <span style={{ verticalAlign: 'middle' }}>{run.cause}</span>
        </div>
      </div>
      <div style={{ textAlign: 'right', flexShrink: 0 }}>
        <div className="rl-px" style={{ fontSize: 9, color: RL.textDim, letterSpacing: 1 }}>DEPTH</div>
        <div className="rl-logo" style={{ fontSize: 18, color: rank <= 3 ? RL.gold : RL.text, lineHeight: 1 }}>
          {String(run.depth).padStart(2, '0')}
        </div>
      </div>
    </div>
  );
}

function HallOfFame({ go }) {
  const [tab, setTab] = React.useState('runs');
  const best = RUNS[0];
  const totalRuns = 47, totalKills = 312, totalDeaths = 47;

  return (
    <ScreenLayout
      title="Hall of Fame"
      onMenu={() => go('pause')}
      footer={<IconBtn onClick={() => go('main')}><Glyph kind="back"/></IconBtn>}
    >
      {/* Tabs */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6, marginBottom: 12 }}>
        {[['runs', 'Runs'], ['scrolls', 'Bestiary']].map(([id, label]) => {
          const active = tab === id;
          return (
            <button key={id}
              onClick={() => setTab(id)}
              className="rl-px"
              style={{
                background: active ? RL.panel2 : RL.button,
                boxShadow: active
                  ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}, inset 0 0 0 3px ${RL.bevelDk}`
                  : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
                border: 'none', padding: '8px 12px', cursor: 'pointer',
                color: active ? RL.gold : RL.text, fontSize: 12, letterSpacing: 1,
              }}
            >{label}</button>
          );
        })}
      </div>

      {tab === 'runs' && (
        <>
          {/* Personal best banner */}
          <div style={{
            background: RL.panel2,
            boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}, inset 0 0 0 3px ${RL.bevelDk}`,
            padding: 10, marginBottom: 10,
          }}>
            <div className="rl-px" style={{ fontSize: 10, color: RL.gold, letterSpacing: 2, marginBottom: 4 }}>★ PERSONAL BEST</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <div style={{
                width: 52, height: 52, background: RL.panel,
                boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <ClassPortrait klass={best.klass} size={44}/>
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 18, color: RL.text }}>{best.name}</div>
                <div style={{ fontSize: 16, color: RL.textDim, textTransform: 'capitalize' }}>
                  {best.klass} · Lvl {best.lvl} · {best.turns} turns
                </div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div className="rl-px" style={{ fontSize: 9, color: RL.textDim, letterSpacing: 1 }}>DEPTH</div>
                <div className="rl-logo" style={{ fontSize: 28, color: RL.gold, lineHeight: 1 }}>
                  {String(best.depth).padStart(2, '0')}
                </div>
              </div>
            </div>
          </div>

          {/* Totals strip */}
          <div style={{
            display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 6, marginBottom: 12,
          }}>
            {[
              ['RUNS',   totalRuns],
              ['KILLS',  totalKills],
              ['DEATHS', totalDeaths],
            ].map(([label, val]) => (
              <div key={label} style={{
                background: RL.button, padding: '6px 4px', textAlign: 'center',
                boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
              }}>
                <div className="rl-px" style={{ fontSize: 9, color: RL.textDim, letterSpacing: 1 }}>{label}</div>
                <div className="rl-logo" style={{ fontSize: 14, color: RL.text, marginTop: 2 }}>{val}</div>
              </div>
            ))}
          </div>

          {/* Top runs list */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {RUNS.map((r, i) => <RunCard key={r.id} run={r} rank={i + 1}/>)}
          </div>

          <div style={{ height: 16 }}/>
        </>
      )}

      {tab === 'scrolls' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div className="rl-px" style={{ fontSize: 10, color: RL.textDim, padding: '0 2px 4px' }}>
            {SCROLLS.filter((s) => s.seen).length} / {SCROLLS.length} IDENTIFIED
          </div>
          {SCROLLS.map((s) => (
            <div key={s.id} style={{
              background: s.seen ? RL.button : RL.panel2,
              boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
              padding: '8px 10px', display: 'flex', alignItems: 'center', gap: 10,
              opacity: s.seen ? 1 : 0.55,
            }}>
              <div style={{
                width: 28, height: 28, background: RL.panel,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                boxShadow: `inset 0 0 0 1px ${RL.bevelDk}`,
              }}>
                <span style={{ color: s.seen ? RL.gold : RL.textFaint, fontSize: 18 }}>?</span>
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 18, color: s.seen ? RL.text : RL.textFaint }}>
                  {s.seen ? s.name : '???'}
                </div>
                {s.seen && (
                  <div style={{ fontSize: 14, color: RL.textDim }}>used {s.uses}×</div>
                )}
              </div>
              {s.seen && <Glyph kind="check" size={14} color={RL.green}/>}
            </div>
          ))}
          <div style={{ height: 16 }}/>
        </div>
      )}
    </ScreenLayout>
  );
}

// ============================================================
// ARENA — CPU vs CPU configurator
// ============================================================
const FIGHTERS = [
  { id: 'warrior',      name: 'Warrior',       kind: 'class' },
  { id: 'rogue',        name: 'Rogue',         kind: 'class' },
  { id: 'mage',         name: 'Mage',          kind: 'class' },
  { id: 'ranger',       name: 'Ranger',        kind: 'class' },
  { id: 'dungeon-mouse',name: 'Dungeon Mouse', kind: 'mob' },
  { id: 'goblin',       name: 'Goblin',        kind: 'mob' },
  { id: 'orc',          name: 'Orc',           kind: 'mob' },
  { id: 'skeleton',     name: 'Skeleton',      kind: 'mob' },
  { id: 'gnoll',        name: 'Gnoll Brute',   kind: 'mob' },
  { id: 'lich',         name: 'Lich',          kind: 'mob' },
  { id: 'drake',        name: 'Drake',         kind: 'mob' },
];

function FighterCarousel({ value, onChange }) {
  const idx = FIGHTERS.findIndex((f) => f.id === value);
  const step = (d) => onChange(FIGHTERS[(idx + d + FIGHTERS.length) % FIGHTERS.length].id);
  const f = FIGHTERS[idx];
  return (
    <div style={{ display: 'flex', alignItems: 'stretch', gap: 6 }}>
      <button onClick={() => step(-1)} className="rl-px"
        style={{
          width: 36, background: RL.button,
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`,
          border: 'none', cursor: 'pointer', color: RL.text, fontSize: 16,
        }}>‹</button>
      <div style={{
        flex: 1, background: RL.panel2,
        boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
        display: 'flex', alignItems: 'center', gap: 10, padding: '4px 10px',
      }}>
        <ClassPortrait klass={f.kind === 'class' ? f.id : 'rogue'} size={36}/>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="rl-px" style={{ fontSize: 12, color: RL.gold, letterSpacing: 1 }}>{f.name}</div>
          <div style={{ fontSize: 12, color: RL.textDim, textTransform: 'uppercase' }}>{f.kind}</div>
        </div>
        <div style={{ color: RL.textFaint, fontSize: 12, fontFamily: "'Silkscreen', monospace" }}>
          {idx + 1}/{FIGHTERS.length}
        </div>
      </div>
      <button onClick={() => step(1)} className="rl-px"
        style={{
          width: 36, background: RL.button,
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, inset 0 0 0 3px ${RL.bevelDk}`,
          border: 'none', cursor: 'pointer', color: RL.text, fontSize: 16,
        }}>›</button>
    </div>
  );
}

function TeamConfig({ team, color, value, set }) {
  const f = FIGHTERS.find((x) => x.id === value.fighter);
  return (
    <div style={{
      background: RL.panel2, padding: 10,
      boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${color}, inset 0 0 0 3px ${RL.bevelDk}`,
    }}>
      {/* Header row: team label + composition preview */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <div className="rl-px" style={{
          fontSize: 12, color: color, letterSpacing: 1,
          padding: '2px 6px',
          background: RL.bevelDk,
        }}>TEAM {team}</div>
        {/* Mini portrait stack — shows count visually */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 2, flex: 1, minWidth: 0, overflow: 'hidden' }}>
          {Array.from({ length: value.count }).map((_, i) => (
            <div key={i} style={{
              width: 20, height: 20, background: RL.panel,
              boxShadow: `inset 0 0 0 1px ${RL.bevelDk}`,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              flexShrink: 0,
            }}>
              <ClassPortrait klass={f.kind === 'class' ? f.id : 'rogue'} size={16}/>
            </div>
          ))}
        </div>
        <div className="rl-px" style={{ fontSize: 10, color: RL.textDim }}>
          ×{value.count} · L{value.level}
        </div>
      </div>

      {/* Fighter carousel */}
      <FighterCarousel value={value.fighter} onChange={(v) => set({ ...value, fighter: v })}/>

      {/* Level + count */}
      <div style={{ marginTop: 10, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
        <div>
          <div className="rl-px" style={{ fontSize: 10, color: RL.textDim, letterSpacing: 1, marginBottom: 4 }}>LEVEL</div>
          <SegmentedPicker value={value.level}
            onChange={(v) => set({ ...value, level: v })}
            options={[1, 5, 10, 15]}/>
        </div>
        <div>
          <div className="rl-px" style={{ fontSize: 10, color: RL.textDim, letterSpacing: 1, marginBottom: 4 }}>COUNT</div>
          <SegmentedPicker value={value.count}
            onChange={(v) => set({ ...value, count: v })}
            options={[1, 3, 5, 8]}/>
        </div>
      </div>
    </div>
  );
}

function Arena({ go }) {
  const [teamA, setA] = React.useState({ fighter: 'warrior',       level: 5, count: 3 });
  const [teamB, setB] = React.useState({ fighter: 'dungeon-mouse', level: 5, count: 3 });

  const odds = React.useMemo(() => {
    // Cheap heuristic for flavor
    const a = teamA.level * teamA.count;
    const b = teamB.level * teamB.count;
    return Math.round((a / (a + b)) * 100);
  }, [teamA, teamB]);

  return (
    <ScreenLayout
      title="Arena"
      onMenu={() => go('pause')}
      footer={<>
        <IconBtn onClick={() => go('main')}><Glyph kind="back"/></IconBtn>
        <PixelButton size="md" onClick={() => go('hall')}>Hall of Fame</PixelButton>
        <PixelButton size="md" variant="primary" onClick={() => go('hud', { arena: { teamA, teamB } })}>
          ▶ FIGHT
        </PixelButton>
      </>}
    >
      <div className="rl-px" style={{ fontSize: 11, color: RL.textDim, textAlign: 'center', marginBottom: 10, letterSpacing: 1 }}>
        CPU VS CPU · WATCH &amp; WAGER
      </div>

      <TeamConfig team="A" color={RL.gold}    value={teamA} set={setA}/>

      {/* VS divider with odds bar */}
      <div style={{ position: 'relative', margin: '12px 0' }}>
        <div style={{ height: 14, display: 'flex',
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
        }}>
          <div style={{ width: `${odds}%`, background: RL.gold,
            boxShadow: `inset 0 -2px 0 rgba(0,0,0,0.3), inset 0 1px 0 rgba(255,255,255,0.2)` }}/>
          <div style={{ flex: 1, background: RL.red,
            boxShadow: `inset 0 -2px 0 rgba(0,0,0,0.3), inset 0 1px 0 rgba(255,255,255,0.2)` }}/>
        </div>
        <div style={{
          position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%,-50%)',
          background: RL.bg, padding: '4px 10px',
          boxShadow: `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelLt}, 0 0 0 1px ${RL.bevelDk}`,
        }}>
          <span className="rl-logo" style={{ fontSize: 14, color: RL.red, letterSpacing: 1 }}>VS</span>
        </div>
        <div style={{
          display: 'flex', justifyContent: 'space-between', fontSize: 14,
          color: RL.textDim, marginTop: 4,
        }}>
          <span style={{ color: RL.gold }}>A · {odds}%</span>
          <span style={{ color: RL.red  }}>{100 - odds}% · B</span>
        </div>
      </div>

      <TeamConfig team="B" color={RL.red}     value={teamB} set={setB}/>

      <div style={{ height: 16 }}/>
    </ScreenLayout>
  );
}

// ============================================================
// SETTINGS
// ============================================================
function SegmentedPicker({ value, options, onChange }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
      {options.map((o) => {
        const val = typeof o === 'object' ? o.value : o;
        const lbl = typeof o === 'object' ? o.label : String(o);
        const active = value === val;
        return (
          <button key={String(val)} onClick={() => onChange(val)}
            className="rl-px"
            style={{
              minWidth: 44, height: 36, padding: '0 10px',
              background: active ? RL.panel2 : RL.button,
              boxShadow: active
                ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}, inset 0 0 0 3px ${RL.bevelDk}`
                : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
              border: 'none', cursor: 'pointer', fontSize: 12,
              color: active ? RL.gold : RL.text, letterSpacing: 0.5,
            }}
          >{lbl}</button>
        );
      })}
    </div>
  );
}

function SettingRow({ label, sub, children }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <div className="rl-px" style={{ fontSize: 11, color: RL.gold, letterSpacing: 1, marginBottom: 6 }}>
        {label}
      </div>
      {sub && <div style={{ fontSize: 14, color: RL.textDim, marginTop: -4, marginBottom: 6 }}>{sub}</div>}
      {children}
    </div>
  );
}

function Toggle({ value, onChange }) {
  return (
    <button onClick={() => onChange(!value)} className="rl-px"
      style={{
        width: 72, height: 30, position: 'relative',
        background: value ? RL.panel2 : RL.button,
        boxShadow: value
          ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}`
          : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
        border: 'none', cursor: 'pointer', padding: 0,
      }}
    >
      <div style={{
        position: 'absolute', top: 4, left: value ? 40 : 4,
        width: 26, height: 22, background: value ? RL.gold : RL.bevelLt,
        boxShadow: `inset 0 0 0 1px ${RL.bevelDk}`,
        transition: 'left 0.15s',
      }}/>
    </button>
  );
}

function Settings({ go }) {
  const [tab, setTab] = React.useState('display');
  const [s, setS] = React.useState({
    uiScale: 2, fontSize: 1, outlineW: 0.6, outlineDark: 0.75, outlineSmooth: 'smooth',
    music: 60, sfx: 75, haptics: true,
    leftHand: false, joystickStyle: 'fixed', tapMove: true,
    confirmAttack: true, autoSwitch: false, animSpeed: 'normal', lang: 'en',
  });
  const set = (k, v) => setS((p) => ({ ...p, [k]: v }));

  return (
    <ScreenLayout
      title="Settings"
      onMenu={() => go('pause')}
      footer={<IconBtn onClick={() => go('main')}><Glyph kind="back"/></IconBtn>}
    >
      {/* Tabs — matches original three-icon design but with text labels */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 6, marginBottom: 14 }}>
        {[
          ['controls', 'CONTROLS'],
          ['display',  'DISPLAY'],
          ['game',     'GAMEPLAY'],
        ].map(([id, label]) => {
          const active = tab === id;
          return (
            <button key={id} onClick={() => setTab(id)} className="rl-px"
              style={{
                background: active ? RL.panel2 : RL.button,
                boxShadow: active
                  ? `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.gold}, inset 0 0 0 3px ${RL.bevelDk}`
                  : `inset 0 0 0 1px ${RL.bevelDk}, inset 0 0 0 2px ${RL.bevelMd}`,
                border: 'none', padding: '8px 4px', cursor: 'pointer',
                color: active ? RL.gold : RL.text, fontSize: 10, letterSpacing: 0.5,
              }}
            >{label}</button>
          );
        })}
      </div>

      {tab === 'display' && (<>
        <SettingRow label="UI SCALE">
          <SegmentedPicker value={s.uiScale} onChange={(v) => set('uiScale', v)}
            options={[1, 1.5, 2, 2.5, 3, 3.5].map((n) => ({ value: n, label: `${n}x` }))}/>
        </SettingRow>
        <SettingRow label="UI FONT SIZE">
          <SegmentedPicker value={s.fontSize} onChange={(v) => set('fontSize', v)}
            options={[0.75, 1, 1.5, 2].map((n) => ({ value: n, label: `${n}x` }))}/>
        </SettingRow>
        <SettingRow label="MOB OUTLINE WIDTH">
          <SegmentedPicker value={s.outlineW} onChange={(v) => set('outlineW', v)}
            options={[0, 0.3, 0.6, 1, 1.5, 2]}/>
        </SettingRow>
        <SettingRow label="MOB OUTLINE DARKNESS">
          <SegmentedPicker value={s.outlineDark} onChange={(v) => set('outlineDark', v)}
            options={[0.3, 0.55, 0.75, 1]}/>
        </SettingRow>
        <SettingRow label="OUTLINE SMOOTHING">
          <SegmentedPicker value={s.outlineSmooth} onChange={(v) => set('outlineSmooth', v)}
            options={[{ value: 'smooth', label: 'Smooth' }, { value: 'pixel', label: 'Pixel' }]}/>
        </SettingRow>
      </>)}

      {tab === 'controls' && (<>
        <SettingRow label="HANDEDNESS">
          <SegmentedPicker value={s.leftHand ? 'L' : 'R'}
            onChange={(v) => set('leftHand', v === 'L')}
            options={[{ value: 'L', label: 'Left' }, { value: 'R', label: 'Right' }]}/>
        </SettingRow>
        <SettingRow label="JOYSTICK">
          <SegmentedPicker value={s.joystickStyle} onChange={(v) => set('joystickStyle', v)}
            options={[
              { value: 'fixed',  label: 'Fixed' },
              { value: 'float',  label: 'Floating' },
              { value: 'swipe',  label: 'Swipe' },
              { value: 'dpad',   label: 'D-Pad' },
            ]}/>
        </SettingRow>
        <SettingRow label="TAP TO MOVE" sub="Tap any tile to path toward it">
          <Toggle value={s.tapMove} onChange={(v) => set('tapMove', v)}/>
        </SettingRow>
        <SettingRow label="CONFIRM ATTACKS" sub="Ask before attacking the wrong target">
          <Toggle value={s.confirmAttack} onChange={(v) => set('confirmAttack', v)}/>
        </SettingRow>
        <SettingRow label="HAPTICS">
          <Toggle value={s.haptics} onChange={(v) => set('haptics', v)}/>
        </SettingRow>
        <SettingRow label="MUSIC">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <MeterBar value={s.music} max={100} color={RL.gold} height={14}/>
            <span className="rl-px" style={{ fontSize: 12, width: 36, textAlign: 'right' }}>{s.music}%</span>
          </div>
        </SettingRow>
        <SettingRow label="SFX">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <MeterBar value={s.sfx} max={100} color={RL.gold} height={14}/>
            <span className="rl-px" style={{ fontSize: 12, width: 36, textAlign: 'right' }}>{s.sfx}%</span>
          </div>
        </SettingRow>
      </>)}

      {tab === 'game' && (<>
        <SettingRow label="ANIMATION SPEED">
          <SegmentedPicker value={s.animSpeed} onChange={(v) => set('animSpeed', v)}
            options={[
              { value: 'slow',   label: 'Slow' },
              { value: 'normal', label: 'Normal' },
              { value: 'fast',   label: 'Fast' },
              { value: 'instant',label: 'Instant' },
            ]}/>
        </SettingRow>
        <SettingRow label="AUTO-SWITCH WEAPON" sub="Equip best weapon on pickup">
          <Toggle value={s.autoSwitch} onChange={(v) => set('autoSwitch', v)}/>
        </SettingRow>
        <SettingRow label="LANGUAGE">
          <SegmentedPicker value={s.lang} onChange={(v) => set('lang', v)}
            options={[
              { value: 'en', label: 'EN' },
              { value: 'es', label: 'ES' },
              { value: 'fr', label: 'FR' },
              { value: 'de', label: 'DE' },
              { value: 'jp', label: 'JP' },
            ]}/>
        </SettingRow>
        <SettingRow label="DANGER ZONE">
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <PixelButton variant="danger">Reset Tutorial</PixelButton>
            <PixelButton variant="danger">Clear All Saves</PixelButton>
          </div>
        </SettingRow>
      </>)}

      <div style={{ height: 16 }}/>
    </ScreenLayout>
  );
}

// ============================================================
// CREDITS
// ============================================================
function Credits({ go }) {
  const sections = [
    { head: 'GAME BY',     lines: ['(your studio)'] },
    { head: 'PROGRAMMING', lines: ['(lead engineer)', '(systems)', '(tools)'] },
    { head: 'ART',         lines: ['(lead artist)', '(sprites)', '(tilesets)'] },
    { head: 'AUDIO',       lines: ['(composer)', '(sfx design)'] },
    { head: 'WRITING',     lines: ['(narrative)', '(item flavor)'] },
    { head: 'SPECIAL THANKS', lines: ['the libgdx community', 'playtesters', 'roguelike forefathers'] },
    { head: 'BUILT WITH',  lines: ['libgdx', 'scene2d', 'kotlin', 'box2d'] },
  ];

  return (
    <ScreenLayout
      title="Credits"
      onMenu={() => go('pause')}
      footer={<IconBtn onClick={() => go('main')}><Glyph kind="back"/></IconBtn>}
    >
      <div style={{ textAlign: 'center', padding: '8px 0 18px' }}>
        <div className="rl-logo" style={{
          fontSize: 36, color: RL.gold,
          textShadow: `3px 3px 0 ${RL.bevelDk}, 6px 6px 0 ${RL.redDk}`,
          letterSpacing: '3px',
        }}>rl2</div>
        <div className="rl-px" style={{ color: RL.textDim, fontSize: 9, marginTop: 8, letterSpacing: 1 }}>
          v0.1.4-alpha · build 184
        </div>
      </div>

      {sections.map((sec) => (
        <div key={sec.head} style={{ textAlign: 'center', marginBottom: 16 }}>
          <div className="rl-px" style={{ fontSize: 11, color: RL.gold, letterSpacing: 2 }}>{sec.head}</div>
          <div style={{
            margin: '4px auto', width: 60, height: 1,
            borderBottom: `1px dotted ${RL.bevelMd}`,
          }}/>
          {sec.lines.map((l) => (
            <div key={l} style={{ fontSize: 18, color: RL.text, lineHeight: 1.3 }}>{l}</div>
          ))}
        </div>
      ))}

      <div style={{ textAlign: 'center', color: RL.textFaint, fontSize: 14, padding: '8px 0 24px' }}>
        <Glyph kind="heart" size={12} color={RL.red}/>
        <span style={{ marginLeft: 6, verticalAlign: 'middle' }}>made with love &amp; coffee</span>
      </div>
    </ScreenLayout>
  );
}

Object.assign(window, {
  HallOfFame, Arena, Settings, Credits,
  SegmentedPicker, SettingRow, Toggle, RankBadge, RunCard,
  FighterCarousel, TeamConfig, FIGHTERS,
});
