// Offscreen regression for unaligned buffer copies, Metal 4 residency re-retain, and temporal motion/jitter signs.
// clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework MetalFX -framework QuartzCore -framework Foundation -framework AppKit -framework IOSurface tools/test_unaligned_copy.m -o /tmp/pm-unaligned-copy
// MTL_DEBUG_LAYER=1 MTL_DEBUG_LAYER_ERROR_MODE=nslog /tmp/pm-unaligned-copy
#include "../src/main/native/puremetal.m"
#include <simd/simd.h>
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

static void submit_and_wait(PMDevice *d, uint64_t *seq) {
    pm_submit(d, ++(*seq));
    assert(pm_wait(d, *seq, 5000000000LL) && !pm_device_failed(d));
}

static void test_unaligned_copies(PMDevice *d, uint64_t *seq) {
    void *src1 = pm_buffer_create(d, 64);
    void *src2 = pm_buffer_create(d, 64);
    void *dst = pm_buffer_create(d, 64);
    assert(src1 && src2 && dst);

    // Case 1: aligned early write [1,2,3,4], then 1-byte unaligned write 9 at offset 1 in the same submit.
    uint8_t *p1 = (uint8_t *)pm_buffer_contents(src1);
    uint8_t *p2 = (uint8_t *)pm_buffer_contents(src2);
    uint8_t *pd = (uint8_t *)pm_buffer_contents(dst);
    memset(pd, 0, 64);
    p1[0] = 1; p1[1] = 2; p1[2] = 3; p1[3] = 4;
    p2[0] = 9;
    pm_copy_buffer_early(d, src1, 0, dst, 0, 4);
    pm_copy_buffer(d, src2, 0, dst, 1, 1);
    submit_and_wait(d, seq);
    assert(pd[0] == 1 && pd[1] == 9 && pd[2] == 3 && pd[3] == 4);

    // Case 2: aligned frame copy followed by unaligned 3-byte write at offset 3, then aligned readback copy.
    uint8_t init[8] = { 10, 20, 30, 40, 50, 60, 70, 80 };
    uint8_t patch[3] = { 99, 88, 77 };
    memcpy(p1, init, sizeof(init));
    memcpy(p2 + 1, patch, sizeof(patch)); // unaligned srcOffset=1, dstOffset=3, size=3
    pm_copy_buffer(d, src1, 0, dst, 0, 8);
    pm_copy_buffer(d, src2, 1, dst, 3, 3);
    pm_copy_buffer(d, dst, 0, src1, 16, 8); // aligned read after unaligned compute write
    submit_and_wait(d, seq);
    uint8_t expected[8] = { 10, 20, 30, 99, 88, 77, 70, 80 };
    assert(memcmp(pd, expected, 8) == 0);
    assert(memcmp(p1 + 16, expected, 8) == 0);

    // Case 3: overlapping same-buffer unaligned copy (backward: dstOffset > srcOffset).
    uint8_t seqBytes[8] = { 1, 2, 3, 4, 5, 6, 7, 8 };
    memcpy(pd, seqBytes, 8);
    pm_copy_buffer(d, dst, 1, dst, 2, 5); // [1, 2, 2, 3, 4, 5, 6, 8]
    submit_and_wait(d, seq);
    uint8_t expBack[8] = { 1, 2, 2, 3, 4, 5, 6, 8 };
    assert(memcmp(pd, expBack, 8) == 0);

    // Case 4: overlapping same-buffer unaligned copy (forward: dstOffset < srcOffset).
    memcpy(pd, seqBytes, 8);
    pm_copy_buffer(d, dst, 2, dst, 1, 5); // [1, 3, 4, 5, 6, 7, 7, 8]
    submit_and_wait(d, seq);
    uint8_t expFwd[8] = { 1, 3, 4, 5, 6, 7, 7, 8 };
    assert(memcmp(pd, expFwd, 8) == 0);

    pm_release(src1);
    pm_release(src2);
    pm_release(dst);
    printf("PASS unaligned buffer copies (%s)\n", d->m4 ? "Metal 4" : "Metal 3");
}

