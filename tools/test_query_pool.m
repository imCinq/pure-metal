// clang -fobjc-arc -O1 -fsanitize=address -g -Wall -Werror -arch arm64 -mmacosx-version-min=14.0 \
//   -framework Metal -framework MetalFX -framework QuartzCore -framework Foundation -framework AppKit -framework IOSurface \
//   tools/test_query_pool.m -o /tmp/pm-query-pool && MTL_DEBUG_LAYER=1 /tmp/pm-query-pool
#import "../src/main/native/puremetal.m"
#include <assert.h>
#include <stdio.h>

static void run_suite(int wantMetal4, int m4EventCompletion) {
    const char *tag = wantMetal4 ? (m4EventCompletion ? "Metal 4 (event)" : "Metal 4 (feedback)") : "Metal 3";
    PMDevice *d = pm_device_create(0, wantMetal4);
    assert(d != NULL);
    if (wantMetal4 && !d->m4) {
        printf("SKIP %s: Metal 4 unavailable\n", tag);
        pm_device_destroy(d);
        return;
    }
    if (wantMetal4 && !m4EventCompletion) {
        pm_set_option(d, 3, 0);
    }
    printf("Testing %s (m4=%d)...\n", tag, d->m4);

    // 1. Calibration timestamp matches mach monotonic nanoseconds closely.
    uint64_t cpuBefore = pm_now_ns();
    uint64_t gpuNow = pm_sample_gpu_timestamp_ns(d);
    uint64_t cpuAfter = pm_now_ns();
    assert(gpuNow > 0);
    int64_t skew = (int64_t)(cpuBefore + ((cpuAfter - cpuBefore) / 2)) - (int64_t)gpuNow;
    assert(skew > -50000000LL && skew < 50000000LL); // within 50 ms

    // 2. Frame-level query span across real GPU work (blit + copy) and 50 consecutive frames (exceeds PM_M4_FRAMES).
    assert(pm_query_pool_create(0) == NULL);
    assert(pm_query_pool_create((1 << 20) + 1) == NULL);
    void *pool = pm_query_pool_create(6);
    assert(pool != NULL);
    assert(pm_query_read(pool, 0) == -1);
    assert(pm_query_read(pool, 5) == -1);
    assert(pm_query_read(pool, -1) == -1);
    assert(pm_query_read(pool, 6) == -1);

    void *bufA = pm_buffer_create(d, 4 << 20);
    void *bufB = pm_buffer_create(d, 4 << 20);
    assert(bufA && bufB);
    memset(pm_buffer_contents(bufA), 0x3C, 4 << 20);

    uint64_t submit = 1;
    uint64_t prevEnd = 0;
    for (int f = 0; f < 50; f++) {
        int base = (f % 3) * 2;
        assert(pm_query_write(d, pool, base, 0) == 1);
        pm_copy_buffer(d, bufA, 0, bufB, 0, 4 << 20);
        pm_copy_buffer(d, bufB, 0, bufA, 0, 4 << 20);
        assert(pm_query_write(d, pool, base + 1, 0) == 1);
        pm_submit(d, submit);
        assert(pm_wait(d, submit, 5000000000LL) != 0);
        submit++;

        int64_t pair[2] = { -1, -1 };
        pm_query_read_range(pool, base, 2, pair);
        assert(pair[0] > 0 && pair[1] > pair[0]);
        assert(pm_query_read(pool, base) == pair[0]);
        assert(pm_query_read(pool, base + 1) == pair[1]);
        if (f == 0) {
            int64_t domainDiff = pair[0] - (int64_t)gpuNow;
            assert(domainDiff > -5000000000LL && domainDiff < 5000000000LL);
        }
        if (prevEnd > 0) {
            assert((uint64_t)pair[1] >= prevEnd);
        }
        prevEnd = (uint64_t)pair[1];
    }

    // 2b. Early-upload-only interval (uses d->uploadCb with no frame cb on Metal 3) measures non-zero GPU duration.
    assert(pm_query_write(d, pool, 0, 0) == 1);
    pm_copy_buffer_early(d, bufA, 0, bufB, 0, 4 << 20);
    pm_copy_buffer_early(d, bufB, 0, bufA, 0, 4 << 20);
    assert(pm_query_write(d, pool, 1, 0) == 1);
    pm_submit(d, submit);
    assert(pm_wait(d, submit, 5000000000LL) != 0);
    submit++;
    int64_t earlyPair[2] = { -1, -1 };
    pm_query_read_range(pool, 0, 2, earlyPair);
    assert(earlyPair[0] > 0 && earlyPair[1] > earlyPair[0]);

    // 2c. Pre-beginProfile tick upload (blit/uploadCb open before first query) must NOT collapse begin/end to GPUEndTime.
    pm_copy_buffer_early(d, bufA, 0, bufB, 0, 1 << 20);
    pm_copy_buffer(d, bufA, 0, bufB, 0, 1 << 20);
    assert(pm_query_write(d, pool, 0, 0) == 1);
    pm_copy_buffer(d, bufB, 0, bufA, 0, 4 << 20);
    assert(pm_query_write(d, pool, 1, 0) == 1);
    pm_submit(d, submit);
    assert(pm_wait(d, submit, 5000000000LL) != 0);
    submit++;
    int64_t tickPair[2] = { -1, -1 };
    pm_query_read_range(pool, 0, 2, tickPair);
    assert(tickPair[0] > 0 && tickPair[1] > tickPair[0]);

    // 3. Render pass with in-pass writeTimestamp (inRenderPass=1) + empty submit (zero GPU work) both resolve cleanly.
    void *tex = pm_texture_create(d, 0 /* RGBA8Unorm */, 64, 64, 1, 1, 8 /* render target */);
    assert(tex != NULL);
    void *colors[1] = { tex };
    int loadOps[1] = { 1 /* Clear */ };
    float clearCol[4] = { 0.25f, 0.5f, 0.75f, 1.0f };
    pm_query_write(d, pool, 0, 0);
    assert(pm_pass_begin(d, 1, colors, loadOps, clearCol, NULL, -1, 0.0, 64, 64, 0, 0, 64, 64, "query_test") != 0);
    pm_query_write(d, pool, 2, 1);
    pm_pass_end(d);
    pm_query_write(d, pool, 1, 0);
    pm_submit(d, submit);
    assert(pm_wait(d, submit, 5000000000LL) != 0);
    submit++;
    int64_t rpVals[3] = { -1, -1, -1 };
    pm_query_read_range(pool, 0, 3, rpVals);
    assert(rpVals[0] > 0 && rpVals[2] >= rpVals[0] && rpVals[1] >= rpVals[0]);
    pm_release(tex);

    pm_query_write(d, pool, 0, 0);
    pm_query_write(d, pool, 1, 0);
    pm_submit(d, submit);
    assert(pm_wait(d, submit, 5000000000LL) != 0);
    submit++;
    int64_t emptyPair[2] = { -1, -1 };
    pm_query_read_range(pool, 0, 2, emptyPair);
    assert(emptyPair[0] > 0 && emptyPair[1] >= emptyPair[0]);

    // 4. Unread in-flight index reuse: Submit A writes slot 0/1, Submit B immediately reuses slot 0/1 before Submit A finishes.
    uint64_t beforeReuseNs = pm_now_ns();
    pm_query_write(d, pool, 0, 0);
    for (int k = 0; k < 8; k++) pm_copy_buffer(d, bufA, 0, bufB, 0, 4 << 20);
    pm_query_write(d, pool, 1, 0);
    pm_submit(d, submit++);

    // Immediately reuse slot 0 and 1 (also write slot 1 twice in the same submit: last write must win).
    pm_query_write(d, pool, 0, 0);
    assert(pm_query_read(pool, 0) == -1);
    pm_copy_buffer(d, bufA, 0, bufB, 0, 4 << 20);
    pm_query_write(d, pool, 1, 0);
    pm_copy_buffer(d, bufB, 0, bufA, 0, 4 << 20);
    pm_query_write(d, pool, 1, 0);
    uint64_t reuseSubmit = submit++;
    pm_submit(d, reuseSubmit);
    assert(pm_wait(d, reuseSubmit, 5000000000LL) != 0);
    int64_t reusePair[2] = { -1, -1 };
    pm_query_read_range(pool, 0, 2, reusePair);
    assert(reusePair[0] > 0 && reusePair[1] >= reusePair[0]);
    assert((uint64_t)reusePair[1] >= beforeReuseNs - 50000000ULL);

    // 5. Close pool while an in-flight submit still references it (must not UAF or leak under ASan).
    void *closingPool = pm_query_pool_create(4);
    assert(closingPool != NULL);
    pm_query_write(d, closingPool, 0, 0);
    for (int k = 0; k < 6; k++) pm_copy_buffer(d, bufA, 0, bufB, 0, 4 << 20);
    pm_query_write(d, closingPool, 1, 0);
    uint64_t closeSubmit = submit++;
    pm_submit(d, closeSubmit);
    pm_query_pool_destroy(closingPool); // closed BEFORE GPU finishes!
    assert(pm_wait(d, closeSubmit, 5000000000LL) != 0);

    // 6. Unsubmitted queries left at device teardown + pool destroyed AFTER device (must not UAF or leak).
    void *orphanPool = pm_query_pool_create(4);
    assert(orphanPool != NULL);
    pm_query_write(d, orphanPool, 0, 0);

    pm_release(bufA);
    pm_release(bufB);
    pm_query_pool_destroy(pool);
    pm_device_destroy(d);
    assert(pm_query_read(orphanPool, 0) == -1);
    pm_query_pool_destroy(orphanPool);
    printf("PASS %s (skew=%lld ns)\n", tag, (long long)skew);
}

int main(void) {
    @autoreleasepool {
        run_suite(0, 1);
        run_suite(1, 1);
        run_suite(1, 0);
        puts("ALL QUERY POOL TESTS PASSED");
    }
    return 0;
}
