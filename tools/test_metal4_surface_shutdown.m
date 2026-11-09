// clang -fobjc-arc -O1 -fsanitize=address -g -arch arm64 -mmacosx-version-min=14.0 -framework Metal -framework MetalFX -framework QuartzCore -framework Foundation -framework AppKit -framework IOSurface tools/test_metal4_surface_shutdown.m -o /tmp/pm-surface-shutdown && /tmp/pm-surface-shutdown
#import "../src/main/native/puremetal.m"
#include <assert.h>

int main(void) { @autoreleasepool {
    PMDevice *device = pm_device_create(0, 1);
    if (!device || !device->m4) {
        puts("SKIP: Metal 4 unavailable");
        if (device) pm_device_destroy(device);
        return 0;
    }
    CAMetalLayer *layer = [CAMetalLayer layer];
    PMSurface *surface = pm_surface_create(device, (__bridge void *)layer);
    assert(surface && surface->shownContext);
    // Simulate a presented handler that has not arrived when the surface closes.
    PMShownContext *context = surface->shownContext;
    atomic_fetch_add(&context->refs, 1);
    atomic_fetch_add(&surface->onScreenPending, 1);
    double start = CACurrentMediaTime();
    pm_surface_destroy(surface);
    double elapsed = CACurrentMediaTime() - start;
    pthread_mutex_lock(&context->lock);
    assert(context->surface == NULL);
    pthread_mutex_unlock(&context->lock);
    assert(elapsed < 2.0);
    // Simulate the missing handler arriving after the surface has been freed.
    shown_context_release(context);
    pm_device_destroy(device);
    printf("PASS: detached late callback, surface destroyed in %.3f seconds\n", elapsed);
}}
