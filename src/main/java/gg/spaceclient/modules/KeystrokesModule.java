package gg.spaceclient.modules;

import gg.spaceclient.input.RawKeyboard;
import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.ModeSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows which keys are being pressed.
 *
 *  - KEYBINDS: the movement block plus mouse buttons and space
 *  - FULL:     a real keyboard, laid out the way it sits under your hands
 *  - CUSTOM:   the keys you list, arranged in that same keyboard layout
 *
 * Pressed state is read from the physical keyboard, so pressing F lights up F
 * regardless of what F happens to be bound to. Only the mouse buttons go
 * through the game's bindings, since those follow whatever the player set them
 * to. If the raw read is unavailable the module falls back to bindings, which
 * still covers the movement keys.
 */
public class KeystrokesModule extends HudModule {
    private static final int UNIT = 20;
    private static final int GAP = 2;

    private final ModeSetting layout = new ModeSetting(
            "layout", "Layout", "Which keys to display",
            Arrays.asList("KEYBINDS", "FULL", "CUSTOM"), "KEYBINDS");

    private final BooleanSetting showMouse = new BooleanSetting(
            "show_mouse", "Show mouse buttons", "Include LMB and RMB", true);

    private final BooleanSetting showCps = new BooleanSetting(
            "show_cps", "Show CPS on buttons", "Draw the click rate inside the mouse keys", false);

    private final ColorSetting pressedColor = new ColorSetting(
            "pressed_color", "Pressed colour", "Colour of a key while held", 0xFFFFFFFF);

    private final ColorSetting idleColor = new ColorSetting(
            "idle_color", "Idle colour", "Colour of a key at rest", 0x80404040);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the key labels", 0xFFFFFFFF);

    /** Comma separated key labels for CUSTOM mode. */
    private String customKeys = "W,A,S,D,SPACE,SHIFT";

    public KeystrokesModule() {
        super("keystrokes", "Keystrokes", "Visualise your inputs in real time", 0.02f, 0.55f, true);
        addSettings(layout, showMouse, showCps, pressedColor, idleColor, textColor);
        // Each key draws its own plate, so a module-wide one would double up.
        setBackgroundEnabled(false);
    }

    public String getCustomKeys() { return customKeys; }
    public void setCustomKeys(String keys) { this.customKeys = keys; }

