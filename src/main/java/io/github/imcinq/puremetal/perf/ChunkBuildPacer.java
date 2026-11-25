package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.backend.MetalNative;
import io.github.imcinq.puremetal.backend.MetalUpscaler;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Concurrency pacer for vanilla {@code SectionRenderDispatcher} async chunk meshing on Pure Metal.
 *
 * <p>Vanilla wakes every {@code Worker-Main} thread simultaneously (9 on a 10-core M5 Air) whenever occluded sections
 * become visible at once, spiking package power on fanless Apple Silicon Macs and starving the render thread.
 * Bounding concurrent compile workers by non-efficiency core count (Super + Performance via {@code hw.perflevelN}) and
 * macOS thermal state keeps headroom while {@code SectionTaskDynamicQueue} still drains closest sections first. Steps
 * down immediately on higher thermal states and requires {@value #RECOVER_NS} ns of continuous cooler readings before
 * relaxing back to the current thermal state. Lifted while a loading screen/overlay is up so
 * initial world entry prioritizes chunk throughput. Inactive when Pure Metal is not the active backend.
 *
 * <p>Opt-out: {@code -Dpuremetal.disableChunkBuildPacer=true}.
 * Manual override: {@code -Dpuremetal.maxChunkBuildThreads=<N>}.
 */
public final class ChunkBuildPacer {
    public static final boolean ENABLED = !Boolean.getBoolean("puremetal.disableChunkBuildPacer");
    private static final int OVERRIDE_MAX = Integer.getInteger("puremetal.maxChunkBuildThreads", 0);
    private static final long POLL_NS = 500_000_000L;
    private static final long RECOVER_NS = 5_000_000_000L;

    private static volatile IntSupplier cpuSupplier = () -> Runtime.getRuntime().availableProcessors();
    private static volatile IntSupplier perfCoreSupplier = MetalNative::perfCoreCount;
    private static volatile BooleanSupplier metalActive = MetalUpscaler::active;
    private static volatile BooleanSupplier loadingSupplier = ChunkBuildPacer::isLoadingScreen;
    private static volatile IntSupplier thermalSupplier = MetalNative::powerState;
    private static volatile LongSupplier clockNs = System::nanoTime;

    private static final Object THERMAL_LOCK = new Object();
    private static volatile long lastPollNs = Long.MIN_VALUE;
    private static volatile int effectiveThermal;
    private static long coolerSinceNs = Long.MIN_VALUE;
    private static volatile boolean loadingScreen;

    private ChunkBuildPacer() {
    }

    /** Updated on the render thread so worker threads can lift the cap during world/resource loading screens. */
    public static void setLoadingScreen(final boolean loading) {
        loadingScreen = loading;
    }

    private static boolean isLoadingScreen() {
        return loadingScreen;
    }

    /**
     * Maximum number of {@code SectionRenderDispatcher} async tasks allowed in flight at once, or
     * {@link Integer#MAX_VALUE} when disabled, during loading screens, or running on a non-Metal backend.
     *
     * <p>Scaling by Apple Silicon non-efficiency core count (Super + Performance via {@code hw.perflevelN}) and
     * thermal state (Nominal / Fair / Serious+):
     * <ul>
     *   <li>4 P-cores (M1-M5 base/Air, 8-10 total): 4 / 3 / 2</li>
     *   <li>6 P-cores (M1/M2/M3 Pro, 8-12 total): 6 / 4 / 3</li>
     *   <li>8 P-cores (M1/M2/M4 Pro, 10-12 total): 8 / 5 / 4</li>
     *   <li>10 P-cores (M3/M4 Pro/Max, 14 total): 10 / 7 / 5</li>
     *   <li>12 P-cores (M3/M4 Max, 16 total): 12 / 8 / 6</li>
     *   <li>16 P-cores (M1/M2 Ultra, 20 total): 16 / 11 / 8</li>
     *   <li>18-20 P-cores (M5 Pro/Max Super+Perf, M3 Ultra): 18-20 / 12-13 / 9-10</li>
     * </ul>
     */
    public static int maxConcurrentBuilds() {
        if (!ENABLED || !metalActive.getAsBoolean()) {
            return Integer.MAX_VALUE;
        }
        if (OVERRIDE_MAX > 0) {
            return OVERRIDE_MAX;
        }
        if (loadingSupplier.getAsBoolean()) {
            return Integer.MAX_VALUE;
        }
        return computeLimit(perfCoreSupplier.getAsInt(), cpuSupplier.getAsInt(), pollThermalState());
    }

    static int computeLimit(final int perfCores, final int totalCpus, final int thermalState) {
        int maxWorkers = Math.max(1, totalCpus - 1);
        int effectiveP = perfCores > 0 ? Math.min(perfCores, totalCpus) : Math.max(1, totalCpus / 2);
        int target;
        if (thermalState >= 2) {
            target = Math.max(2, effectiveP / 2);
        } else if (thermalState == 1) {
            target = Math.max(2, (effectiveP * 2 + 1) / 3);
        } else {
            target = Math.max(2, effectiveP);
        }
        return Math.min(target, maxWorkers);
    }

    private static int pollThermalState() {
        long now = clockNs.getAsLong();
        long prev = lastPollNs;
        if (prev != Long.MIN_VALUE && now - prev < POLL_NS) {
            return effectiveThermal;
        }
        synchronized (THERMAL_LOCK) {
            now = clockNs.getAsLong();
            if (lastPollNs != Long.MIN_VALUE && now - lastPollNs < POLL_NS) {
                return effectiveThermal;
            }
            int raw = thermalSupplier.getAsInt();
            lastPollNs = now;
            if (raw >= effectiveThermal) {
                effectiveThermal = raw;
                coolerSinceNs = Long.MIN_VALUE;
            } else if (coolerSinceNs == Long.MIN_VALUE) {
                coolerSinceNs = now;
            } else if (now - coolerSinceNs >= RECOVER_NS) {
                effectiveThermal = raw;
                coolerSinceNs = Long.MIN_VALUE;
            }
            return effectiveThermal;
        }
    }

    /** Test hook: injects deterministic P-core/CPU counts, backend readiness, loading state, thermal state, and clock. */
    public static void setHooksForTest(
        final IntSupplier perfCores, final IntSupplier cpus, final BooleanSupplier active,
        final BooleanSupplier loading, final IntSupplier thermal, final LongSupplier clock
    ) {
        synchronized (THERMAL_LOCK) {
            perfCoreSupplier = perfCores;
            cpuSupplier = cpus;
            metalActive = active;
            loadingSupplier = loading;
            thermalSupplier = thermal;
            clockNs = clock;
            lastPollNs = Long.MIN_VALUE;
            effectiveThermal = 0;
            coolerSinceNs = Long.MIN_VALUE;
            loadingScreen = false;
        }
    }
}
