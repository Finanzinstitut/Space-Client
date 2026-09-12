package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;

/**
 * Colours lifted from the Space Client launcher so the in-game menu and the
 * launcher read as one product. Every value is ARGB - a plain RGB value renders
 * fully transparent in this version.
 */
public final class Theme {
    // --- launcher palette ---
    public static final int BG_DEEP   = 0xFF05040F; // --bg-deep
    public static final int BG_MID    = 0xFF1A1440; // radial highlight
    public static final int PANEL     = 0xE00D0B23; // --bg-panel
    public static final int PANEL_ALT = 0xFF14113A;
    public static final int BORDER    = 0xFF2A2555; // --border
    public static final int TEXT      = 0xFFE9E6FF; // --text
    public static final int TEXT_DIM  = 0xFF9A95C9; // --text-dim
    public static final int CYAN      = 0xFF38E0FF; // --accent-2
    public static final int OFF       = 0xFF3A3560;

    // --- surfaces for the card layout ---
    public static final int SIDEBAR      = 0xF00A0820;
    public static final int SIDEBAR_PICK = 0x33FFFFFF;
    public static final int CONTENT      = 0xE6080618;
    public static final int CARD         = 0xFF15123A;
    public static final int CARD_HOVER   = 0xFF1E1A4E;

    /**
     * Chips sit on the dark content panel with nothing behind them, so the card
     * colour left them nearly invisible - the same fill that reads as a raised
     * card against a lit grid reads as nothing at all against empty background.
     */
    /**
     * Chips are lifted to the brightness of a picked sidebar row.
     *
     * They sit on the bare content panel with nothing behind them, so the
     * values that read as a raised card against the lit module grid vanished
     * against empty background. These are deliberately lighter than any other
     * surface here: a filter row that cannot be read is a filter row nobody
     * uses.
     */
    public static final int CHIP         = 0xFF6055B8;
    public static final int CHIP_HOVER   = 0xFF7669D6;
    public static final int CHIP_BORDER  = 0xFFA79CF0;
    public static final int CARD_FOOT    = 0xFF0E0C28;
    public static final int BORDER_HOVER = 0xFF453D80;

    /** Text drawn on top of a filled accent strip, which is bright. */
    public static final int TEXT_ON_ACCENT = 0xFF0A0818;

    public static int backdrop() {
        return switch (SpaceClient.getSettings().backgroundStyle()) {
            case "SOLID_BLACK" -> 0xFF000000;
            case "TRANSPARENT" -> 0x66000000;
            case "DARK" -> 0xF00A0A12;
            default -> BG_DEEP;
        };
    }

    /** The starfield background is the launcher's signature look. */
    public static boolean spaceBackdrop() {
        return SpaceClient.getSettings().backgroundStyle().equals("SPACE");
    }

    public static int accent() {
        return SpaceClient.getSettings().accentColor();
    }

    /** Dimmed accent for filled backgrounds behind text. */
    public static int accentDim() {
        int accent = accent();
        int r = ((accent >> 16) & 0xFF) / 4;
        int g = ((accent >> 8) & 0xFF) / 4;
        int b = (accent & 0xFF) / 4;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private Theme() {}
}
