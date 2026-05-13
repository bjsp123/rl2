package com.bjsp123.rl2.ui.v2;

/**
 * Plain mutable rectangle in virtual coordinates. Y-up: {@code y} is the
 * BOTTOM edge - matches libGDX's screen-space convention after the viewport
 * unprojects pointer events.
 */
public final class Rect {
    public float x, y, w, h;

    public Rect()                                       { }
    public Rect(float x, float y, float w, float h)     { set(x, y, w, h); }

    public Rect set(float x, float y, float w, float h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        return this;
    }

    public boolean contains(float px, float py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    public float cx() { return x + w / 2f; }
    public float cy() { return y + h / 2f; }
    public float right() { return x + w; }
    public float top()   { return y + h; }
}
