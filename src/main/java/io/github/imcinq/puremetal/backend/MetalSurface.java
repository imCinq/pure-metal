package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import io.github.imcinq.puremetal.bench.PresentationProfile;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** The window's CAMetalLayer (via SDL's Metal view). Present is scheduled on the frame's command buffer. */
final class MetalSurface implements GpuSurfaceBackend {
    private final MetalDevice device;
    private final long view;
    private final long surface;
    private boolean closed;
    /** Dev aid: -Dpuremetal.capture=<frame> saves that presented frame to puremetal-capture.png in the game dir. */
    private static final int CAPTURE_FRAME = Integer.getInteger("puremetal.capture", -1);
    /** Dev aid: -Dpuremetal.noPresent=true renders frames without showing them, to isolate presentation costs. */
    private static final boolean NO_PRESENT = Boolean.getBoolean("puremetal.noPresent");
    private int frame;
    private static final boolean PRESENT_STATS = Debug.ENABLED || io.github.imcinq.puremetal.bench.Bench.SECONDS > 0 || PresentationProfile.ENABLED;
    private long statsStart;
    private int statsFrames;
    private int configuredWidth, configuredHeight;
    private boolean configuredVsync;
    private boolean hdrLayerEnabled;
    private long hdrCheckedNs;
    private float hdrHeadroom = 1.0F;
    private float hdrSentBoost = -1.0F;
    private float hdrMaxHeadroom = 1.0F;
    private boolean hdrLinear;
    private int hdrState;

    MetalSurface(final MetalDevice device, final long windowHandle) {
        this.device = device;
        FrameTrace.init();
        this.view = SDLMetal.SDL_Metal_CreateView(windowHandle);
        if (this.view == 0L) {
            throw new IllegalStateException("SDL could not create a Metal view: " + SDLError.SDL_GetError());
        }
        long layer = SDLMetal.SDL_Metal_GetLayer(this.view);
        this.hdrLayerEnabled = io.github.imcinq.puremetal.perf.HdrHighlights.enabled();
        this.surface = MetalNative.surfaceCreate(device.handle(), layer, this.hdrLayerEnabled ? 1 : 0);
        if (this.surface == 0L) {
            SDLMetal.SDL_Metal_DestroyView(this.view);
            throw new IllegalStateException("Pure Metal could not set up presenting (see the log)");
        }
        device.presentSurface = this.surface;
        device.liveSurface = this;
    }

