package io.github.imcinq.puremetal.scale;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import io.github.imcinq.puremetal.backend.MetalUpscaler;
import io.github.imcinq.puremetal.bench.Bench;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;

/**
 * Auto Render Scale: steps the render scale between a few fixed values so the GPU keeps up with the FPS target (Max FPS, or the
 * display's refresh rate when unlimited). Every two seconds it compares the average GPU time per frame with the frame budget:
 * over budget steps down, enough headroom steps up, and at most one step changes every three seconds. It never limits FPS, and
 * when the CPU is the bottleneck the GPU time stays low, so it simply never steps down. Render thread only.
 */
public final class AutoScale {
    private static final int[] STEPS = {100, 85, 70, 60};
    private static final long WINDOW_NS = 2_000_000_000L;
    private static final long COOLDOWN_NS = 3_000_000_000L;
    private static final long REFRESH_RECHECK_NS = 1_000_000_000L;
    /** Stepping up must leave this share of the budget unused, so it does not step straight back down. */
    private static final double HEADROOM = 0.9;
    /** Vanilla treats this Max FPS and above as unlimited. */
    private static final int UNLIMITED = 260;
    /** The bench sets its own scale, and -Dpuremetal.profile's stage timing splits command buffers outside the GPU counters. */
    static final boolean BLOCKED = Bench.SECONDS > 0 || Boolean.getBoolean("puremetal.profile");
    /**
     * Frames ignored at the start of a window: GPU work submitted before it (or while Auto was off) can still complete and be read
     * during them. Each submit waits until at most four (-Dpuremetal.submitsInFlight) are in flight, so five frames drain it.
     */
    private static final int SETTLE_FRAMES = 5;

    private static int step;
    private static boolean active;
    private static int settle;
    private static long gpuNs;
    private static int frames;
    private static long windowStartNs;
    private static long lastChangeNs = System.nanoTime() - COOLDOWN_NS;
    private static int target;
    private static ScaleConfig.Upscaler upscaler;
    private static float outputScale;
    private static int width;
    private static int height;
    private static int refreshHz;
    private static long refreshCheckedNs;

    private AutoScale() {
    }

    /** Auto Render Scale was turned on or off: start again from 100%. */
    static void restart() {
        step = 0;
        active = false;
        lastChangeNs = System.nanoTime() - COOLDOWN_NS;
    }

    /** The command encoder should read the GPU time this frame. */
    public static boolean sampling() {
        return active;
    }

    /** GPU nanoseconds (frame and early-upload command buffers, summed span by span) that completed since the last read. */
    public static void addGpuNs(final long ns) {
        if (active) {
            gpuNs += ns;
        }
    }

    /** Once per frame, after the upscaler that runs this frame is known. */
    static void frame(final boolean hasLevel) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        if (BLOCKED || !ScaleConfig.autoScale() || !hasLevel || !MetalUpscaler.active() || screen != null && screen.isPauseScreen()) {
            active = false; // paused: the current step stays
            return;
        }
        long now = System.nanoTime();
        Window window = mc.getWindow();
        int fps = targetFps(mc, now);
        ScaleConfig.Upscaler kind = ScaleConfig.effectiveUpscaler();
        float output = ScaleConfig.temporalOutputScale();
        if (!active || fps != target || kind != upscaler || output != outputScale || window.getWidth() != width || window.getHeight() != height) {
            active = true;
            target = fps;
            upscaler = kind;
            outputScale = output;
            width = window.getWidth();
            height = window.getHeight();
            startWindow(now);
            return;
        }
        if (settle > 0) {
            settle--;
            gpuNs = 0L;
            windowStartNs = now;
            return;
        }
        frames++;
        if (now - windowStartNs < WINDOW_NS) {
            return;
        }
        if (gpuNs > 0L && now - lastChangeNs >= COOLDOWN_NS) {
            // Command buffer spans overlap while the GPU is saturated, so their sum can exceed the time that passed. The GPU
            // cannot be busier than the window was long: when the sum reaches it, the frame time itself is the GPU's cost.
            double gpuMs = Math.min(gpuNs, now - windowStartNs) / 1.0e6 / frames;
            double budgetMs = 1000.0 / target;
            int next = step;
            if (gpuMs > budgetMs) {
                next = Math.min(step + 1, STEPS.length - 1);
            } else if (step > 0) {
                // GPU time follows the pixel count, so it grows with the square of the size ratio.
                double ratio = fraction(STEPS[step - 1]) / fraction(STEPS[step]);
                if (gpuMs * ratio * ratio < HEADROOM * budgetMs) {
                    next = step - 1;
                }
            }
            if (next != step) {
                step = next;
                lastChangeNs = now;
                ScaleConfig.setAutoStep(STEPS[step]);
            }
        }
        startWindow(now);
    }

    private static void startWindow(final long now) {
        gpuNs = 0L;
        frames = 0;
        windowStartNs = now;
        settle = SETTLE_FRAMES;
    }

    /** The world's size as a fraction of the screen's; at 100% nothing is scaled, whatever the Temporal output size. */
    private static double fraction(final int percent) {
        return percent >= ScaleConfig.MAX_SCALE ? 1.0 : ScaleConfig.worldFraction(percent);
    }

    /** Max FPS; when unlimited, the refresh rate; with VSync, at most the refresh rate. */
    private static int targetFps(final Minecraft mc, final long now) {
        if (refreshHz == 0 || now - refreshCheckedNs > REFRESH_RECHECK_NS) {
            refreshCheckedNs = now;
            Monitor monitor = mc.getWindow().findBestMonitor();
            VideoMode mode = monitor == null ? null : monitor.currentMode();
            float hz = mode == null ? 0.0F : mode.getRefreshRate();
            refreshHz = hz > 0.0F ? Math.round(hz) : -1;
        }
        Options options = mc.options;
        int limit = options.framerateLimit().get();
        if (refreshHz <= 0) {
            return limit >= UNLIMITED ? 60 : limit;
        }
        if (limit >= UNLIMITED) {
            return refreshHz;
        }
        return options.enableVsync().get() ? Math.min(limit, refreshHz) : limit;
    }
}
