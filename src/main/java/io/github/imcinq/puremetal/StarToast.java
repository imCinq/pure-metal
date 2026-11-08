package io.github.imcinq.puremetal;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A small, unobtrusive toast that uses Minecraft's toast queue and animation. */
public final class StarToast implements Toast {
    private static final int INSET = 2;
    private static final float TEXT_SCALE = 0.75f;
    private final int width;
    private final List<FormattedCharSequence> messageLines;
    private final List<FormattedCharSequence> noteLines;
    private final int height;
    private Visibility visibility = Visibility.SHOW;

    public StarToast() {
        Font font = Minecraft.getInstance().font;
        Component note = Component.literal("You won’t see this message ever again.").withStyle(ChatFormatting.ITALIC);
        width = Math.max(150, (int) Math.ceil(font.width(note) * TEXT_SCALE) + 20 + INSET);
        messageLines = font.split(Component.literal("Enjoying Pure Metal? Give us a star on GitHub! :D"), 180);
        noteLines = font.split(note, Math.max(180, font.width(note) + 2));
        height = INSET + Math.max(32, 11 + (int) Math.ceil((messageLines.size() + noteLines.size() + 1) * font.lineHeight * TEXT_SCALE));
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public Visibility getWantedVisibility() { return visibility; }

    @Override public void update(ToastManager manager, long elapsed) {
        visibility = elapsed < 9000 * manager.getNotificationDisplayTimeMultiplier() ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long elapsed) {
        int right = width - INSET;
        graphics.fill(1, INSET, right, height, 0xFF5B626A);
        graphics.fill(2, INSET + 1, right - 1, height - 1, 0xF024292F);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, INSET);
        graphics.pose().scale(TEXT_SCALE, TEXT_SCALE);
        int x = 10, y = 6;
        Component heading = Component.literal("★ ").withStyle(s -> s.withColor(0xE8C676))
            .append(Component.literal("Pure Metal").withStyle(ChatFormatting.BOLD));
        graphics.text(font, heading, x, y, 0xFFF7F8F9, false);
        y += font.lineHeight + 1;
        for (FormattedCharSequence line : messageLines) {
            graphics.text(font, line, x, y, 0xFFF1F2F3, false);
            y += font.lineHeight;
        }
        y += 1;
        for (FormattedCharSequence line : noteLines) {
            graphics.text(font, line, x, y, 0xFFABB4BD, false);
            y += font.lineHeight;
        }
        graphics.pose().popMatrix();
    }
}