    /**
     * A physical keyboard, row by row. Width is in tenths of a key so wide keys
     * like TAB and SHIFT keep their real proportions.
     */
    private static final String[][] KEYBOARD_ROWS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9"},
            {"TAB", "Q", "W", "E", "R", "T", "Z", "U", "I"},
            {"CAPS", "A", "S", "D", "F", "G", "H", "J"},
            {"SHIFT", "Y", "X", "C", "V", "B", "N"},
            {"CTRL", "SPACE"},
    };

    /** Width in tenths of a unit for the keys that are not square. */
    private static int widthOf(String label) {
        return switch (label) {
            case "TAB" -> 15;
            case "CAPS" -> 18;
            case "SHIFT" -> 22;
            case "CTRL" -> 18;
            case "SPACE" -> 50;
            default -> 10;
        };
    }

    /** Left offset in tenths, so each row steps in like a real keyboard. */
    private static int rowIndent(int row) {
        return switch (row) {
            case 0 -> 8;
            case 1 -> 0;
            case 2 -> 3;
            case 3 -> 0;
            case 4 -> 0;
            default -> 0;
        };
    }

    /** Fallback mapping, used only when the physical keyboard cannot be read. */
    private Map<String, KeyMapping> bindings() {
        Map<String, KeyMapping> map = new LinkedHashMap<>();
        if (mc.options == null) return map;

        map.put("W", mc.options.keyUp);
        map.put("A", mc.options.keyLeft);
        map.put("S", mc.options.keyDown);
        map.put("D", mc.options.keyRight);
        map.put("SPACE", mc.options.keyJump);
        map.put("SHIFT", mc.options.keyShift);
        map.put("CTRL", mc.options.keySprint);
        map.put("LMB", mc.options.keyAttack);
        map.put("RMB", mc.options.keyUse);
        map.put("Q", mc.options.keyDrop);
        map.put("E", mc.options.keyInventory);
        map.put("F", mc.options.keySwapOffhand);
        map.put("T", mc.options.keyChat);
        map.put("TAB", mc.options.keyPlayerList);

        // Number row maps onto the hotbar slots
        KeyMapping[] hotbar = mc.options.keyHotbarSlots;
        if (hotbar != null) {
            for (int i = 0; i < hotbar.length && i < 9; i++) {
                map.put(String.valueOf(i + 1), hotbar[i]);
            }
        }
        return map;
    }

    private boolean isPressed(String label) {
        // Mouse buttons stay on the bindings: those really are controls, and
        // the player may have swapped attack and use.
        if (label.equals("LMB") || label.equals("RMB")) {
            KeyMapping mapping = bindings().get(label);
            return mapping != null && mapping.isDown();
        }

        if (RawKeyboard.isAvailable()) {
            int code = RawKeyboard.codeFor(label);
            if (code != org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN) {
                return RawKeyboard.isDown(code);
            }
        }

        // Fallback while the raw read is unavailable
        KeyMapping mapping = bindings().get(label);
        return mapping != null && mapping.isDown();
    }

    /** label, row, left offset in tenths, width in tenths. */
    private record KeyCell(String label, int row, int offset, int width) {}

    private List<KeyCell> buildCells() {
        List<KeyCell> cells = new ArrayList<>();

        if (layout.is("FULL") || layout.is("CUSTOM")) {
            // CUSTOM keeps the keyboard shape and simply leaves out the keys
            // that are not listed, so the layout stays recognisable.
            List<String> wanted = null;
            if (layout.is("CUSTOM")) {
                wanted = new ArrayList<>();
                for (String raw : customKeys.split(",")) {
                    String label = raw.trim().toUpperCase();
                    if (!label.isEmpty()) wanted.add(label);
                }
            }

            for (int row = 0; row < KEYBOARD_ROWS.length; row++) {
                int offset = rowIndent(row);
                for (String label : KEYBOARD_ROWS[row]) {
                    int width = widthOf(label);
                    if (wanted == null || wanted.contains(label)) {
                        cells.add(new KeyCell(label, row, offset, width));
                    }
                    offset += width + 2;
                }
            }

            // Mouse buttons sit under the keyboard when asked for
            if (showMouse.get() && (wanted == null || wanted.contains("LMB") || wanted.contains("RMB"))) {
                int row = KEYBOARD_ROWS.length;
                if (wanted == null || wanted.contains("LMB")) {
                    cells.add(new KeyCell("LMB", row, 0, 22));
                }
                if (wanted == null || wanted.contains("RMB")) {
                    cells.add(new KeyCell("RMB", row, 24, 22));
                }
            }
            return cells;
        }

        // KEYBINDS: the compact movement block
        cells.add(new KeyCell("W", 0, 12, 10));
        cells.add(new KeyCell("A", 1, 0, 10));
        cells.add(new KeyCell("S", 1, 12, 10));
        cells.add(new KeyCell("D", 1, 24, 10));

        int row = 2;
        if (showMouse.get()) {
            cells.add(new KeyCell("LMB", row, 0, 16));
            cells.add(new KeyCell("RMB", row, 18, 16));
            row++;
        }
        cells.add(new KeyCell("SPACE", row, 0, 34));
        return cells;
    }

    /** Converts tenths of a unit into pixels. */
    private int px(int tenths) {
        return tenths * (UNIT + GAP) / 10;
    }

    @Override
    public int getWidth() {
        int max = 0;
        for (KeyCell cell : buildCells()) {
            max = Math.max(max, px(cell.offset() + cell.width()));
        }
        return max;
    }

    @Override
    public int getHeight() {
        int rows = 0;
        for (KeyCell cell : buildCells()) rows = Math.max(rows, cell.row());
        return (rows + 1) * (UNIT + GAP) - GAP;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        CpsModule cps = showCps.get() ? CpsModule.getInstance() : null;

        for (KeyCell cell : buildCells()) {
            int cellX = x + px(cell.offset());
            int cellY = y + cell.row() * (UNIT + GAP);
            int cellW = px(cell.width()) - GAP;

            boolean pressed = isPressed(cell.label());
            graphics.fill(cellX, cellY, cellX + cellW, cellY + UNIT,
                    pressed ? pressedColor.get() : idleColor.get());

            String label = cell.label();
            if (cps != null && label.equals("LMB")) label = String.valueOf(cps.getLeftCps());
            if (cps != null && label.equals("RMB")) label = String.valueOf(cps.getRightCps());
            if (label.equals("SPACE") && cellW > 60) label = "______";

            // The label inverts on press so it stays readable on the bright fill
            int color = pressed ? 0xFF202020 : textColor.get();

            int textX = cellX + (cellW - mc.font.width(label)) / 2;
            int textY = cellY + (UNIT - mc.font.lineHeight) / 2;
            graphics.text(mc.font, label, textX, textY, color, false);
        }
    }
}