static void test_m4_residency_reretain(PMDevice *d, uint64_t *seq) {
    if (!d->m4) return;
    if (@available(macOS 26.0, *)) {
        void *tex = pm_texture_create(d, 6 /* RGBA8_UNORM */, 16, 16, 1, 1, 12 /* sampled | render target */);
        assert(tex);
        id<MTLTexture> raw = PM_OBJ(id<MTLTexture>, tex);
        // Case A: re-retain while frame N grave entry is pending, then drain frame N; live allocation must stay resident.
        pm_release(tex);
        void *re = pm_retain(raw);
        assert(re == tex);
        submit_and_wait(d, seq);
        m4_sync(d, true);
        id<MTLResidencySet> set = PM_OBJ(id<MTLResidencySet>, d->m4Residency);
        assert([set containsAllocation:(id<MTLAllocation>)raw]);

        // Case B: release in frame N, re-retain and release again in frame N+1 before frame N drains.
        // When frame N drains (done == frameN), the N+1 grave entry is still pending so residency must remain intact.
        uint64_t frameN = d->m4Seq;
        pm_release(re); // grave entry {raw, frameN}
        void *re2 = pm_retain(raw);
        assert(re2 == tex);
        d->m4Seq = frameN + 1;
        pm_release(re2); // grave entry {raw, frameN + 1}, m4Tracked is now empty
        atomic_store(&d->m4Done, frameN);
        m4_sync(d, false);
        assert([set containsAllocation:(id<MTLAllocation>)raw]);
        // Once frame N+1 finishes, both grave entries have drained and residency must be removed.
        atomic_store(&d->m4Done, frameN + 1);
        m4_sync(d, false);
        assert(![set containsAllocation:(id<MTLAllocation>)raw]);
        puts("PASS Metal 4 m4_sync keeps re-retained and multi-frame graved allocations resident");
    }
}

static void test_temporal_motion_y(PMDevice *d, uint64_t *seq) {
    if (!pm_temporal_supported(d)) {
        printf("SKIP temporal motion test (MetalFX temporal unsupported here)\n");
        return;
    }
    // Simple symmetric perspective matrix (column-major): m00=1, m11=1, m22=0, m23=-1, m32=0.1 (reversed-Z: d=0.1/(-z)).
    // Positive view Y maps to positive GL clip Y, which after FLIP_VERTEX_Y maps to increasing texture row gid.y (uv.y = 0.5 + ndc.y*0.5).
    float vp[16] = {
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, 0, -1,
        0, 0, 0.1f, 0
    };
    // Exact inverse of vp:
    float invVp[16] = {
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, 0, 10.0f,
        0, 0, -1, 0
    };
    float params[38];
    memset(params, 0, sizeof(params));
    memcpy(&params[0], invVp, sizeof(invVp));
    memcpy(&params[16], vp, sizeof(vp));
    // Camera moved UP by +1.0 in world Y (camDelta = cur - prev = (0, +1, 0)):
    // A stationary wall point at z = -10 (d = 0.01) was higher in the previous frame, so prev_uv.y > cur_uv.y => mv.y > 0.
    params[33] = 1.0f;
    params[36] = 1.0f;

    MTLTextureDescriptor *dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:8 height:8 mipmapped:NO];
    dd.storageMode = MTLStorageModeShared;
    dd.usage = MTLTextureUsageShaderRead;
    id<MTLTexture> depth = [DEV_MTL(d) newTextureWithDescriptor:dd];
    float depthData[64];
    for (int i = 0; i < 64; i++) depthData[i] = 0.01f; // z = -10
    [depth replaceRegion:MTLRegionMake2D(0, 0, 8, 8) mipmapLevel:0 withBytes:depthData bytesPerRow:8 * sizeof(float)];

    MTLTextureDescriptor *cd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:8 height:8 mipmapped:NO];
    cd.storageMode = MTLStorageModePrivate;
    cd.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    id<MTLTexture> src = [DEV_MTL(d) newTextureWithDescriptor:cd];
    MTLTextureDescriptor *od = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
    od.storageMode = MTLStorageModePrivate;
    od.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget | MTLTextureUsageShaderWrite;
    id<MTLTexture> dst = [DEV_MTL(d) newTextureWithDescriptor:od];

    void *depthHandle = pm_retain(depth);
    void *srcHandle = pm_retain(src);
    void *dstHandle = pm_retain(dst);
    int ok = pm_temporal_prepare(d, depthHandle, srcHandle, dstHandle, params);
    assert(ok == 1);
    void *readback = pm_buffer_create(d, 8 * 8 * 4);
    pm_copy_texture_to_buffer(d, d->txMotion, 0, 0, 0, 0, 8, 8, readback, 0, 8 * 4);
    submit_and_wait(d, seq);
    const _Float16 *mv = (const _Float16 *)pm_buffer_contents(readback);
    // For z = -10, camDelta.y = +1 -> delta_ndc.y = +0.1 -> delta_uv.y = +0.05 -> delta_px.y = +0.05 * 8 = +0.4 px.
    float mvx = (float)mv[(4 * 8 + 4) * 2 + 0];
    float mvy = (float)mv[(4 * 8 + 4) * 2 + 1];
    assert(fabsf(mvx) < 0.01f);
    assert(mvy > 0.35f && mvy < 0.45f);
    pm_release(readback);
    pm_release(dstHandle);
    pm_release(srcHandle);
    pm_release(depthHandle);
    printf("PASS temporal motion Y orientation (%s, mv=(%.3f, %.3f))\n", d->m4 ? "Metal 4" : "Metal 3", mvx, mvy);
}

