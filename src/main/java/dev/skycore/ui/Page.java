package dev.skycore.ui;

import dev.skycore.ui.gfx.Icon;

/** One sidebar destination. Pages are created once per screen and keep their own state while switching. */
public interface Page {
    String id();

    Icon icon();

    /**
     * Draw the page into the content area. {@code area.y} is already offset by the scroll position.
     *
     * @return total content height (used for scrolling)
     */
    float render(Ui ui, Rect area, SkycoreScreen screen);

    default void onShow() {}
}
