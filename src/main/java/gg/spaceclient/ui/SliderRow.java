package gg.spaceclient.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A flat slider for the colour channels. Extending AbstractSliderButton means
 * dragging is handled by the widget itself.
 */
public class SliderRow extends AbstractSliderButton {
    private final String name;
    private final Consumer<Integer> onChange;
    private final int max;

    public SliderRow(int x, int y, int width, int height,
                     String name, int initial, int max, Consumer<Integer> onChange) {
        super(x, y, width, height, Component.empty(), initial / (double) max);
        this.name = name;
        this.max = max;
        this.onChange = onChange;
    }

    public int value() {
        return (int) Math.round(this.value * max);
    }

    @Override
    protected void updateMessage() {
        // The label is drawn manually below.
    }

    @Override
    protected void applyValue() {
        onChange.accept(value());
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + this.width;
        int y2 = y1 + this.height;

        graphics.fill(x1, y1, x2, y2, 0x30FFFFFF);

        // Filled portion up to the handle
        int filled = x1 + (int) (this.value * this.width);
        graphics.fill(x1, y1, filled, y2, Theme.accentDim());
        graphics.fill(filled - 2, y1, filled + 1, y2, Theme.CYAN);

        int border = isHovered() ? Theme.accent() : Theme.BORDER;
        graphics.fill(x1, y1, x2, y1 + 1, border);
        graphics.fill(x1, y2 - 1, x2, y2, border);
        graphics.fill(x1, y1, x1 + 1, y2, border);
        graphics.fill(x2 - 1, y1, x2, y2, border);

        var font = net.minecraft.client.Minecraft.getInstance().font;
        int textY = y1 + (this.height - font.lineHeight) / 2;
        graphics.text(font, name, x1 + 10, textY, Theme.TEXT, false);

        String valueText = String.valueOf(value());
        graphics.text(font, valueText, x2 - font.width(valueText) - 10, textY, Theme.TEXT_DIM, false);
    }

    /** Mouse only, for the same reason as the buttons. */
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(false);
    }

    @Override
    public boolean isFocused() {
        return false;
    }

    /** Out of tab and arrow key navigation, for the same reason. */
    public net.minecraft.client.gui.ComponentPath nextFocusPath(
            net.minecraft.client.gui.navigation.FocusNavigationEvent event) {
        return null;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput builder) {
        // Narration is not implemented for this widget.
    }
}
