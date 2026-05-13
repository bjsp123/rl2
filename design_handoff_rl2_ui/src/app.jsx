// app.jsx — Top-level router + design-canvas layout

const DEFAULT_SAVES = [
  { id: 1, klass: 'rogue',   name: 'Rogue',   lvl:  1, depth: 1, hp:  9, hpMax: 14, played: '00:12' },
  { id: 2, klass: 'rogue',   name: 'Rogue',   lvl: 10, depth: 6, hp: 28, hpMax: 32, played: '02:41' },
  { id: 3, klass: 'warrior', name: 'Warrior', lvl:  4, depth: 2, hp: 18, hpMax: 25, played: '00:37' },
];

const LAST_SAVE = { ...DEFAULT_SAVES[1], klass: 'rogue', name: 'Quill', played: '02:41' };

// Each artboard mounts an independent App rooted at a chosen initial screen.
function App({ initial = 'main' }) {
  const [screen, setScreen] = React.useState(initial);
  const [overlay, setOverlay] = React.useState(null);    // 'pause' overlay
  const [saves, setSaves] = React.useState(DEFAULT_SAVES);
  const [pauseFrom, setPauseFrom] = React.useState('main');

  const go = (target, opts = {}) => {
    if (target === 'pause') {
      setPauseFrom(opts.from || screen);
      setOverlay('pause');
      return;
    }
    if (target === 'quit') return;     // no-op in prototype
    setOverlay(null);
    setScreen(target);
  };

  const deleteSave = (id) => setSaves((s) => s.filter((x) => x.id !== id));

  let body = null;
  switch (screen) {
    case 'main':         body = <MainMenu     go={go} lastSave={LAST_SAVE}/>; break;
    case 'saves':        body = <SavedGames   go={go} saves={saves} onDelete={deleteSave}/>; break;
    case 'class-select': body = <ClassSelect  go={go}/>; break;
    case 'hall':         body = <HallOfFame   go={go}/>; break;
    case 'arena':        body = <Arena        go={go}/>; break;
    case 'settings':     body = <Settings     go={go}/>; break;
    case 'credits':      body = <Credits      go={go}/>; break;
    case 'hud':          body = <GameHUD      go={go}/>; break;
    case 'inv':          body = <Inventory    go={go}/>; break;
    default:             body = <MainMenu     go={go} lastSave={LAST_SAVE}/>;
  }

  return (
    <PhoneShell>
      {body}
      {overlay === 'pause' && <PauseMenu go={go} from={pauseFrom}/>}
    </PhoneShell>
  );
}

// ============================================================
// CANVAS — lay out all 10 screens for review
// ============================================================
function Canvas() {
  return (
    <DesignCanvas>
      <DCSection id="title" title="Title & flow" subtitle="Entry points into the game">
        <DCArtboard id="main"    label="01 · Main Menu"    width={390} height={780}>
          <App initial="main"/>
        </DCArtboard>
        <DCArtboard id="saves"   label="02 · Saved Games"  width={390} height={780}>
          <App initial="saves"/>
        </DCArtboard>
        <DCArtboard id="class"   label="03 · Class Select" width={390} height={780}>
          <App initial="class-select"/>
        </DCArtboard>
      </DCSection>

      <DCSection id="meta" title="Meta screens" subtitle="Records, arena, configuration">
        <DCArtboard id="hall"     label="04 · Hall of Fame" width={390} height={780}>
          <App initial="hall"/>
        </DCArtboard>
        <DCArtboard id="arena"    label="05 · Arena (CPU vs CPU)" width={390} height={780}>
          <App initial="arena"/>
        </DCArtboard>
        <DCArtboard id="settings" label="06 · Settings" width={390} height={780}>
          <App initial="settings"/>
        </DCArtboard>
        <DCArtboard id="credits"  label="07 · Credits"  width={390} height={780}>
          <App initial="credits"/>
        </DCArtboard>
      </DCSection>

      <DCSection id="game" title="In-game" subtitle="The actual play loop">
        <DCArtboard id="hud"   label="08 · Game HUD"    width={390} height={780}>
          <App initial="hud"/>
        </DCArtboard>
        <DCArtboard id="inv"   label="09 · Character / Inventory" width={390} height={780}>
          <App initial="inv"/>
        </DCArtboard>
        <DCArtboard id="pause" label="10 · Pause Menu (overlay)" width={390} height={780}>
          <PauseDemo/>
        </DCArtboard>
      </DCSection>

      <DCPostIt x={40} y={40} w={240}>
        <strong>rl2 · UI direction</strong>
        <br/>Polished pixel, warm wood + parchment + gold. Tap any artboard to enter it fullscreen, navigation works inside.
      </DCPostIt>
    </DesignCanvas>
  );
}

// Pause overlay rendered over the main menu for a clean preview
function PauseDemo() {
  const [screen, setScreen] = React.useState('main');
  const go = (t) => { if (t === 'pause') return; setScreen(t); };
  return (
    <PhoneShell>
      <MainMenu go={() => {}} lastSave={LAST_SAVE}/>
      <PauseMenu go={() => setScreen(screen)} from="main"/>
    </PhoneShell>
  );
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<Canvas/>);