static float halton_seq(int index, int base) {
    float f = 1.0f, r = 0.0f;
    while (index > 0) { f /= (float)base; r += f * (float)(index % base); index /= base; }
    return r;
}

static simd_float4x4 test_perspective_revz(float fovy, float aspect, float zNear, float zFar) {
    float f = 1.0f / tanf(fovy * 0.5f);
    float m22 = zNear / (zFar - zNear);
    float m32 = (zNear * zFar) / (zFar - zNear);
    return (simd_float4x4){{
        { f / aspect, 0, 0, 0 },
        { 0, f, 0, 0 },
        { 0, 0, m22, -1 },
        { 0, 0, m32, 0 }
    }};
}

static simd_float4x4 test_translate_local(simd_float4x4 m, float tx, float ty) {
    simd_float4x4 t = matrix_identity_float4x4;
    t.columns[3].x = tx;
    t.columns[3].y = ty;
    return simd_mul(t, m);
}

static void measure_temporal_jitter_shimmer(PMDevice *d, uint64_t *seq, id<MTLRenderPipelineState> rps,
                                            id<MTLDepthStencilState> dss, float signX, float signY,
                                            double *outShimmerX, double *outShimmerY) {
    const NSUInteger inW = 64, inH = 64, outW = 128, outH = 128;
    MTLTextureDescriptor *cd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:inW height:inH mipmapped:NO];
    cd.storageMode = MTLStorageModePrivate;
    cd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    id<MTLTexture> color = [DEV_MTL(d) newTextureWithDescriptor:cd];

    MTLTextureDescriptor *zd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:inW height:inH mipmapped:NO];
    zd.storageMode = MTLStorageModePrivate;
    zd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    id<MTLTexture> depth = [DEV_MTL(d) newTextureWithDescriptor:zd];

    MTLTextureDescriptor *od = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:outW height:outH mipmapped:NO];
    od.storageMode = MTLStorageModePrivate;
    od.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    id<MTLTexture> dst = [DEV_MTL(d) newTextureWithDescriptor:od];

    void *colorH = pm_retain(color);
    void *depthH = pm_retain(depth);
    void *dstH = pm_retain(dst);
    void *readback = pm_buffer_create(d, outW * outH * 4);
    assert(colorH && depthH && dstH && readback);

    simd_float4x4 proj = test_perspective_revz(1.2f, 1.0f, 0.05f, 100.0f);
    simd_float4x4 invProj = simd_inverse(proj);
    float params[38];
    memset(params, 0, sizeof(params));
    memcpy(&params[0], &invProj, sizeof(invProj));
    memcpy(&params[16], &proj, sizeof(proj));
    params[36] = 1.0f;

    uint8_t *prev = (uint8_t *)calloc(outW * outH * 4, 1);
    assert(prev);
    double sumX = 0.0, sumY = 0.0;

    for (int f = 0; f < 16; f++) {
        float jx = halton_seq((f % 8) + 1, 2) - 0.5f;
        float jy = halton_seq((f % 8) + 1, 3) - 0.5f;
        simd_float4x4 jp = test_translate_local(proj, 2.0f * jx / (float)inW, 2.0f * jy / (float)inH);

        id<MTLCommandBuffer> cb = current_cb(d);
        assert(cb);
        MTLRenderPassDescriptor *rp = new_pass_descriptor(d);
        rp.colorAttachments[0].texture = color;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        rp.depthAttachment.texture = depth;
        rp.depthAttachment.loadAction = MTLLoadActionClear;
        rp.depthAttachment.clearDepth = 0.0;
        rp.depthAttachment.storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> re = new_render_encoder(d, cb, rp, "temporal jitter test", -1);
        assert(re);
        [re setViewport:(MTLViewport){ 0.0, 0.0, (double)inW, (double)inH, 0.0, 1.0 }];
        [re setRenderPipelineState:rps];
        [re setDepthStencilState:dss];
        bind_bytes(d, re, PM_STAGE_VERTEX, &jp, sizeof(jp), 0);
        [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
        PM_IF_M4(d) m4_ts_render_close(d, (id<MTL4RenderCommandEncoder>)re);
        [re endEncoding];

        assert(pm_temporal_prepare(d, depthH, colorH, dstH, params) == 1);
        assert(pm_temporal_upscale(d, colorH, NULL, dstH, signX * jx, signY * jy, f == 0 ? 1 : 0) == 1);
        pm_copy_texture_to_buffer(d, dstH, 0, 0, 0, 0, (int)outW, (int)outH, readback, 0, (int)(outW * 4));
        submit_and_wait(d, seq);

        const uint8_t *cur = (const uint8_t *)pm_buffer_contents(readback);
        if (f >= 8) {
            for (NSUInteger y = 48; y < 80; y++) {
                for (NSUInteger x = 48; x < 80; x++) {
                    NSUInteger idx = (y * outW + x) * 4;
                    sumX += fabs((double)cur[idx + 0] - (double)prev[idx + 0]);
                    sumY += fabs((double)cur[idx + 1] - (double)prev[idx + 1]);
                }
            }
        }
        memcpy(prev, cur, outW * outH * 4);
    }

    free(prev);
    pm_release(readback);
    pm_release(dstH);
    pm_release(depthH);
    pm_release(colorH);
    *outShimmerX = sumX;
    *outShimmerY = sumY;
}

