package com.velometrics.app.ui.navigation

/**
 * MD3 window width size class, bucketed from the current width in dp using the standard
 * Material breakpoints. Deliberately computed from [LocalConfiguration] rather than pulling in
 * the `material3-window-size-class` artifact — this app only needs the width bucket to decide
 * between a bottom nav bar and a nav rail and to cap content width on large screens, and the
 * breakpoints are stable, so a small local enum avoids an extra dependency for that.
 *
 * See https://m3.material.io/foundations/layout/applying-layout/window-size-classes
 */
enum class WindowWidthSizeClass {
    Compact,
    Medium,
    Expanded;

    companion object {
        fun fromWidth(widthDp: Int): WindowWidthSizeClass = when {
            widthDp < 600 -> Compact
            widthDp < 840 -> Medium
            else -> Expanded
        }
    }
}
