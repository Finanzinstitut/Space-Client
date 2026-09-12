package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.module.HudModule;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves, resizes and locks HUD elements.
 *
 * Click an element to pick it up, move the mouse, click again to put it down.
 * Holding the button and dragging would need the mouse event API, whose
 * signatures changed in this version; picking up and putting down needs only a
 * click and the cursor position, both already available.
 *
 * Resizing used to be a corner grip and nothing else, and it never worked. The
 * body handle covered the whole element including the corner and was added to
 * the screen first, and a Screen hands a click to the first child that accepts
 * it - so the grip sat underneath, unreachable, and the corner was not merely
 * hard to hit but impossible. There are now three ways to resize, and the one
 * that needs no aim at all is the default.
 */
public class HudEditorScreen extends Screen {

    /** Fourteen, not eight. A grip you have to aim at is a grip nobody uses. */
    private static final int GRIP = 14;

    /** How close an edge has to be before it snaps, in pixels. */
    private static final int SNAP = 5;

    private final Screen parent;

    /** What a click on an element body does. */
    private enum Mode { MOVE, RESIZE, LOCK }

    private Mode mode = Mode.MOVE;

    private HudModule grabbed;
    private HudModule resizing;

    private int grabOffsetX;
    private int grabOffsetY;

    private final List<GrabHandle> handles = new ArrayList<>();
    private final Map<HudModule, Float> glow = new HashMap<>();
    private final Map<HudModule, float[]> shown = new HashMap<>();

    /** Guides drawn this frame, as screen coordinates. */
    private final List<Integer> guidesX = new ArrayList<>();
    private final List<Integer> guidesY = new ArrayList<>();

    public HudEditorScreen(Screen parent) {
        super(Component.literal("HUD Editor"));
        this.parent = parent;
    }

    private class GrabHandle extends net.minecraft.client.gui.components.Button {
        private final HudModule module;
        private final boolean grip;

        private int lastHoverX;
        private int lastHoverY;

        GrabHandle(HudModule module, boolean grip, int width, int height, Runnable onClick) {
            super(0, 0, width, height, Component.empty(),
                    btn -> onClick.run(), DEFAULT_NARRATION);
            this.module = module;
            this.grip = grip;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        }
    }

    private void startMove(HudModule module, GrabHandle handle) {
        if (module.isLocked()) return;
        grabbed = module;
        grabOffsetX = handle.lastHoverX - module.getX(this.width);
        grabOffsetY = handle.lastHoverY - module.getY(this.height);
    }

    private void drop() {
        boolean wasResizing = resizing != null;
        grabbed = null;
        resizing = null;
        SpaceClient.getConfigManager().save();

        // A resized element has a new footprint, and the handles were built
        // against the old one. Rebuilding is cheaper than resizing widgets in
        // place, and setWidth is not proven on this version.
        if (wasResizing) this.rebuildWidgets();
    }

    private boolean busy() { return grabbed != null || resizing != null; }

    @Override
    protected void init() {
        handles.clear();

        int y = 24;
        this.addRenderableWidget(new NavButton(
                24, y, 90, 20, NavButton.Style.CHIP,
                () -> "Move",
                () -> mode == Mode.MOVE,
                () -> { mode = Mode.MOVE; drop(); }));

        this.addRenderableWidget(new NavButton(
                118, y, 90, 20, NavButton.Style.CHIP,
                () -> "Resize",
                () -> mode == Mode.RESIZE,
                () -> { mode = Mode.RESIZE; drop(); }));

        this.addRenderableWidget(new NavButton(
                212, y, 90, 20, NavButton.Style.CHIP,
                () -> "Lock",
                () -> mode == Mode.LOCK,
                () -> { mode = Mode.LOCK; drop(); }));

        // The way back out of a mistake. Someone who scales an element to three
        // times the screen cannot easily grab it again to undo that.
        this.addRenderableWidget(new FlatButton(
                this.width - 130, 24, 106, 20,
                () -> "Reset sizes", () -> false,
                () -> {
                    for (HudModule module : SpaceClient.getModuleManager().getHudModules()) {
                        module.setScale(1f);
                    }
                    SpaceClient.getConfigManager().save();
                }).asAction());

        for (HudModule module : SpaceClient.getModuleManager().getHudModules()) {
            if (!module.isEnabled()) continue;

            GrabHandle[] body = new GrabHandle[1];

            GrabHandle grip = new GrabHandle(module, true, GRIP, GRIP,
                    () -> {
                        if (busy()) drop();
                        else if (!module.isLocked()) resizing = module;
                    });

            body[0] = new GrabHandle(module, false,
                    Math.max(24, module.getScaledWidth()),
                    Math.max(12, module.getScaledHeight()),
                    () -> {
                        if (busy()) { drop(); return; }
                        switch (mode) {
                            case RESIZE -> { if (!module.isLocked()) resizing = module; }
                            case LOCK -> {
                                module.setLocked(!module.isLocked());
                                SpaceClient.getConfigManager().save();
                            }
                            default -> startMove(module, body[0]);
                        }
                    });

            // The grip goes in first, and that ordering is the whole fix: a
            // Screen gives the click to the first child that accepts it, so
            // whichever is registered first owns the overlap.
            handles.add(grip);
            handles.add(body[0]);
            this.addRenderableWidget(grip);
            this.addRenderableWidget(body[0]);
        }
    }

