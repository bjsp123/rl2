package com.bjsp123.rl2.input;

import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.world.render.LevelRenderer;
import com.bjsp123.rl2.ui.hud.ActionBar;

public class GameInput extends InputAdapter {

    private static final float DRAG_THRESHOLD = 12f;

    private final World world;
    private final OrthographicCamera camera;

    private float touchStartX, touchStartY;
    private Runnable onInteract;
    private Runnable onInventoryToggle;
    private Runnable onLookToggle;
    private Runnable onCharacterToggle;
    /** Invoked with a 0-based action-bar slot index when the user presses a number key
     *  (1 -> slot 0, 2 -> slot 1, ..., up to {@link com.bjsp123.rl2.ui.hud.ActionBar#SLOTS}). */
    private java.util.function.IntConsumer onActionSlot;

    public GameInput(World world, OrthographicCamera camera, CameraController cameraController) {
        this.world            = world;
        this.camera           = camera;
    }

    public void setStairHandlers(Runnable onUp, Runnable onDown) {
    }

    public void setInteractHandler(Runnable onInteract) { this.onInteract = onInteract; }

    /** Wired by PlayScreen to {@link com.bjsp123.rl2.ui.popup.InventoryRenderer#toggle}. The
     *  inventory's own scene2d listener handles {@code i} only after it has keyboard focus
     *  (i.e. after the user has already opened it some other way) - this keeps the hotkey
     *  responsive on the very first press, before any focus transfer has happened. */
    public void setInventoryToggle(Runnable onInventoryToggle) {
        this.onInventoryToggle = onInventoryToggle;
    }

    /** Wired by PlayScreen to {@link com.bjsp123.rl2.input.LookMode#toggle}. The {@code l}
     *  key triggers it; LookMode itself owns the in-mode key handling for cursor movement
     *  and exit. */
    public void setLookToggle(Runnable onLookToggle) {
        this.onLookToggle = onLookToggle;
    }

    /** Wired by PlayScreen to the character-screen toggle. The {@code c} key triggers
     *  it; the character screen owns its own dismiss key once focused. */
    public void setCharacterToggle(Runnable onCharacterToggle) {
        this.onCharacterToggle = onCharacterToggle;
    }

    /** Wired by PlayScreen to {@code triggerActionSlot}. Number keys 1..{@link
     *  com.bjsp123.rl2.ui.hud.ActionBar#SLOTS} fire this with a 0-based slot index. */
    public void setActionSlotHandler(java.util.function.IntConsumer onActionSlot) {
        this.onActionSlot = onActionSlot;
    }

    // -- Keyboard -------------------------------------------------------------

    @Override
    public boolean keyDown(int keycode) {
        // Hotkeys that must respond regardless of whose turn it is - checked before the
        // active-player guard so they fire during AI ticks too.
        if (keycode == Input.Keys.I) {
            if (onInventoryToggle != null) { onInventoryToggle.run(); return true; }
        }
        if (keycode == Input.Keys.L) {
            if (onLookToggle != null) { onLookToggle.run(); return true; }
        }
        if (keycode == Input.Keys.C) {
            if (onCharacterToggle != null) { onCharacterToggle.run(); return true; }
        }
        // Number-row 1..6 -> action-bar slots 0..5. Numpad number keys are reserved for the
        // 8-way movement diamond, so this is top-row-only. Fires regardless of whose turn
        // it is - triggerActionSlot itself gates on isPlayerTurn and the animation queue.
        int slot = numberKeyToSlot(keycode);
        if (slot >= 0) {
            if (onActionSlot != null) { onActionSlot.accept(slot); return true; }
        }
        Mob player = TurnSystem.getActivePlayer(world.currentLevel());
        if (player == null) return false;
        double x = player.position.x(), y = player.position.y();

        if (keycode == Input.Keys.SPACE
                || keycode == Input.Keys.ENTER
                || keycode == Input.Keys.NUMPAD_ENTER) {
            if (onInteract != null) { onInteract.run(); return true; }
        }

        Point target = switch (keycode) {
            case Input.Keys.LEFT,    Input.Keys.NUMPAD_4 -> new Point(x - 1, y);
            case Input.Keys.RIGHT,   Input.Keys.NUMPAD_6 -> new Point(x + 1, y);
            case Input.Keys.UP,      Input.Keys.NUMPAD_8 -> new Point(x,     y + 1);
            case Input.Keys.DOWN,    Input.Keys.NUMPAD_2 -> new Point(x,     y - 1);
            case Input.Keys.NUMPAD_7                     -> new Point(x - 1, y + 1);
            case Input.Keys.NUMPAD_9                     -> new Point(x + 1, y + 1);
            case Input.Keys.NUMPAD_1                     -> new Point(x - 1, y - 1);
            case Input.Keys.NUMPAD_3                     -> new Point(x + 1, y - 1);
            default -> null;
        };

        if (target == null) return false;
        player.targetPosition = target;
        return true;
    }

    /** Maps the number-row keys 1..6 to slot indices 0..5; returns -1 for anything else. */
    private static int numberKeyToSlot(int keycode) {
        return switch (keycode) {
            case Input.Keys.NUM_1 -> 0;
            case Input.Keys.NUM_2 -> 1;
            case Input.Keys.NUM_3 -> 2;
            case Input.Keys.NUM_4 -> 3;
            case Input.Keys.NUM_5 -> 4;
            case Input.Keys.NUM_6 -> 5;
            default               -> -1;
        };
    }

    // -- Touch / click ---------------------------------------------------------

    @Override
    public boolean touchDown(int x, int y, int pointer, int button) {
        if (pointer == 0) { touchStartX = x; touchStartY = y; }
        return false;
    }

    @Override
    public boolean touchUp(int x, int y, int pointer, int button) {
        if (pointer != 0) return false;
        // Ignore if this was a drag (handled by CameraController as pan)
        float dx = x - touchStartX, dy = y - touchStartY;
        if (Math.sqrt(dx * dx + dy * dy) > DRAG_THRESHOLD) return false;

        Mob player = TurnSystem.getActivePlayer(world.currentLevel());
        if (player == null) return false;

        Vector3 world3 = camera.unproject(new Vector3(x, y, 0));
        int tileX = (int) Math.floor(world3.x / LevelRenderer.TILE_SIZE);
        int tileY = (int) Math.floor(world3.y / LevelRenderer.TILE_SIZE);

        // Tap on own tile = interact (e.g. use stairs) instead of move.
        if (tileX == player.position.tileX() && tileY == player.position.tileY()) {
            if (onInteract != null) { onInteract.run(); return true; }
            return false;
        }

        player.targetPosition = new Point(tileX, tileY);
        return true;
    }
}
