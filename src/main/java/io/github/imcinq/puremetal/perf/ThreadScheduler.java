package io.github.imcinq.puremetal.perf;

import com.mojang.logging.LogUtils;
import io.github.imcinq.puremetal.backend.MetalNative;
import io.github.imcinq.puremetal.backend.MetalUpscaler;
import io.github.imcinq.puremetal.backend.PureMetalBackend;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;

/**
 * macOS thread QoS helper for {@code Sound engine}.
 *
 * <p>Promotes {@code SoundEngineExecutor}'s thread to {@code QOS_CLASS_USER_INITIATED} so {@code SoundEngine.play()}'s
 * synchronous channel-creation join on the render thread does not queue behind CPU-bound {@code Worker-Main} threads.
 * Only activates once Pure Metal's Metal device is active; never promotes on non-Metal sessions and latches as a no-op
 * if Pure Metal failed or was rejected during backend startup.
 *
 * <p>Opt-out: {@code -Dpuremetal.disableThreadQos=true}.
 */
public final class ThreadScheduler {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ENABLED = !Boolean.getBoolean("puremetal.disableThreadQos");

    public static final int QOS_DEFAULT = 0;
    public static final int QOS_UTILITY = 1;
    public static final int QOS_USER_INITIATED = 2;
    public static final int QOS_USER_INTERACTIVE = 3;

    private static volatile BooleanSupplier metalActive = () -> MetalUpscaler.active();
    private static volatile BooleanSupplier metalFallback = () -> PureMetalBackend.fellBack();
    private static volatile IntUnaryOperator qosSetter = level -> MetalNative.setThreadQos(level);
    private static final AtomicInteger SOUND_PROMOTIONS = new AtomicInteger();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();

    private ThreadScheduler() {
    }

    /**
     * Sound engine thread: raises the calling thread's QoS to {@code User Initiated} when Pure Metal is active.
     * Returns {@code true} once applied, disabled, fallen back to a non-Metal backend, or attempted once (logging
     * {@code errno} once on failure) so the caller never retries on every park. Returns {@code false} only while
     * backend initialization is still pending.
     */
    public static boolean promoteSoundThread() {
        if (!ENABLED) {
            return true;
        }
        if (!metalActive.getAsBoolean()) {
            return metalFallback.getAsBoolean();
        }
        int rc = qosSetter.applyAsInt(QOS_USER_INITIATED);
        if (rc == 0) {
            SOUND_PROMOTIONS.incrementAndGet();
        } else if (FAILURE_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("Pure Metal could not set Sound engine thread QoS (errno={})", rc);
        }
        return true;
    }

    static int soundPromotions() {
        return SOUND_PROMOTIONS.get();
    }

    /** Test hook: injects deterministic backend/native providers and resets counters. */
    public static void setHooksForTest(
        final BooleanSupplier active, final BooleanSupplier fallback, final IntUnaryOperator setter
    ) {
        metalActive = active;
        metalFallback = fallback;
        qosSetter = setter;
        SOUND_PROMOTIONS.set(0);
        FAILURE_LOGGED.set(false);
    }
}
