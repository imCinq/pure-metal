package io.github.imcinq.puremetal.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.imcinq.puremetal.perf.ChunkBuildPacer;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.TracingExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Caps concurrent async {@code SectionRenderDispatcher} tasks on {@code Util.backgroundExecutor()} via
 * {@link ChunkBuildPacer}. Tasks still drain closest-first through {@code SectionTaskDynamicQueue}, and
 * synchronous player-edited compiles ({@code compileSync}) are untouched.
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherPacerMixin {
    /** Per-worker-thread runTask outcome: 0=pre-acquire/empty, 1=completed cleanly, 2=pool empty, 3=pack held / failed. */
    @Unique
    private static final int PUREMETAL$STATE_PRE_ACQUIRE = 0;
    @Unique
    private static final int PUREMETAL$STATE_COMPLETED = 1;
    @Unique
    private static final int PUREMETAL$STATE_POOL_EMPTY = 2;
    @Unique
    private static final int PUREMETAL$STATE_PACK_ACQUIRED = 3;
    @Unique
    private static final int PUREMETAL$STATE_UNPACED = -1;

    @Unique
    private static final ThreadLocal<int[]> PUREMETAL$RUN_STATE = ThreadLocal.withInitial(() -> new int[] {PUREMETAL$STATE_UNPACED});

    @Shadow @Final private SectionTaskDynamicQueue queue;
    @Shadow @Final private TracingExecutor executor;
    @Shadow private volatile boolean closed;

    @Shadow
    private void runTask() {
        throw new AssertionError();
    }

    @Shadow
    public abstract int getFreeBufferCount();

    @Unique
    private final AtomicInteger puremetal$inFlight = new AtomicInteger();

    /**
     * Set only when a paced worker hits an empty shared {@code SectionBufferBuilderPool} with no other local in-flight
     * worker left to wake this dispatcher (e.g., right after dispatcher replacement while disposed workers still hold
     * packs). Checked once per frame on the render thread in {@code uploadTerrainBuffersToGpu()} so queued terrain
     * resumes as soon as packs return, without ever re-triggering on a {@code STATE_PACK_ACQUIRED} NPE.
     */
    @Unique
    private volatile boolean puremetal$poolStarved;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void puremetal$initLoadingState(final CallbackInfo ci) {
        puremetal$refreshLoadingState();
    }

    @Inject(method = "uploadTerrainBuffersToGpu", at = @At("HEAD"))
    private void puremetal$onFrameUpload(final CallbackInfo ci) {
        puremetal$refreshLoadingState();
        if (this.puremetal$poolStarved && !this.closed && this.getFreeBufferCount() > 0) {
            this.puremetal$poolStarved = false;
            int queued = this.puremetal$queuedCount();
            if (queued > 0) {
                int limit = ChunkBuildPacer.maxConcurrentBuilds();
                int cap = limit == Integer.MAX_VALUE ? Math.max(1, Runtime.getRuntime().availableProcessors()) : limit;
                this.puremetal$tryDispatch(queued, cap, null);
            }
        }
    }

    @WrapOperation(
        method = "schedule",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/TracingExecutor;execute(Ljava/lang/Runnable;)V")
    )
    private void puremetal$pacedSchedule(
        final TracingExecutor executor, final Runnable task, final Operation<Void> original
    ) {
        int limit = ChunkBuildPacer.maxConcurrentBuilds();
        if (limit == Integer.MAX_VALUE) {
            this.puremetal$poolStarved = false;
            original.call(executor, task);
            return;
        }
        this.puremetal$poolStarved = false;
        this.puremetal$tryDispatch(1, limit, original);
    }

    @WrapOperation(
        method = "runTask",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SectionBufferBuilderPool;acquire()Lnet/minecraft/client/renderer/SectionBufferBuilderPack;")
    )
    private @Nullable SectionBufferBuilderPack puremetal$trackAcquire(
        final SectionBufferBuilderPool pool, final Operation<SectionBufferBuilderPack> original
    ) {
        SectionBufferBuilderPack pack = original.call(pool);
        int[] state = PUREMETAL$RUN_STATE.get();
        if (state[0] != PUREMETAL$STATE_UNPACED) {
            state[0] = pack == null ? PUREMETAL$STATE_POOL_EMPTY : PUREMETAL$STATE_PACK_ACQUIRED;
        }
        return pack;
    }

    @WrapOperation(
        method = "runTask",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/TracingExecutor;execute(Ljava/lang/Runnable;)V")
    )
    private void puremetal$suppressRecursiveDispatch(
        final TracingExecutor executor, final Runnable task, final Operation<Void> original
    ) {
        int[] state = PUREMETAL$RUN_STATE.get();
        if (state[0] == PUREMETAL$STATE_UNPACED) {
            original.call(executor, task);
            return;
        }
        // Reached only after doTask finished and bufferPool.release(pack) succeeded;
        // puremetal$runPacedTask's finally block handles re-dispatching after decrementing inFlight.
        state[0] = PUREMETAL$STATE_COMPLETED;
    }

    @Unique
    private void puremetal$runPacedTask() {
        int[] state = PUREMETAL$RUN_STATE.get();
        int prevState = state[0];
        state[0] = PUREMETAL$STATE_PRE_ACQUIRE;
        try {
            this.runTask();
        } finally {
            int outcome = state[0];
            state[0] = prevState;
            int remaining = this.puremetal$inFlight.decrementAndGet();
            if (!this.closed) {
                if (this.puremetal$shouldRedispatch(outcome, remaining)) {
                    if (outcome == PUREMETAL$STATE_COMPLETED) {
                        this.puremetal$poolStarved = false;
                    }
                    int queued = this.puremetal$queuedCount();
                    if (queued > 0) {
                        int limit = ChunkBuildPacer.maxConcurrentBuilds();
                        int cap = limit == Integer.MAX_VALUE ? Math.max(1, Runtime.getRuntime().availableProcessors()) : limit;
                        this.puremetal$tryDispatch(queued, cap, null);
                    }
                } else if (outcome == PUREMETAL$STATE_POOL_EMPTY && remaining == 0) {
                    this.puremetal$poolStarved = true;
                }
            }
        }
    }

    @Unique
    private boolean puremetal$shouldRedispatch(final int outcome, final int remaining) {
        // Never re-dispatch on STATE_PACK_ACQUIRED: vanilla caught an NPE/exception inside doTask without releasing
        // the pack and intentionally stops until a later schedule(), preventing a tight pack-leaking spin loop.
        if (outcome == PUREMETAL$STATE_PACK_ACQUIRED) {
            return false;
        }
        if (outcome == PUREMETAL$STATE_POOL_EMPTY) {
            // Only wake immediately when no other in-flight task remains to wake us AND a buffer is actually free.
            return remaining == 0 && this.getFreeBufferCount() > 0;
        }
        return outcome == PUREMETAL$STATE_COMPLETED || outcome == PUREMETAL$STATE_PRE_ACQUIRE;
    }

    @Unique
    private void puremetal$tryDispatch(
        final int maxNew, final int limit, final @Nullable Operation<Void> scheduleOriginal
    ) {
        if (this.closed || maxNew <= 0) {
            return;
        }
        int dispatched = 0;
        while (dispatched < maxNew && !this.closed) {
            int current = this.puremetal$inFlight.get();
            if (current >= limit) {
                return;
            }
            if (this.puremetal$inFlight.compareAndSet(current, current + 1)) {
                dispatched++;
                try {
                    Runnable paced = this::puremetal$runPacedTask;
                    if (scheduleOriginal != null && dispatched == 1) {
                        scheduleOriginal.call(this.executor, paced);
                    } else {
                        this.executor.execute(paced);
                    }
                } catch (Throwable t) {
                    this.puremetal$inFlight.decrementAndGet();
                    throw t;
                }
            }
        }
    }

    @Unique
    private int puremetal$queuedCount() {
        synchronized (this.queue) {
            return this.queue.size();
        }
    }

    @Unique
    private static void puremetal$refreshLoadingState() {
        Minecraft mc = Minecraft.getInstance();
        boolean loading = mc != null && mc.gui != null
            && (mc.gui.screen() instanceof LevelLoadingScreen || mc.gui.overlay() != null);
        ChunkBuildPacer.setLoadingScreen(loading);
    }
}
