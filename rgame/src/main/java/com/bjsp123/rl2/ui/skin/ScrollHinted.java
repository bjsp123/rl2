package com.bjsp123.rl2.ui.skin;

import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;

/**
 * Wraps a {@link ScrollPane} with two small arrow indicators — an up-caret at
 * the top-right, a down-caret at the bottom-right — that appear only when the
 * pane has more content above / below the current view. Visibility is updated
 * each frame inside {@link #act(float)} so the hints stay in sync as the user
 * drags or wheels.
 *
 * <p>Used everywhere the game shows a scrollable list or long content block
 * (hall-of-fame ledgers, the encyclopedia tabs, the level info dump, the
 * crafting recipe list, the map screen overflow). The wrapped {@code ScrollPane}
 * remains accessible to callers that need to programmatically scroll
 * (e.g. centring on the player tile in the map screen) — just keep the
 * reference you passed in.
 */
public final class ScrollHinted extends Stack {

    /** Glyph for the "more above" hint. ASCII so the bundled bitmap font
     *  renders it without needing Unicode coverage. */
    private static final String UP_GLYPH   = "^";
    /** Glyph for the "more below" hint. */
    private static final String DOWN_GLYPH = "v";

    private final ScrollPane pane;
    private final Label upArrow;
    private final Label downArrow;

    public ScrollHinted(ScrollPane pane, Skin skin) {
        this.pane = pane;
        this.upArrow   = makeArrow(UP_GLYPH,   skin);
        this.downArrow = makeArrow(DOWN_GLYPH, skin);

        Container<Label> upPos = new Container<>(upArrow);
        upPos.top().right().pad(2);
        upPos.setTouchable(Touchable.disabled);

        Container<Label> downPos = new Container<>(downArrow);
        downPos.bottom().right().pad(2);
        downPos.setTouchable(Touchable.disabled);

        addActor(pane);
        addActor(upPos);
        addActor(downPos);
        // Start hidden — first act() call will reveal whichever arrow is
        // appropriate once the ScrollPane has measured its content.
        upArrow.setVisible(false);
        downArrow.setVisible(false);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        float maxY = pane.getMaxY();
        float y    = pane.getScrollY();
        // Half-pixel epsilon — ScrollPane's own bounds rounding leaves a hairline
        // residual at the extremes; without the cushion the arrow flickers on
        // for a single frame at rest.
        upArrow.setVisible(y > 0.5f);
        downArrow.setVisible(maxY > 0.5f && y < maxY - 0.5f);
    }

    private static Label makeArrow(String s, Skin skin) {
        Label l = new Label(s, skin, "dim");
        l.setTouchable(Touchable.disabled);
        return l;
    }
}
