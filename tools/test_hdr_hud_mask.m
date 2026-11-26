// clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework MetalFX -framework QuartzCore -framework Foundation -framework AppKit -framework IOSurface tools/test_hdr_hud_mask.m -o /tmp/pm-hdr-hud-mask && MTL_DEBUG_LAYER=1 MTL_DEBUG_LAYER_ERROR_MODE=nslog /tmp/pm-hdr-hud-mask
#include "../src/main/native/puremetal.m"
#include <assert.h>
#include <math.h>
#include <stdio.h>

static float f16_to_f32(uint16_t h) {
    uint32_t s = (uint32_t)(h & 0x8000u) << 16;
    uint32_t e = (h >> 10) & 0x1fu;
    uint32_t m = h & 0x3ffu;
    uint32_t f;
    if (e == 0) {
        if (m == 0) f = s;
        else {
            e = 1;
            while ((m & 0x400u) == 0) { m <<= 1; e--; }
            m &= 0x3ffu;
            f = s | ((e + 112) << 23) | (m << 13);
        }
    } else if (e == 31) {
        f = s | 0x7f800000u | (m << 13);
    } else {
        f = s | ((e + 112) << 23) | (m << 13);
    }
    float out;
    memcpy(&out, &f, sizeof(out));
    return out;
}

