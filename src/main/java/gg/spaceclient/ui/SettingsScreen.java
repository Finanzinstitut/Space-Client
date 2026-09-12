package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.setting.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a list of settings, and a button for each sub-group.
 *
 * Used both for a module's top level and for each of its groups, so a module
 * with four categories of three options each reads as four buttons rather than
 * twelve rows.
 */
public class SettingsScreen extends Screen {
    private static final int ROW_H = 26;
    private static final int GAP = 6;
    private static final int PANEL_W = 340;

    private final Screen parent;
    private final String heading;
    private final String subheading;
    private final List<Setting> settings;
    private final List<SettingGroup> groups;

    /**
     * Which page of settings is shown.
     *
     * Paging rather than scrolling on purpose: a scroll wheel handler would
     * need the mouse event signature, which changed in this version, while
     * buttons are already known to work.
     */
    private int page = 0;
    private int pageCount = 1;

    /** The settings laid out on the current page. */
    private List<Setting> visibleSettings = List.of();

    /** Y positions of colour wheels, so their names can be drawn above them. */
    private final List<int[]> colourRows = new ArrayList<>();

    public SettingsScreen(Screen parent, String heading, String subheading,
                          List<Setting> settings, List<SettingGroup> groups) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.subheading = subheading;
        this.settings = settings;
        this.groups = groups;
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    /** How much vertical room a setting takes, so a page can be filled exactly. */
    private static int heightOf(Setting setting) {
        if (setting instanceof ColorSetting) return 84 + 22 + ROW_H + GAP;
        return ROW_H + GAP;
    }

