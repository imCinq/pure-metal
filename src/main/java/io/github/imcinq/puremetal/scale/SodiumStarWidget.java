package io.github.imcinq.puremetal.scale;

import java.net.URI;
import java.util.List;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;
import net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget;
import net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget;
import net.caffeinemc.mods.sodium.client.gui.widgets.ScrollbarWidget;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Compact link at the end of Pure Metal's Sodium settings. */
public final class SodiumStarWidget extends AbstractWidget {
    private final OptionListWidget list;
    private final Screen screen;
    private final CloseButton close;
    private final List<FormattedCharSequence> description;
    private final int accent;

    private SodiumStarWidget(OptionListWidget list, Screen screen, Dim2i dim, List<FormattedCharSequence> description, int accent) {
        super(dim);
        this.list = list;
        this.screen = screen;
        this.close = new CloseButton(new Dim2i(dim.x() + dim.width() - 18, dim.y() + 1, 16, 16));
        this.description = description;
        this.accent = accent | 0xFF000000;
    }

    public static SodiumStarWidget create(OptionListWidget list, Screen screen, int x, int y, int width, ColorTheme theme) {
        var font = Minecraft.getInstance().font;
        var lines = font.split(Component.literal("A star on GitHub would mean a lot. Thank you! :D"), Math.max(20, width - 30));
        return new SodiumStarWidget(list, screen, new Dim2i(x, y, width, 12 + (lines.size() + 2) * font.lineHeight), lines, theme.themeLighter);
    }

    public AbstractWidget closeButton() { return close; }

    @Override public int getY() { return super.getY() - list.getScrollAmount(); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        g.fill(x + 3, y, x + w, y + h, 0x70000000);
        if (isFocused()) {
            g.fill(x + 3, y, x + w, y + 1, accent);
            g.fill(x + 3, y + h - 1, x + w, y + h, accent);
            g.fill(x + 3, y, x + 4, y + h, accent);
            g.fill(x + w - 1, y, x + w, y + h, accent);
        }
        Component heading = Component.literal("★ Enjoying Pure Metal?").withStyle(net.minecraft.ChatFormatting.BOLD);
        int headingAlpha = Math.round(255 * (1f - close.expansion()));
        if (headingAlpha > 3) {
            g.enableScissor(x + 3, y, close.getX() - 2, y + font.lineHeight + 6);
            g.text(font, heading, x + (w - 18 - font.width(heading)) / 2, y + 4, (headingAlpha << 24) | 0xFFFFFF);
            g.disableScissor();
        }
        boolean closeActive = close.isFocused() || (list.isMouseOver(mouseX, mouseY) && close.isMouseOver(mouseX, mouseY));
        String closeLabel = close.label();
        if (close.getWidth() >= font.width(closeLabel) + 6) {
            int color = closeLabel.equals("×") ? (closeActive ? 0xFFFFFFFF : 0xFFAAAAAA) : (closeActive ? 0xFFFFFFFF : accent);
            g.text(font, Component.literal(closeLabel), close.getX() + (close.getWidth() - font.width(closeLabel)) / 2, close.getY() + 3, color);
        }
        if (close.isFocused()) {
            g.fill(close.getX(), close.getY(), close.getX() + close.getWidth(), close.getY() + 1, accent);
            g.fill(close.getX(), close.getY() + close.getHeight() - 1, close.getX() + close.getWidth(), close.getY() + close.getHeight(), accent);
        }
        int lineY = y + 4 + font.lineHeight;
        for (FormattedCharSequence line : description) {
            g.text(font, line, x + (w - font.width(line)) / 2, lineY, 0xFFCCCCCC);
            lineY += font.lineHeight;
        }
        Component action = Component.literal("Star on GitHub ↗");
        g.text(font, action, x + (w - font.width(action)) / 2, lineY + 2, isFocused() || isMouseOver(mouseX, mouseY) ? 0xFFFFFFFF : accent);
    }

    private void open() {
        var client = Minecraft.getInstance();
        if (client.gui.screen() != null) ConfirmLinkScreen.confirmLinkNow(client.gui.screen(), URI.create("https://github.com/imCinq/Pure-Metal"));
    }

    private final class CloseButton extends AbstractWidget {
        private static final long TIMEOUT_NS = 5_000_000_000L;
        private static final long ANIMATION_NS = 160_000_000L;
        private long deadline, animationStart;
        private float animationFrom = 16;
        private int targetWidth = 16;
        private String label = "×";

        CloseButton(Dim2i dim) { super(dim); }
        @Override public int getY() { return super.getY() - list.getScrollAmount(); }
        @Override public int getX() { return super.getX() + 16 - getWidth(); }
        @Override public int getWidth() {
            updateState();
            float progress = Math.min(1f, (System.nanoTime() - animationStart) / (float) ANIMATION_NS);
            return Math.round(animationFrom + (targetWidth - animationFrom) * (1f - (1f - progress) * (1f - progress)));
        }
        String label() { updateState(); return label; }

        float expansion() {
            int expandedWidth = Math.max(17, font.width("Are you sure?") + 12);
            return Math.min(1f, Math.max(0f, (getWidth() - 16f) / (expandedWidth - 16f)));
        }

        private void animateTo(String text, long until) {
            int currentWidth = getWidth();
            label = text;
            deadline = until;
            animationFrom = currentWidth;
            targetWidth = text.equals("×") ? 16 : font.width(text) + 12;
            animationStart = System.nanoTime();
        }

        private void updateState() {
            if (deadline != 0 && System.nanoTime() >= deadline) {
                deadline = 0;
                animationFrom = targetWidth;
                targetWidth = 16;
                animationStart = System.nanoTime();
                label = "×";
            }
        }

        private void activate() {
            updateState();
            if (!label.equals("Are you sure?")) {
                animateTo("Are you sure?", System.nanoTime() + TIMEOUT_NS);
                return;
            }
            if (!GitHubCardDismissal.hide()) {
                animateTo("Couldn’t save", System.nanoTime() + TIMEOUT_NS);
                return;
            }
            int scroll = list.getScrollAmount();
            list.rebuild(screen);
            list.setFocused(null);
            list.children().stream().filter(ScrollbarWidget.class::isInstance).map(ScrollbarWidget.class::cast)
                .findFirst().ifPresent(bar -> bar.scrollTo(scroll));
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) { }
        @Override public boolean shouldTakeFocusAfterInteraction() { return false; }
        @Override public void updateNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal(label.equals("Are you sure?") ? "Confirm permanently hiding GitHub card" : "Hide GitHub card"));
        }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT || !list.isMouseOver(event.x(), event.y()) || !isMouseOver(event.x(), event.y())) return false;
            playClickSound();
            activate();
            return true;
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if (isFocused() && event.isSelection()) { activate(); return true; }
            return false;
        }
    }

    @Override public void updateNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal("Star Pure Metal on GitHub"));
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT || !list.isMouseOver(event.x(), event.y()) || !isMouseOver(event.x(), event.y())) return false;
        playClickSound();
        open();
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (isFocused() && event.isSelection()) { open(); return true; }
        return false;
    }
}