static void test_temporal_jitter_sign(PMDevice *d, uint64_t *seq) {
    if (!pm_temporal_supported(d)) {
        printf("SKIP temporal jitter sign test (MetalFX temporal unsupported here)\n");
        return;
    }
    static NSString *const STRIPE_SHADER =
        @"#include <metal_stdlib>\n"
         "using namespace metal;\n"
         "struct U { float4x4 mvp; };\n"
         "struct VOut { float4 pos [[position]]; float2 uv; };\n"
         "vertex VOut vs(uint vid [[vertex_id]], constant U &u [[buffer(0)]]) {\n"
         "  float2 pts[6] = { {-0.4,-0.4}, {0.4,-0.4}, {0.4,0.4}, {-0.4,-0.4}, {0.4,0.4}, {-0.4,0.4} };\n"
         "  float4 p = u.mvp * float4(pts[vid], -2.0, 1.0);\n"
         "  p.y = -p.y;\n"
         "  VOut o; o.pos = p; o.uv = pts[vid] * 1.25 + 0.5; return o;\n"
         "}\n"
         "fragment float4 fs(VOut in [[stage_in]]) {\n"
         "  float sx = sin(in.uv.x * 14.0 * 3.14159265) * 0.5 + 0.5;\n"
         "  float sy = sin(in.uv.y * 14.0 * 3.14159265) * 0.5 + 0.5;\n"
         "  return float4(sx, sy, 0.5, 1.0);\n"
         "}\n";
    NSError *err = nil;
    id<MTLLibrary> lib = pm_new_library(d, STRIPE_SHADER, nil, &err);
    assert(lib);
    MTLRenderPipelineDescriptor *rpd = [MTLRenderPipelineDescriptor new];
    rpd.vertexFunction = [lib newFunctionWithName:@"vs"];
    rpd.fragmentFunction = [lib newFunctionWithName:@"fs"];
    rpd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
    rpd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    id<MTLRenderPipelineState> rps = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:rpd error:&err];
    assert(rps);
    MTLDepthStencilDescriptor *dsd = [MTLDepthStencilDescriptor new];
    dsd.depthCompareFunction = MTLCompareFunctionGreaterEqual;
    dsd.depthWriteEnabled = YES;
    id<MTLDepthStencilState> dss = [DEV_MTL(d) newDepthStencilStateWithDescriptor:dsd];
    assert(dss);

    double posX = 0, posY = 0, negX = 0, negY = 0;
    measure_temporal_jitter_shimmer(d, seq, rps, dss, +1.0f, +1.0f, &posX, &posY);
    measure_temporal_jitter_shimmer(d, seq, rps, dss, -1.0f, -1.0f, &negX, &negY);
    assert(posX < negX * 0.7 && posY < negY * 0.7);
    printf("PASS temporal jitter sign (%s, +jx/+jy shimmer=(%.0f, %.0f) vs -jx/-jy (%.0f, %.0f))\n",
           d->m4 ? "Metal 4" : "Metal 3", posX, posY, negX, negY);
}

int main(void) {
    @autoreleasepool {
        for (int m4 = 0; m4 <= 1; m4++) {
            PMDevice *d = pm_device_create(1, m4);
            if (!d) {
                printf("SKIP %s (Metal unavailable)\n", m4 ? "Metal 4" : "Metal 3");
                continue;
            }
            if (m4 && !d->m4) {
                printf("SKIP Metal 4 (%s)\n", d->m4Reason);
                pm_device_destroy(d);
                continue;
            }
            uint64_t seq = 0;
            test_unaligned_copies(d, &seq);
            test_m4_residency_reretain(d, &seq);
            test_temporal_motion_y(d, &seq);
            test_temporal_jitter_sign(d, &seq);
            pm_device_destroy(d);
        }
    }
    return 0;
}
