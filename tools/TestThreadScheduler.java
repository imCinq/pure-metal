package io.github.imcinq.puremetal.perf;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Headless regression checks for ThreadScheduler Sound engine promotion and native QoS / P-core symbols. */
public final class TestThreadScheduler {
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(final String[] args) throws Throwable {
        AtomicBoolean active = new AtomicBoolean(false);
        AtomicBoolean fallback = new AtomicBoolean(false);
        List<Integer> calls = new ArrayList<>();

        ThreadScheduler.setHooksForTest(active::get, fallback::get, level -> {
            calls.add(level);
            return 0;
        });

        // Backend pending: returns false without calling native QoS.
        require(!ThreadScheduler.promoteSoundThread(), "sound promotion must wait while backend is pending");
        require(calls.isEmpty(), "no QoS calls while backend is pending");

        // OpenGL fallback: latches true without ever calling native QoS.
        fallback.set(true);
        require(ThreadScheduler.promoteSoundThread(), "OpenGL fallback must latch true");
        require(calls.isEmpty(), "OpenGL fallback must not call native QoS");
        require(ThreadScheduler.soundPromotions() == 0, "fallback must not increment soundPromotions");

        // Active Metal backend: promotes once to USER_INITIATED.
        fallback.set(false);
        active.set(true);
        require(ThreadScheduler.promoteSoundThread(), "sound promotion succeeds when Metal is active");
        require(calls.equals(List.of(ThreadScheduler.QOS_USER_INITIATED)), "expected USER_INITIATED, got " + calls);
        require(ThreadScheduler.soundPromotions() == 1, "expected 1 promotion, got " + ThreadScheduler.soundPromotions());

        // Failure latch: if native returns non-zero, promoteSoundThread still returns true so caller doesn't retry on every park.
        calls.clear();
        ThreadScheduler.setHooksForTest(active::get, fallback::get, level -> {
            calls.add(level);
            return 22; // EINVAL
        });
        require(ThreadScheduler.promoteSoundThread(), "sound promotion must latch even on native failure");
        require(calls.size() == 1, "expected 1 attempt, got " + calls.size());
        require(ThreadScheduler.soundPromotions() == 0, "failed call must not increment soundPromotions");

        if (args.length > 0) {
            Path dylib = Path.of(args[0]);
            SymbolLookup lookup = SymbolLookup.libraryLookup(dylib, Arena.global());
            MethodHandle setQos = Linker.nativeLinker().downcallHandle(
                lookup.find("pm_set_thread_qos").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            for (int level : new int[] {
                ThreadScheduler.QOS_UTILITY, ThreadScheduler.QOS_USER_INITIATED,
                ThreadScheduler.QOS_USER_INTERACTIVE, ThreadScheduler.QOS_DEFAULT
            }) {
                int rc = (int) setQos.invokeExact(level);
                require(rc == 0, "pm_set_thread_qos(" + level + ") failed with rc=" + rc);
            }
            MethodHandle perfCores = Linker.nativeLinker().downcallHandle(
                lookup.find("pm_perf_core_count").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            int pCores = (int) perfCores.invokeExact();
            require(pCores > 0 && pCores <= Runtime.getRuntime().availableProcessors(),
                "pm_perf_core_count returned unexpected count: " + pCores);
            System.out.println("native pm_set_thread_qos verified across all 4 QoS levels; pm_perf_core_count=" + pCores);
        }

        System.out.println("TestThreadScheduler OK");
    }
}