    @Override
    protected void init() {
        colourRows.clear();
        int left = panelLeft();
        int y = 92;

        // Everything that has to fit: the group buttons, then the settings
        int room = this.height - 92 - 70;

        // Split the settings into pages that fit the window
        List<List<Setting>> pages = new ArrayList<>();
        List<Setting> currentPage = new ArrayList<>();
        int used = 0;

        for (Setting setting : settings) {
            int needed = heightOf(setting);
            if (used + needed > room && !currentPage.isEmpty()) {
                pages.add(currentPage);
                currentPage = new ArrayList<>();
                used = 0;
            }
            currentPage.add(setting);
            used += needed;
        }
        if (!currentPage.isEmpty()) pages.add(currentPage);
        if (pages.isEmpty()) pages.add(List.of());

        pageCount = pages.size();
        page = Math.max(0, Math.min(page, pageCount - 1));
        List<Setting> visible = pages.get(page);
        visibleSettings = visible;

        // Sub-groups first, and only on the first page
        for (SettingGroup group : page == 0 ? groups : List.<SettingGroup>of()) {
            this.addRenderableWidget(new FlatButton(
                    left, y, PANEL_W, ROW_H,
                    () -> group.name() + "  >",
                    () -> false,
                    () -> Minecraft.getInstance().gui.setScreen(new SettingsScreen(
                            this, group.name(), group.description(),
                            group.settings(), List.of()))
            ).asAction());
            y += ROW_H + GAP;
        }
        if (!groups.isEmpty()) y += GAP;

        for (Setting setting : visible) {
            if (setting instanceof BooleanSetting b) {
                this.addRenderableWidget(new FlatButton(
                        left, y, PANEL_W, ROW_H,
                        setting::getName, b::get,
                        () -> {
                            b.toggle();
                            SpaceClient.getConfigManager().save();
                        }
                ));
                y += ROW_H + GAP;

            } else if (setting instanceof ModeSetting m) {
                this.addRenderableWidget(new FlatButton(
                        left, y, PANEL_W, ROW_H,
                        () -> setting.getName() + ": " + m.get(),
                        () -> false,
                        () -> {
                            m.cycle();
                            SpaceClient.getConfigManager().save();
                        }
                ));
                y += ROW_H + GAP;

            } else if (setting instanceof IntSetting i) {
                this.addRenderableWidget(new SliderRow(
                        left, y, PANEL_W, ROW_H,
                        setting.getName(), i.get(), i.getMax(),
                        value -> {
                            i.set(value);
                            SpaceClient.getConfigManager().save();
                        }
                ));
                y += ROW_H + GAP;

            } else if (setting instanceof ColorSetting c) {
                colourRows.add(new int[]{y});
                this.addRenderableWidget(new ColorWheel(
                        left, y + 14, 84, c,
                        () -> SpaceClient.getConfigManager().save()
                ));
                y += 84 + 22;

                // Opacity keeps a slider; it has no place on a hue wheel
                this.addRenderableWidget(new SliderRow(left, y, PANEL_W, ROW_H,
                        setting.getName() + " opacity", c.getAlpha(), 255, value -> {
                    c.setComponents(value, c.getRed(), c.getGreen(), c.getBlue());
                    SpaceClient.getConfigManager().save();
                }));
                y += ROW_H + GAP;
            }
        }

        int bottom = this.height - 34;

        if (pageCount > 1) {
            int third = (PANEL_W - GAP * 2) / 3;

            this.addRenderableWidget(new FlatButton(
                    left, bottom, third, ROW_H,
                    () -> "< Page",
                    () -> false,
                    () -> {
                        if (page > 0) { page--; this.rebuildWidgets(); }
                    }
            ).asAction());

            this.addRenderableWidget(new FlatButton(
                    left + third + GAP, bottom, third, ROW_H,
                    () -> "Back",
                    () -> false,
                    this::onClose
            ).asAction());

            this.addRenderableWidget(new FlatButton(
                    left + (third + GAP) * 2, bottom, third, ROW_H,
                    () -> "Page >",
                    () -> false,
                    () -> {
                        if (page < pageCount - 1) { page++; this.rebuildWidgets(); }
                    }
            ).asAction());
        } else {
            this.addRenderableWidget(new FlatButton(
                    left, bottom, PANEL_W, ROW_H,
                    () -> "Back",
                    () -> false,
                    this::onClose
            ).asAction());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Backdrop.draw(graphics, this.width, this.height);

        int left = panelLeft();
        graphics.fill(left - 18, 20, left + PANEL_W + 18, this.height - 20, Theme.PANEL);
        graphics.fill(left - 18, 20, left + PANEL_W + 18, 21, Theme.BORDER);

        JupiterIcon.draw(graphics, left, 34, 24);
        graphics.text(this.font, heading.toUpperCase(), left + 34, 38, Theme.CYAN, false);
        graphics.text(this.font, subheading, left + 34, 50, Theme.TEXT_DIM, false);
        graphics.fill(left, 74, left + PANEL_W, 75, Theme.BORDER);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Hitboxes silently lose their per-category options when the world
        // render event is missing, so say that here rather than let the
        // settings look broken.
        if (heading.equalsIgnoreCase("Hitbox") && !gg.spaceclient.render.HitboxRenderer.isAvailable()) {
            graphics.text(this.font,
                    "Custom drawing unavailable - using the game's own hitbox view.",
                    left, this.height - 46, 0xFFFF9AAE, false);
            graphics.text(this.font,
                    "Colours, widths and arrows have no effect in that mode.",
                    left, this.height - 34, 0xFF9A95C9, false);
        }

        // Names above the colour wheels, which draw no label of their own.
        // Only the ones on this page were laid out, so the labels follow that.
        int index = 0;
        for (Setting setting : visibleSettings) {
            if (!(setting instanceof ColorSetting)) continue;
            if (index >= colourRows.size()) break;
            graphics.text(this.font, setting.getName(),
                    left, colourRows.get(index)[0], Theme.TEXT, false);
            index++;
        }

        if (pageCount > 1) {
            String label = "Page " + (page + 1) + " of " + pageCount;
            graphics.text(this.font, label,
                    left + PANEL_W - this.font.width(label), 60, Theme.TEXT_DIM, false);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