static void run_present_hdr(PMDevice *d, id<MTLTexture> src, id<MTLTexture> dst, float cap, float headroom) {
    char err[256] = {0};
    assert(ensure_present_pipeline(d, MTLPixelFormatRGBA16Float, err, sizeof(err)));
    float params[8] = { (float)dst.height, cap, 1.0f, 0.0f, headroom, 0.0f, 0.0f, 0.0f };
    PM_IF_M4(d) {
        id<MTL4CommandQueue> q = PM_OBJ(id<MTL4CommandQueue>, d->m4PresentQueue);
        id<MTL4CommandAllocator> alloc = PM_OBJ(id<MTL4CommandAllocator>, d->m4PresentAlloc[0]);
        [alloc reset];
        id<MTL4CommandBuffer> cb = PM_OBJ(id<MTL4CommandBuffer>, d->m4PresentCb[0]);
        [cb beginCommandBufferWithAllocator:alloc];
        [cb useResidencySet:PM_OBJ(id<MTLResidencySet>, d->m4Residency)];
        MTL4RenderPassDescriptor *rp = [MTL4RenderPassDescriptor new];
        rp.colorAttachments[0].texture = dst;
        rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTL4RenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
        assert(e);
        [e setViewport:(MTLViewport){ 0.0, 0.0, (double)dst.width, (double)dst.height, 0.0, 1.0 }];
        [e setRenderPipelineState:PM_OBJ(id<MTLRenderPipelineState>, d->presentHdrPipeline)];
        id<MTL4ArgumentTable> table = PM_OBJ(id<MTL4ArgumentTable>, d->m4PresentTable);
        id<MTLBuffer> constant = PM_OBJ(id<MTLBuffer>, d->m4PresentConst[0]);
        memcpy(constant.contents, params, sizeof(params));
        [table setTexture:src.gpuResourceID atIndex:0];
        [table setAddress:constant.gpuAddress atIndex:0];
        [e setArgumentTable:table atStages:MTLRenderStageFragment];
        [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [e endEncoding];
        [cb endCommandBuffer];
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        __block NSError *commitErr = nil;
        MTL4CommitOptions *opts = [MTL4CommitOptions new];
        [opts addFeedbackHandler:^(id<MTL4CommitFeedback> fb) {
            commitErr = fb.error;
            dispatch_semaphore_signal(done);
        }];
        id<MTL4CommandBuffer> list[1] = { cb };
        [q commit:list count:1 options:opts];
        assert(dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) == 0);
        assert(!commitErr);
        return;
    }
    id<MTLCommandBuffer> cb = [PM_OBJ(id<MTLCommandQueue>, d->queue) commandBuffer];
    assert(cb);
    MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = dst;
    rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
    assert(e);
    [e setViewport:(MTLViewport){ 0.0, 0.0, (double)dst.width, (double)dst.height, 0.0, 1.0 }];
    [e setRenderPipelineState:PM_OBJ(id<MTLRenderPipelineState>, d->presentHdrPipeline)];
    [e setFragmentTexture:src atIndex:0];
    [e setFragmentBytes:params length:sizeof(params) atIndex:0];
    [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [e endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    assert(cb.status == MTLCommandBufferStatusCompleted);
}

static void test_backend(int metal4) {
    PMDevice *d = pm_device_create(0, metal4);
    assert(d);
    if (metal4 && !d->m4) {
        printf("SKIP Metal 4 unavailable (%s)\n", d->m4Reason);
        pm_device_destroy(d);
        return;
    }
    NSError *error = nil;
    NSString *hudSrc =
        @"#include <metal_stdlib>\n"
         "using namespace metal;\n"
         "vertex float4 v(uint i [[vertex_id]]) {\n"
         "  float2 p[3] = { float2(-1, -1), float2(3, -1), float2(-1, 3) };\n"
         "  return float4(p[i], 0, 1);\n"
         "}\n"
         "fragment float4 f(constant float4 &c [[buffer(0)]]) { return c; }\n";
    id<MTLLibrary> lib = [DEV_MTL(d) newLibraryWithSource:hudSrc options:nil error:&error];
    assert(lib && !error);
    MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
    pd.vertexFunction = [lib newFunctionWithName:@"v"];
    pd.fragmentFunction = [lib newFunctionWithName:@"f"];
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
    // Exact BlendFunction.TRANSLUCENT from RenderPearl: RGB (SrcAlpha, 1-SrcAlpha), A (One, 1-SrcAlpha).
    pd.colorAttachments[0].blendingEnabled = YES;
    pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    pd.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    pd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    pd.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    id<MTLRenderPipelineState> hudPipe = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error];
    assert(hudPipe && !error);

    pd.colorAttachments[0].blendingEnabled = NO;
    id<MTLRenderPipelineState> worldPipe = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error];
    assert(worldPipe && !error);

    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:8 height:1 mipmapped:NO];
    td.storageMode = MTLStorageModePrivate;
    td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    id<MTLTexture> mainTex = [DEV_MTL(d) newTextureWithDescriptor:td];
    assert(mainTex);

    MTLTextureDescriptor *dtd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:8 height:1 mipmapped:NO];
    dtd.storageMode = MTLStorageModePrivate;
    dtd.usage = MTLTextureUsageRenderTarget;
    id<MTLTexture> depthTex = [DEV_MTL(d) newTextureWithDescriptor:dtd];
    assert(depthTex);

    MTLTextureDescriptor *wtd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:4 height:1 mipmapped:NO];
    wtd.storageMode = MTLStorageModePrivate;
    wtd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    id<MTLTexture> worldTex = [DEV_MTL(d) newTextureWithDescriptor:wtd];
    assert(worldTex);

    MTLTextureDescriptor *htd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:8 height:1 mipmapped:NO];
    htd.storageMode = MTLStorageModeShared;
    htd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    id<MTLTexture> hdrOut = [DEV_MTL(d) newTextureWithDescriptor:htd];
    assert(hdrOut);

    if (d->m4) {
        pm_retain(mainTex); pm_retain(depthTex); pm_retain(worldTex); pm_retain(hdrOut);
        PM_IF_M4(d) {
            id<MTLResidencySet> res = PM_OBJ(id<MTLResidencySet>, d->m4Residency);
            [res addAllocation:mainTex]; [res addAllocation:depthTex];
            [res addAllocation:worldTex]; [res addAllocation:hdrOut];
            [res commit];
        }
    }

    // 1. Native 100% scale:
    //    x=0..3: world sun (1,1,1,1)
    //    x=4: neutral non-highlight world (0.90,0.90,0.90,1)
    //    x=5: pure yellow (230/255, 230/255, 0)
    //    x=6: 1-step greener yellow (230/255, 231/255, 0) (tests smooth yellow edge)
    //    Leave wireframe=1 on the parked encoder before pm_clear_alpha to verify fill-mode override!
    uint64_t submit = 1;
    void *colors[] = { (__bridge void *)mainTex };
    int loadClear = 1, loadKeep = 0;
    float sun[] = { 1, 1, 1, 1 };
    assert(pm_pass_begin(d, 1, colors, &loadClear, sun, (__bridge void *)depthTex, 1, 0.0, 8, 1, 0, 0, 8, 1, "Main"));
    pm_pass_set_pipeline(d, (__bridge void *)worldPipe, NULL, 0, 0, 0, 0);
    float gray90[4] = { 0.90f, 0.90f, 0.90f, 1.0f };
    pm_pass_set_scissor(d, 4, 0, 1, 1);
    pm_pass_set_bytes(d, gray90, sizeof(gray90), 0);
    pm_pass_draw(d, 0, 3, 1, 0, 0);
    float yelA[4] = { 230.0f / 255.0f, 230.0f / 255.0f, 0.0f, 1.0f };
    pm_pass_set_scissor(d, 5, 0, 1, 1);
    pm_pass_set_bytes(d, yelA, sizeof(yelA), 0);
    pm_pass_draw(d, 0, 3, 1, 0, 0);
    float yelB[4] = { 230.0f / 255.0f, 231.0f / 255.0f, 0.0f, 1.0f };
    pm_pass_set_scissor(d, 6, 0, 1, 1);
    pm_pass_set_bytes(d, yelB, sizeof(yelB), 0);
    pm_pass_draw(d, 0, 3, 1, 0, 0);
    // Leave wireframe active on the parked encoder before pm_clear_alpha.
    pm_pass_set_pipeline(d, (__bridge void *)worldPipe, NULL, 0, 1, 0, 0);
    pm_pass_end(d);
    assert(d->parked != NULL);
    uint64_t encBefore = d->stats[0];
    pm_clear_alpha(d, colors[0]);
    assert(d->stats[0] == encBefore); // zero extra encoders: ran inside the parked world tile pass!

    assert(pm_pass_begin(d, 1, colors, &loadKeep, sun, (__bridge void *)depthTex, 1, 0.0, 8, 1, 0, 0, 8, 1, "GUI before blur"));
    pm_pass_set_pipeline(d, (__bridge void *)hudPipe, NULL, 0, 0, 0, 0);
    // x=1: opaque white HUD text over sun
    float whiteHud[4] = { 1, 1, 1, 1 };
    pm_pass_set_scissor(d, 1, 0, 1, 1);
    pm_pass_set_bytes(d, whiteHud, sizeof(whiteHud), 0);
    pm_pass_draw(d, 0, 3, 1, 0, 0);
    // x=2: 50% translucent white HUD edge over sun
    float halfWhite[4] = { 1, 1, 1, 0.5f };
    pm_pass_set_scissor(d, 2, 0, 1, 1);
    pm_pass_set_bytes(d, halfWhite, sizeof(halfWhite), 0);
    pm_pass_draw(d, 0, 3, 1, 0, 0);
    // x=3: 50% translucent black chat box over sun
    float halfBlack[4] = { 0, 0, 0, 0.5f };
    pm_pass_set_scissor(d, 3, 0, 1, 1);
    pm_pass_set_bytes(d, halfBlack, sizeof(halfBlack), 0);
    pm_pass_draw(d, 0, 3, 1, 0, 0);
    // x=4: 75% translucent white HUD over neutral 0.90 world (must NOT create a highlight!)
    float white75[4] = { 1, 1, 1, 0.75f };
    pm_pass_set_scissor(d, 4, 0, 1, 1);
    pm_pass_set_bytes(d, white75, sizeof(white75), 0);
    pm_pass_draw(d, 0, 3, 1, 0, 0);
    pm_pass_end(d);

    pm_submit(d, submit);
    assert(pm_wait(d, submit, 5000000000LL) && !pm_device_failed(d));
    submit++;

    run_present_hdr(d, mainTex, hdrOut, 2.0f, 2.0f);
    uint16_t px[8 * 4];
    [hdrOut getBytes:px bytesPerRow:8 * 8 fromRegion:MTLRegionMake2D(0, 0, 8, 1) mipmapLevel:0];
    float r0 = f16_to_f32(px[0 * 4]), r1 = f16_to_f32(px[1 * 4]), r2 = f16_to_f32(px[2 * 4]);
    float r3 = f16_to_f32(px[3 * 4]), r4 = f16_to_f32(px[4 * 4]), r5 = f16_to_f32(px[5 * 4]), r6 = f16_to_f32(px[6 * 4]);
    printf("PASS %s native-100: sun=%.3f opaqueHud=%.3f halfWhite=%.3f halfBlack=%.3f hud75OnGray90=%.3f yel230=%.3f yel231=%.3f\n",
           metal4 ? "Metal4" : "Metal3", r0, r1, r2, r3, r4, r5, r6);
    assert(fabsf(r0 - 2.0f) < 0.02f);       // pure world sun gets full 2.0x HDR
    assert(fabsf(r1 - 1.0f) < 0.01f);       // opaque white HUD over sun stays strictly 1.0 SDR!
    assert(fabsf(r2 - 1.5f) < 0.03f);       // 50% translucent white HUD over sun = exact 1.5 linear blend!
    assert(r3 > 0.20f && r3 < 0.50f);       // 50% dark chat over sun stays dark SDR, no blow-out!
    assert(r4 < 0.96f);                     // 75% white HUD over 0.90 gray world gets ZERO HDR boost (~0.945)!
    assert(r5 > 1.90f && fabsf(r5 - r6) < 0.15f); // smooth yellow edge, no discontinuity!

    // 2. Scaled <100% folded upscale + warm lava vs identical warm orange HUD icon + cap=1.0 identity check.
    void *wColors[] = { (__bridge void *)worldTex };
    float lava[] = { 1.0f, 0.65f, 0.0f, 1.0f };
    assert(pm_pass_begin(d, 1, wColors, &loadClear, lava, NULL, 0, 0.0, 4, 1, 0, 0, 4, 1, "Main scaled"));
    pm_pass_end(d);
    assert(pm_upscale(d, wColors[0], colors[0], 0, 0.2f) >= 0);
    assert(d->bgDst != NULL);
    pm_clear_alpha(d, colors[0]);
    assert(d->bgZeroAlpha == true); // folded into upscale!

    assert(pm_pass_begin(d, 1, colors, &loadKeep, sun, (__bridge void *)depthTex, 1, 0.0, 8, 1, 0, 0, 8, 1, "GUI before blur"));
    pm_pass_set_pipeline(d, (__bridge void *)hudPipe, NULL, 0, 0, 0, 0);
    pm_pass_set_scissor(d, 1, 0, 1, 1);
    pm_pass_set_bytes(d, lava, sizeof(lava), 0);
    pm_pass_draw(d, 0, 3, 1, 0, 0);
    pm_pass_end(d);

    pm_submit(d, submit);
    assert(pm_wait(d, submit, 5000000000LL) && !pm_device_failed(d));
    submit++;

    run_present_hdr(d, mainTex, hdrOut, 2.0f, 2.0f);
    [hdrOut getBytes:px bytesPerRow:8 * 8 fromRegion:MTLRegionMake2D(0, 0, 8, 1) mipmapLevel:0];
    float lavaWorldR = f16_to_f32(px[0 * 4]);
    float lavaHudR = f16_to_f32(px[1 * 4]);
    // Also verify cap=1.0 produces exact SDR identity even where worldWeight == 1.
    run_present_hdr(d, mainTex, hdrOut, 1.0f, 2.0f);
    [hdrOut getBytes:px bytesPerRow:8 * 8 fromRegion:MTLRegionMake2D(0, 0, 8, 1) mipmapLevel:0];
    float lavaCap1R = f16_to_f32(px[0 * 4]);
    printf("PASS %s scaled-upscale: lavaWorldR=%.3f lavaHudR=%.3f lavaCap1R=%.3f\n",
           metal4 ? "Metal4" : "Metal3", lavaWorldR, lavaHudR, lavaCap1R);
    assert(lavaWorldR > 1.90f);               // warm world lava boosted to full HDR peak
    assert(fabsf(lavaHudR - 1.0f) < 0.01f);   // identical warm HUD icon stays strictly SDR 1.0!
    assert(fabsf(lavaCap1R - 1.0f) < 0.01f);  // cap=1.0 stays strictly SDR identity!

    // 3. Fallback path (no parked encoder on mainTex): verify RGB preserved and alpha encodes (1 - worldWeight).
    assert(pm_pass_begin(d, 1, colors, &loadClear, lava, NULL, 0, 0.0, 8, 1, 0, 0, 8, 1, "Post"));
    pm_pass_end(d);
    end_parked(d);
    pm_clear_alpha(d, colors[0]);
    void *readback = pm_buffer_create(d, 64);
    assert(readback);
    pm_copy_texture_to_buffer(d, colors[0], 0, 0, 0, 0, 8, 1, readback, 0, 32);
    pm_submit(d, submit);
    assert(pm_wait(d, submit, 5000000000LL) && !pm_device_failed(d));
    const uint8_t *rb = pm_buffer_contents(readback);
    for (int x = 0; x < 8; x++) {
        assert(rb[x * 4 + 0] == 255 && rb[x * 4 + 1] >= 165 && rb[x * 4 + 1] <= 167 && rb[x * 4 + 2] == 0 && rb[x * 4 + 3] == 0);
    }
    printf("PASS %s fallback-alpha-mask: RGB untouched, alpha=(1-w)=0 across lava pixels\n", metal4 ? "Metal4" : "Metal3");
    pm_release(readback);
    if (d->m4) {
        pm_release((__bridge void *)mainTex); pm_release((__bridge void *)depthTex);
        pm_release((__bridge void *)worldTex); pm_release((__bridge void *)hdrOut);
    }
    pm_device_destroy(d);
}

int main(void) { @autoreleasepool {
    if (!MTLCreateSystemDefaultDevice()) { printf("SKIP: no Metal device\n"); return 0; }
    test_backend(0);
    test_backend(1);
    printf("ALL HDR HUD MASK TESTS PASSED\n");
    return 0;
} }