    @Override
    public void configure(final GpuSurface.Configuration config) {
        boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO || config.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED;
        this.configuredWidth = config.width();
        this.configuredHeight = config.height();
        this.configuredVsync = vsync;
        PresentationProfile.Configuration event = PresentationProfile.ENABLED ? new PresentationProfile.Configuration() : null;
        if (event != null) {
            event.width = config.width();
            event.height = config.height();
            event.vsync = vsync;
            event.begin();
        }
        FrameTrace.mark(FrameTrace.CFG_ENTER, config.width(), config.height(), vsync ? 1L : 0L);
        MetalNative.surfaceConfigure(this.surface, config.width(), config.height(), vsync ? 1 : 0,
            io.github.imcinq.puremetal.perf.FramePacing.allowTearing());
        FrameTrace.mark(FrameTrace.CFG_EXIT, 0);
        if (event != null) event.commit();
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
        // Frame boundary: terrain math switches are published here, before any pass of the frame is recorded.
        io.github.imcinq.puremetal.perf.TerrainShaderProof.frameBoundary();
        if (NO_PRESENT) {
            return;
        }
        if (io.github.imcinq.puremetal.perf.FramePacing.update(this.configuredVsync)) {
            MetalNative.surfaceSetPacing(this.surface, io.github.imcinq.puremetal.perf.FramePacing.refreshNs(),
                io.github.imcinq.puremetal.perf.FramePacing.pacing(), io.github.imcinq.puremetal.perf.FramePacing.allowTearing());
        }
        io.github.imcinq.puremetal.perf.HdrHighlights.beginFrame();
        boolean wantHdr = io.github.imcinq.puremetal.perf.HdrHighlights.enabled();
        if (wantHdr != this.hdrLayerEnabled) {
            this.hdrLayerEnabled = wantHdr;
            this.hdrCheckedNs = 0L;
            this.hdrSentBoost = -1.0F;
            this.hdrHeadroom = 1.0F;
            this.hdrMaxHeadroom = 1.0F;
            this.hdrLinear = false;
            this.hdrState = 0;
            MetalNative.surfaceSetHdr(this.surface, wantHdr ? 1 : 0);
            if (!wantHdr) {
                io.github.imcinq.puremetal.perf.HdrHighlights.publish(1.0F, 1.0F, 1.0F, false, false);
            }
        }
        if (this.hdrLayerEnabled) {
            this.pollHdrState();
        }
        if (PRESENT_STATS) {
            this.logPresentStats();
        }
        if (io.github.imcinq.puremetal.bench.Bench.SECONDS > 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.LongBuffer gpu = stack.mallocLong(2);
                MetalNative.surfaceTakePresentGpu(this.surface, MemoryUtil.memAddress(gpu));
                io.github.imcinq.puremetal.bench.Bench.presentGpuNs += gpu.get(0) * 1000L;
                io.github.imcinq.puremetal.bench.Bench.presentGpuCount += gpu.get(1);
            }
        }
        FrameTrace.mark(FrameTrace.FRAME, 0);
    }

    /** HDR highlights only: reads the watcher's latest headroom/colorspace snapshot once a second (or each frame while settling). */
    private void pollHdrState() {
        long now = System.nanoTime();
        if (this.hdrCheckedNs == 0L || this.hdrState == 0 || now - this.hdrCheckedNs > io.github.imcinq.puremetal.perf.HdrHighlights.HEADROOM_RECHECK_NS) {
            this.hdrCheckedNs = now;
            this.hdrHeadroom = MetalNative.surfaceEdrHeadroom(this.surface);
            this.hdrMaxHeadroom = MetalNative.surfaceEdrMax(this.surface);
            this.hdrLinear = MetalNative.surfaceHdrLinear(this.surface);
            this.hdrState = MetalNative.surfaceHdrState(this.surface);
        }
    }

    /** Called at present time (after the world/HUD boundary) so the boost matches this exact frame's slot. */
    private void updateHdrBoost() {
        float boost = io.github.imcinq.puremetal.perf.HdrHighlights.boost(this.hdrMaxHeadroom);
        if (boost != this.hdrSentBoost) {
            this.hdrSentBoost = boost;
            MetalNative.surfaceSetHdrBoost(this.surface, boost);
        }
        io.github.imcinq.puremetal.perf.HdrHighlights.publish(this.hdrHeadroom, this.hdrMaxHeadroom, boost, this.hdrLinear, this.hdrState == 2);
    }

    /** Once a second: frames rendered vs presented vs actually shown, and why the rest were not presented. */
    private void logPresentStats() {
        long now = System.nanoTime();
        this.statsFrames++;
        if (this.statsStart == 0L) {
            this.statsStart = now;
            return;
        }
        if (now - this.statsStart < 1_000_000_000L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer st = stack.mallocLong(7);
            MetalNative.surfaceTakeStats(this.surface, MemoryUtil.memAddress(st));
            double secs = (now - this.statsStart) / 1e9;
            long shown = st.get(3);
            long drawableWaitMax = FrameTrace.ENABLED ? MetalNative.takeStat(this.device.handle(), 38) : 0L;
            long layerConfigureMax = FrameTrace.ENABLED ? MetalNative.takeStat(this.device.handle(), 39) : 0L;
            if (PresentationProfile.ENABLED) {
                PresentationProfile event = new PresentationProfile();
                if (event.isEnabled()) {
                    event.intervalNs = now - this.statsStart;
                    event.width = this.configuredWidth;
                    event.height = this.configuredHeight;
                    event.vsync = this.configuredVsync;
                    event.focused = net.minecraft.client.Minecraft.getInstance().isWindowActive();
                    event.nativeTimingEnabled = FrameTrace.ENABLED;
                    event.rendered = this.statsFrames;
                    event.skipped = st.get(0);
                    event.slotsBusy = st.get(1);
                    event.presented = st.get(2);
                    event.shown = shown;
                    event.submitToShownSumNs = st.get(4) * 1000L;
                    event.drawableWaitMaxNs = drawableWaitMax;
                    event.layerConfigureMaxNs = layerConfigureMax;
                    event.commit();
                }
            }
            org.slf4j.LoggerFactory.getLogger(MetalSurface.class).info(
                "PRESENT rendered/s={} presented/s={} shown/s={} noDrawable/s={} slotsBusy/s={} presentToShownMs={} hitches={}/{} paced={} sync={}",
                Math.round(this.statsFrames / secs), Math.round(st.get(2) / secs), Math.round(shown / secs),
                Math.round(st.get(0) / secs), Math.round(st.get(1) / secs), shown == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", st.get(4) / 1000.0 / shown),
                st.get(5), st.get(6), io.github.imcinq.puremetal.perf.FramePacing.pacing() == 1,
                MetalNative.surfaceDisplaySync(this.surface)
            );
            io.github.imcinq.puremetal.bench.Bench.hitches += st.get(5);
            io.github.imcinq.puremetal.bench.Bench.shownCompared += st.get(6);
            if (FrameTrace.ENABLED) {
                org.slf4j.LoggerFactory.getLogger(MetalSurface.class).info(
                    "PRESENT-TIMING drawableWaitMaxMs={} layerConfigureMaxMs={} size={}x{} vsync={}",
                    String.format(Locale.ROOT, "%.2f", drawableWaitMax / 1e6), String.format(Locale.ROOT, "%.2f", layerConfigureMax / 1e6),
                    this.configuredWidth, this.configuredHeight, this.configuredVsync
                );
            }
        }
        this.statsStart = now;
        this.statsFrames = 0;
    }

    @Override
    public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
        if (++this.frame == CAPTURE_FRAME) {
            this.capture((MetalCommandEncoder)commandEncoder, (MetalTextureView)textureView);
        }
        if (NO_PRESENT) {
            return;
        }
        if (this.hdrLayerEnabled) {
            this.updateHdrBoost();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer err = stack.calloc(512);
            MetalTextureView view = (MetalTextureView)textureView;
            int ok = MetalNative.surfaceBlit(
                this.device.handle(), this.surface, view.handle(), MemoryUtil.memAddress(err), err.capacity(),
                this.directPresentAllowed(view.metalTexture()) ? 1 : 0
            );
            if (ok == 2) {
                view.metalTexture().presentedDirect();
            }
            if (ok == 0 && err.get(0) != 0) {
                throw new IllegalStateException("Pure Metal could not present: " + MemoryUtil.memUTF8(MemoryUtil.memAddress(err)));
            }
        }
    }

    private boolean directPresentAllowed(final MetalTexture texture) {
        return this.device.directPresent && !this.device.directPresentDisabled && texture.canPresentDirect();
    }

    private void capture(final MetalCommandEncoder encoder, final MetalTextureView view) {
        MetalTexture texture = view.metalTexture();
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        int texel = texture.getFormat().blockSize();
        if (texel != 4) {
            org.slf4j.LoggerFactory.getLogger(MetalSurface.class).warn("Pure Metal capture: unsupported format {}", texture.getFormat());
            return;
        }
        MetalBuffer buffer = new MetalBuffer(this.device, com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_MAP_READ, (long)width * height * 4, null);
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            try {
                java.nio.file.Path out = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("puremetal-capture.png");
                // Render targets use Vulkan's row order, so flip like the present pass; force opaque alpha since HDR stores HUD coverage in A.
                ByteBuffer pixels = MemoryUtil.memByteBuffer(buffer.contents(), width * height * 4);
                for (int i = 3; i < pixels.limit(); i += 4) {
                    pixels.put(i, (byte)0xFF);
                }
                org.lwjgl.stb.STBImageWrite.stbi_flip_vertically_on_write(true);
                org.lwjgl.stb.STBImageWrite.stbi_write_png(out.toString(), width, height, 4, pixels, width * 4);
                org.slf4j.LoggerFactory.getLogger(MetalSurface.class).info("Pure Metal capture: frame {} saved to {}", CAPTURE_FRAME, out.getFileName());
            } finally {
                org.lwjgl.stb.STBImageWrite.stbi_flip_vertically_on_write(false);
                buffer.close();
            }
        }, 0);
    }

    @Override
    public void present() {
        // Already scheduled with presentDrawable when the frame was blitted; it shows once that work completes.
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
    }

    @Override
    public synchronized void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.device.presentSurface == this.surface) {
                this.device.presentSurface = 0L;
            }
            if (MetalNative.surfaceDestroy(this.surface) != 0) {
                SDLMetal.SDL_Metal_DestroyView(this.view);
            } else {
                // Presenter may still be blocked in nextDrawable; keep the view and owning device alive to prevent UAF.
                this.device.markSurfaceNotDrained();
            }
            // Only after teardown: a concurrent MetalDevice.close() then still finds this surface and waits on its monitor.
            if (this.device.liveSurface == this) {
                this.device.liveSurface = null;
            }
        }
    }
}
