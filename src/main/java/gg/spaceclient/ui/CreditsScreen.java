package gg.spaceclient.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Who else's work went into this, and under what terms.
 *
 * Not a formality. Two of the things this client does were worked out by
 * somebody else first, and the licences they published under are not the same
 * as each other - one of them rules out copying into a client licensed the way
 * this one is. Writing that down where it can be read is the difference
 * between crediting people and quietly borrowing from them.
 */
public class CreditsScreen extends Screen {

    private static final int PANEL_W = 460;

    private final Screen parent;

    private double scroll = 0;
    private int contentBottom = 0;

    public CreditsScreen(Screen parent) {
        super(Component.literal("Credits"));
        this.parent = parent;
    }

    /** One entry: who, what for, and the terms it came under. */
    private record Entry(String name, String by, String licence, String[] lines) {}

    private static final Entry[] ENTRIES = {
            new Entry("WaveyCapes", "tr7zw", "tr7zw Protective License",
                    new String[]{
                            "Copyright (c) tr7zw, 2021.",
                            "Showed that a cape reads as cloth only once it is drawn as a",
                            "chain of segments rather than one flat slab. Space Client's",
                            "cape motion is its own code and steers the angles the game",
                            "already keeps, but the idea and the reference are tr7zw's.",
                            "The licence permits use and modification, and forbids using",
                            "the work for commercial advantage or payment.",
                            "github.com/tr7zw/WaveyCapes",
                    }),

            new Entry("ItemPhysic", "CreativeMD / team.creative", "LGPL-3.0",
                    new String[]{
                            "Dropped items lying flat on the ground, stacking and settling",
                            "instead of spinning in the air, is CreativeMD's idea and this",
                            "client has no better one.",
                            "",
                            "None of that code is in here, and the reason is the licence,",
                            "not the effort: LGPL-3.0 code cannot be copied into a client",
                            "released as all rights reserved.",
                            "",
                            "Space Client's Item Physics module is a separate implementation",
                            "of the same idea, written without reference to their source.",
                            "github.com/CreativeMD/ItemPhysic",
                    }),

            new Entry("Sodium and Iris", "CaffeineMC, IMS", "-",
                    new String[]{
                            "Not used or copied. Named because the FPS Boost module",
                            "deliberately stops where they begin: they rewrite how the",
                            "world is drawn, this only skips entity work whose result",
                            "was never visible. If you want the terrain faster, install",
                            "Sodium - this module is not a substitute for it.",
                    }),

            new Entry("Minecraft", "Mojang Studios", "-",
                    new String[]{
                            "Space Client is a modification and is not affiliated with,",
                            "endorsed by, or connected to Mojang or Microsoft.",
                    }),

            new Entry("Fabric", "FabricMC", "Apache-2.0",
                    new String[]{
                            "The loader and API everything here is built on.",
                    }),

            new Entry("Menu layout", "Chill Client", "-",
                    new String[]{
                            "The shape of the opening sequence - a greeting, then the time,",
                            "then a row of round icons - was taken from screenshots of",
                            "Chill Client. The code behind it is entirely this client's.",
                    }),
    };

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    private int listBottom() { return this.height - 52; }

    private int maxScroll() {
        return Math.max(0, contentBottom - listBottom() + 8);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - scrollY * 18));
        return true;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(new FlatButton(
                panelLeft(), this.height - 46, PANEL_W, 24,
                () -> "Back",
                () -> false,
                this::onClose
        ).asAction());
    }

    private boolean visible(int y) {
        return y > 70 && y < listBottom();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Backdrop.draw(graphics, this.width, this.height);

        int left = panelLeft();
        graphics.fill(left - 18, 20, left + PANEL_W + 18, this.height - 20, Theme.PANEL);
        graphics.fill(left - 18, 20, left + PANEL_W + 18, 21, Theme.BORDER);

        JupiterIcon.draw(graphics, left, 34, 24);
        graphics.text(this.font, "CREDITS", left + 32, 36, Theme.CYAN, false);
        graphics.text(this.font, "Whose work this is built on",
                left + 32, 36 + this.font.lineHeight + 2, Theme.TEXT_DIM, false);

        int y = 88 - (int) scroll;

        for (Entry entry : ENTRIES) {
            if (visible(y)) {
                graphics.text(this.font, entry.name(), left, y, Theme.TEXT, false);

                String by = "by " + entry.by();
                graphics.text(this.font, by,
                        left + this.font.width(entry.name()) + 8, y, Theme.TEXT_DIM, false);
            }
            y += this.font.lineHeight + 2;

            if (!entry.licence().equals("-") && visible(y)) {
                graphics.text(this.font, entry.licence(), left, y, Theme.CYAN, false);
            }
            if (!entry.licence().equals("-")) y += this.font.lineHeight + 2;

            for (String line : entry.lines()) {
                if (visible(y) && !line.isEmpty()) {
                    graphics.text(this.font, line, left, y, Theme.TEXT_DIM, false);
                }
                y += this.font.lineHeight + 1;
            }

            y += 12;
        }

        contentBottom = y + (int) scroll;

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        gg.spaceclient.util.Screens.open(parent);
    }
}