    // ---------------- resizing by wheel ----------------

    private boolean scrollResize(double mouseX, double mouseY, double amount) {
        HudModule under = resizing;
        if (under == null) under = elementAt((int) mouseX, (int) mouseY);
        if (under == null || under.isLocked()) return false;

        under.setScale(under.getScale() + (float) amount * 0.1f);
        SpaceClient.getConfigManager().save();
        return true;
    }

    private HudModule elementAt(int mouseX, int mouseY) {
        for (GrabHandle handle : handles) {
            if (handle.grip) continue;
            HudModule module = handle.module;
            int x = module.getX(this.width);
            int y = module.getY(this.height);
            if (mouseX >= x && mouseX <= x + Math.max(24, module.getScaledWidth())
                    && mouseY >= y && mouseY <= y + Math.max(12, module.getScaledHeight())) {
                return module;
            }
        }
        return null;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return scrollResize(mouseX, mouseY, scrollY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return scrollResize(mouseX, mouseY, amount);
    }

    // ---------------- snapping ----------------

    /**
     * Pulls a moving edge onto a nearby line.
     *
     * Lining elements up by eye is the slowest part of arranging a HUD, and
     * two pixels out is visible once the game is running but invisible while
     * placing it. The candidates are the screen's edges and centre, and the
     * edges of every other element - the same lines a person would have been
     * aiming for anyway.
     */
    private int snap(int value, int size, int screenSize, boolean horizontal, HudModule moving) {
        List<Integer> lines = new ArrayList<>();
        lines.add(0);
        lines.add(screenSize / 2 - size / 2);
        lines.add(screenSize - size);

        for (HudModule other : SpaceClient.getModuleManager().getHudModules()) {
            if (other == moving || !other.isEnabled()) continue;
            int start = horizontal ? other.getX(this.width) : other.getY(this.height);
            int extent = horizontal ? other.getScaledWidth() : other.getScaledHeight();
            lines.add(start);                    // edges flush
            lines.add(start + extent - size);    // far edges flush
        }

        for (int line : lines) {
            if (Math.abs(value - line) <= SNAP) {
                if (horizontal) guidesX.add(line <= value ? line : line + size);
                else guidesY.add(line <= value ? line : line + size);
                return line;
            }
        }
        return value;
    }

    // ---------------- paint ----------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0x9002010A);

        guidesX.clear();
        guidesY.clear();

        if (grabbed != null) {
            int wantX = mouseX - grabOffsetX;
            int wantY = mouseY - grabOffsetY;

            int snappedX = snap(wantX, grabbed.getScaledWidth(), this.width, true, grabbed);
            int snappedY = snap(wantY, grabbed.getScaledHeight(), this.height, false, grabbed);

            grabbed.setPosition(snappedX / (float) this.width, snappedY / (float) this.height);
        }

        if (resizing != null) {
            int originX = resizing.getX(this.width);
            int contentWidth = Math.max(1, resizing.getWidth());
            resizing.setScale((mouseX - originX) / (float) contentWidth);
        }

        for (GrabHandle handle : handles) {
            if (handle.grip) continue;

            HudModule module = handle.module;
            int x = module.getX(this.width);
            int y = module.getY(this.height);
            int w = Math.max(24, module.getScaledWidth());
            int h = Math.max(12, module.getScaledHeight());

            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
            boolean active = grabbed == module || resizing == module;

            float[] at = shown.computeIfAbsent(module, key -> new float[]{ x, y });
            if (active) {
                at[0] = Ease.approach(at[0], x, 0.5f, delta);
                at[1] = Ease.approach(at[1], y, 0.5f, delta);
            } else {
                at[0] = x;
                at[1] = y;
            }
            int drawX = Math.round(at[0]);
            int drawY = Math.round(at[1]);

            handle.setX(x);
            handle.setY(y);
            handle.lastHoverX = mouseX;
            handle.lastHoverY = mouseY;

            module.draw(graphics, drawX, drawY);

            float target = active ? 1f : (hovered ? 0.6f : 0.18f);
            float lit = Ease.approach(glow.getOrDefault(module, 0f), target, 0.35f, delta);
            glow.put(module, lit);

            int base = module.isLocked() ? Theme.OFF : Theme.accent();
            int color = Ease.color(Theme.BORDER, active ? Theme.CYAN : base, lit);
            color = (Math.round(255 * Math.min(1f, 0.3f + lit)) << 24) | (color & 0xFFFFFF);

            graphics.fill(drawX - 2, drawY - 2, drawX + w + 2, drawY - 1, color);
            graphics.fill(drawX - 2, drawY + h + 1, drawX + w + 2, drawY + h + 2, color);
            graphics.fill(drawX - 2, drawY - 2, drawX - 1, drawY + h + 2, color);
            graphics.fill(drawX + w + 1, drawY - 2, drawX + w + 2, drawY + h + 2, color);

            // The grip is drawn always, not only on hover. A control that
            // appears when you are already over it cannot be aimed at.
            if (!module.isLocked()) {
                int gripX = drawX + w + 2 - GRIP;
                int gripY = drawY + h + 2 - GRIP;
                graphics.fill(gripX, gripY, drawX + w + 2, drawY + h + 2, color);
                // Two notches, so it reads as a handle rather than a block
                graphics.fill(gripX + 3, gripY + GRIP - 3, drawX + w, drawY + h + 1, 0x66000000);
                graphics.fill(gripX + GRIP - 3, gripY + 3, drawX + w + 1, drawY + h, 0x66000000);
            }

            if (hovered || active) {
                String label = module.getName();
                if (resizing == module) {
                    label = label + "  " + Math.round(module.getScale() * 100) + "%";
                } else if (module.isLocked()) {
                    label = label + "  locked";
                }
                graphics.text(this.font, label, drawX, drawY - 13,
                        active ? Theme.CYAN : Theme.TEXT, false);
            }
        }

        for (GrabHandle handle : handles) {
            if (!handle.grip) continue;
            HudModule module = handle.module;
            handle.setX(module.getX(this.width) + Math.max(24, module.getScaledWidth()) + 2 - GRIP);
            handle.setY(module.getY(this.height) + Math.max(12, module.getScaledHeight()) + 2 - GRIP);
            handle.lastHoverX = mouseX;
            handle.lastHoverY = mouseY;
        }

        // Guides on top of the elements, under the chrome
        for (int line : guidesX) {
            graphics.fill(line, 0, line + 1, this.height, 0x99FF4FD8);
        }
        for (int line : guidesY) {
            graphics.fill(0, line, this.width, line + 1, 0x99FF4FD8);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        JupiterIcon.draw(graphics, 24, 52, 20);
        graphics.text(this.font, "HUD EDITOR", 52, 54, Theme.CYAN, false);
        graphics.text(this.font, hint(), 52, 66, Theme.TEXT_DIM, false);
        graphics.text(this.font, "Scroll over an element to resize it at any time",
                24, this.height - 36, Theme.OFF, false);
        graphics.text(this.font, "Esc to go back", 24, this.height - 24, Theme.TEXT_DIM, false);
    }

    private String hint() {
        if (resizing != null) return "Move the mouse to resize, click to keep it";
        if (grabbed != null) return "Move the mouse, click again to place it";
        return switch (mode) {
            case RESIZE -> "Click any element to resize it";
            case LOCK -> "Click an element to lock or unlock it";
            default -> "Click to pick up, or grab the corner to resize";
        };
    }

    @Override
    public void onClose() {
        if (busy()) drop();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
