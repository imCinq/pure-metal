// clang -fobjc-arc -O1 -fsanitize=address -g -Wall -Werror -arch arm64 -mmacosx-version-min=14.0 \
//   -framework Metal -framework MetalFX -framework QuartzCore -framework Foundation -framework AppKit -framework IOSurface \
//   tools/test_frame_pacing.m -o /tmp/pm-pacing-test && /tmp/pm-pacing-test
#import "../src/main/native/puremetal.m"
#include <assert.h>
#include <math.h>
#include <stdio.h>

static void require_near(double actual, double expected, double eps, const char *label) {
    if (fabs(actual - expected) > eps) {
        fprintf(stderr, "FAIL %s: actual=%.6f expected=%.6f\n", label, actual, expected);
        abort();
    }
}

int main(void) {
    @autoreleasepool {
        PMSurface s;
        memset(&s, 0, sizeof(s));
        pthread_mutex_init(&s.cfgLock, NULL);

        const uint64_t r60 = 16666667ULL;  // 60 Hz
        const uint64_t r120 = 8333333ULL;  // 120 Hz
        pm_surface_set_pacing(&s, r60, 1, 0);

        // 1. Before any anchor is recorded, present immediately (0).
        assert(pacing_target_at(&s, 1, 100.0) == 0.0);

        // 2. On-time 60 Hz cadence: frame 1 shown at t=100.000s, frame 2 submitted at t=100.002s -> target = 100 + 0.75 * interval.
        pacing_record_anchor(&s, 1, 100000000000ULL);
        double t2 = pacing_target_at(&s, 2, 100.002);
        require_near(t2, 100.0 + 0.75 * (r60 / 1e9), 1e-6, "frame 2 target");
        // Queued frame 3 while frame 2 is still unshown -> target = 100 + 1.75 * interval.
        double t3 = pacing_target_at(&s, 3, 100.004);
        require_near(t3, 100.0 + 1.75 * (r60 / 1e9), 1e-6, "frame 3 target");

        // 3. Late frame is never delayed (returns 0 -> present immediately).
        assert(pacing_target_at(&s, 2, 100.015) == 0.0);
        // Stale anchor (>0.25s old) never delays.
        assert(pacing_target_at(&s, 2, 100.300) == 0.0);
        // Sequence gap > 4 falls back to immediate present.
        assert(pacing_target_at(&s, 6, 100.002) == 0.0);

        // 4. Monotonic anchor: out-of-order older presented callback must NOT rewind anchorSeq/anchorNs.
        pacing_record_anchor(&s, 5, 100066666668ULL);
        pacing_record_anchor(&s, 4, 100050000000ULL); // delayed callback for frame 4
        pthread_mutex_lock(&s.cfgLock);
        assert(s.anchorSeq == 5 && s.anchorNs == 100066666668ULL);
        pthread_mutex_unlock(&s.cfgLock);

        // 5. Refresh rate change (60 Hz -> 120 Hz) resets stale anchor so first frame on new display presents cleanly.
        pm_surface_set_pacing(&s, r120, 1, 0);
        assert(pacing_target_at(&s, 6, 100.068) == 0.0);
        pacing_record_anchor(&s, 6, 100075000000ULL);
        double t7 = pacing_target_at(&s, 7, 100.076);
        require_near(t7, 100.075 + 0.75 * (r120 / 1e9), 1e-6, "120Hz frame 7 target");

        // 6. Toggling pacing off disables scheduled presents and resets anchor on re-enable.
        pm_surface_set_pacing(&s, r120, 0, 0);
        assert(pacing_target_at(&s, 7, 100.076) == 0.0);
        pm_surface_set_pacing(&s, r120, 1, 0);
        assert(pacing_target_at(&s, 7, 100.076) == 0.0);

        // 7. Concurrent anchor writer + pacing_target reader: no torn (anchorSeq, anchorNs) pair ever observed.
        atomic_int stop = 0;
        PMSurface *sp = &s;
        atomic_int *stopPtr = &stop;
        dispatch_semaphore_t writerDone = dispatch_semaphore_create(0);
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            for (uint64_t k = 10; k < 20000; k++) {
                // Invariant: anchorNs == k * 10000000ULL (10ms per seq).
                pacing_record_anchor(sp, k, k * 10000000ULL);
            }
            atomic_store(stopPtr, 1);
            dispatch_semaphore_signal(writerDone);
        });
        while (!atomic_load(&stop)) {
            pthread_mutex_lock(&s.cfgLock);
            uint64_t aSeq = s.anchorSeq, aNs = s.anchorNs;
            pthread_mutex_unlock(&s.cfgLock);
            if (aSeq >= 10) {
                assert(aNs == aSeq * 10000000ULL);
            }
        }
        dispatch_semaphore_wait(writerDone, DISPATCH_TIME_FOREVER);

        pthread_mutex_destroy(&s.cfgLock);
        puts("PASS smooth frame pacing math, monotonicity, refresh switch reset, and atomic anchor");
    }
    return 0;
}
