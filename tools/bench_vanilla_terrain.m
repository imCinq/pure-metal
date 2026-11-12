// Headless GPU benchmark and pixel check: Minecraft's own core/terrain shader (the one Pure Metal draws terrain with, also under
// Sodium) compiled with Safe math (as shipped) vs Relaxed math. No Java, no game, no window.
//
//   clang -fobjc-arc -O2 -Wall -Wextra -framework Metal -framework Foundation tools/bench_vanilla_terrain.m -o build/bench_vanilla_terrain
//   build/bench_vanilla_terrain VMSL_DIR [fly|ground] [solid|cutout|translucent] [frames] [width height]
//
// VMSL_DIR holds the translations written by `tools/DumpTerrainMsl.java ... vanilla` (vsolid-eo-vertex.metal, ...); every shader
// includes the bit identical RGSS early-out, like the shipped pipelines. The scene is a heightfield (one indexed draw, vanilla
// vertex layout with MULTIDRAW_TERRAIN attributes, fog, 1024^2 atlas with mips, lightmap) rendered into RGBA8 + D32 like the main pass.
// Variants: A Safe vertex + Safe fragment (reference), B Safe vertex + Relaxed fragment (what FastTerrainMath ships), C everything
// Relaxed (what -Dpuremetal.safeMath=false does). Reported per variant and per u_UseRgss setting: median GPU ms (ABBA interleaved)
// and the difference to A: max per channel diff, number of differing pixels, depth differences (a changed cutout discard shows
// up as a depth difference, so that count is the number of discard flips).
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define PM_VERTEX_BUFFER 16
enum { SOLID = 0, CUTOUT = 1, TRANSLUCENT = 2 };

typedef struct { int32_t cameraBlockPos[3]; float glintAlpha; float cameraOffset[3]; float gameTime; float screenSize[2]; int32_t menuBlur; int32_t useRgss; } Globals; // 48
typedef struct { float proj[16]; } Projection;
typedef struct { float mv[16]; int32_t texSize[2]; int32_t pad[2]; } TerrainUniform; // 80
typedef struct { float color[4]; float envStart, envEnd, rdStart, rdEnd, skyEnd, cloudsEnd; float pad[2]; } Fog; // 48
typedef struct { float pos[3]; uint8_t color[4]; float uv[2]; int32_t uv2[2]; int32_t chunk[3]; float visibility; } Vertex; // 48
_Static_assert(sizeof(Globals) == 48 && sizeof(TerrainUniform) == 80 && sizeof(Fog) == 48 && sizeof(Vertex) == 48, "layouts");

static uint32_t rngState = 12345u;
static uint32_t rnd(void) { rngState = rngState * 1664525u + 1013904223u; return rngState >> 8; }
static float frand(void) { return (float)(rnd() & 0xFFFF) / 65536.0f; }

static float terrainHeight(int x, int z) {
    float h = 20.0f + 8.0f * sinf(x * 0.045f) * cosf(z * 0.037f) + 5.0f * sinf((x + z) * 0.11f) + 3.0f * sinf(x * 0.23f) * sinf(z * 0.19f);
    h = floorf(h);
    return h < 2 ? 2 : (h > 46 ? 46 : h);
}

static id<MTLTexture> makeAtlas(id<MTLDevice> dev, id<MTLCommandQueue> q, int kind) {
    const int size = 1024, levels = 5;
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:size height:size mipmapped:NO];
    td.mipmapLevelCount = levels; td.storageMode = MTLStorageModeShared; td.usage = MTLTextureUsageShaderRead;
    id<MTLTexture> staging = [dev newTextureWithDescriptor:td];
    td.storageMode = MTLStorageModePrivate;
    id<MTLTexture> atlas = [dev newTextureWithDescriptor:td];
    uint8_t *prev = NULL; int prevSize = 0;
    for (int level = 0; level < levels; level++) {
        int s = size >> level; uint8_t *px = malloc((size_t)s * s * 4);
        if (level == 0) {
            for (int ty = 0; ty < 64; ty++) for (int tx = 0; tx < 64; tx++) {
                float br = 0.25f + 0.6f * frand(), bg = 0.25f + 0.6f * frand(), bb = 0.2f + 0.6f * frand();
                BOOL holes = kind == CUTOUT && (rnd() % 10) < 4;
                uint8_t tileAlpha = kind == TRANSLUCENT ? (uint8_t)(90 + rnd() % 150) : 255;
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    float n = 0.75f + 0.25f * frand(); uint8_t *p = px + (((size_t)(ty * 16 + y)) * s + tx * 16 + x) * 4;
                    p[0] = (uint8_t)(255.0f * fminf(1.0f, br * n)); p[1] = (uint8_t)(255.0f * fminf(1.0f, bg * n));
                    p[2] = (uint8_t)(255.0f * fminf(1.0f, bb * n)); p[3] = (holes && frand() < 0.35f) ? 0 : tileAlpha;
                }
            }
        } else {
            for (int y = 0; y < s; y++) for (int x = 0; x < s; x++) for (int c = 0; c < 4; c++) {
                int sum = prev[((2 * y) * prevSize + 2 * x) * 4 + c] + prev[((2 * y) * prevSize + 2 * x + 1) * 4 + c]
                        + prev[((2 * y + 1) * prevSize + 2 * x) * 4 + c] + prev[((2 * y + 1) * prevSize + 2 * x + 1) * 4 + c];
                px[(y * s + x) * 4 + c] = (uint8_t)((sum + 2) / 4);
            }
        }
        [staging replaceRegion:MTLRegionMake2D(0, 0, s, s) mipmapLevel:level withBytes:px bytesPerRow:(NSUInteger)s * 4];
        free(prev); prev = px; prevSize = s;
    }
    free(prev);
    id<MTLCommandBuffer> cb = [q commandBuffer]; id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    [blit copyFromTexture:staging toTexture:atlas]; [blit endEncoding]; [cb commit]; [cb waitUntilCompleted];
    return atlas;
}

static id<MTLTexture> makeLightmap(id<MTLDevice> dev) {
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
    td.storageMode = MTLStorageModeShared; td.usage = MTLTextureUsageShaderRead;
    id<MTLTexture> tex = [dev newTextureWithDescriptor:td]; uint8_t px[16 * 16 * 4];
    for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
        float sky = 0.35f + 0.65f * y / 15.0f, blk = 0.25f + 0.75f * x / 15.0f, l = fmaxf(sky, blk);
        uint8_t *p = px + (y * 16 + x) * 4; p[0] = (uint8_t)(255 * l); p[1] = (uint8_t)(255 * l); p[2] = (uint8_t)(255 * fminf(1.0f, l * 1.05f)); p[3] = 255;
    }
    [tex replaceRegion:MTLRegionMake2D(0, 0, 16, 16) mipmapLevel:0 withBytes:px bytesPerRow:64];
    return tex;
}

static id<MTLLibrary> library(id<MTLDevice> dev, NSString *source, BOOL safe, NSString *what) {
    MTLCompileOptions *o = [MTLCompileOptions new];
    o.languageVersion = MTLLanguageVersion3_0; o.preserveInvariance = YES; // as pm_library_create
    if (@available(macOS 15.0, *)) o.mathMode = safe ? MTLMathModeSafe : MTLMathModeRelaxed;
    NSError *e = nil; id<MTLLibrary> lib = [dev newLibraryWithSource:source options:o error:&e];
    if (!lib) { fprintf(stderr, "compile %s failed: %s\n", what.UTF8String, e.localizedDescription.UTF8String); exit(2); }
    return lib;
}

static id<MTLFunction> function(id<MTLLibrary> lib, BOOL earlyOutConstant) {
    if (!earlyOutConstant) return [lib newFunctionWithName:@"main0"];
    MTLFunctionConstantValues *cv = [MTLFunctionConstantValues new]; BOOL one = YES;
    [cv setConstantValue:&one type:MTLDataTypeBool atIndex:77];
    NSError *e = nil; id<MTLFunction> f = [lib newFunctionWithName:@"main0" constantValues:cv error:&e];
    if (!f) { fprintf(stderr, "function: %s\n", e.localizedDescription.UTF8String); exit(2); }
    return f;
}

static id<MTLRenderPipelineState> pipeline(id<MTLDevice> dev, id<MTLFunction> vs, id<MTLFunction> fs, NSString *label, BOOL blend) {
    MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
    pd.label = label; pd.vertexFunction = vs; pd.fragmentFunction = fs;
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm; pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    if (blend) {
        pd.colorAttachments[0].blendingEnabled = YES;
        pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha; pd.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
        pd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne; pd.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    }
    MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
    vd.layouts[PM_VERTEX_BUFFER].stride = sizeof(Vertex); vd.layouts[PM_VERTEX_BUFFER].stepFunction = MTLVertexStepFunctionPerVertex;
    MTLVertexFormat formats[6] = {MTLVertexFormatFloat3, MTLVertexFormatUChar4Normalized, MTLVertexFormatFloat2, MTLVertexFormatInt2, MTLVertexFormatInt3, MTLVertexFormatFloat};
    NSUInteger offsets[6] = {0, 12, 16, 24, 32, 44};
    for (int i = 0; i < 6; i++) { vd.attributes[i].format = formats[i]; vd.attributes[i].offset = offsets[i]; vd.attributes[i].bufferIndex = PM_VERTEX_BUFFER; }
    pd.vertexDescriptor = vd;
    NSError *e = nil; id<MTLRenderPipelineState> s = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
    if (!s) { fprintf(stderr, "pipeline %s: %s\n", label.UTF8String, e.localizedDescription.UTF8String); exit(2); }
    return s;
}

static NSString *readFile(NSString *path) {
    NSError *e = nil; NSString *s = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:&e];
    if (!s) { fprintf(stderr, "cannot read %s\n", path.UTF8String); exit(2); }
    return s;
}

static int cmpDouble(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return (x > y) - (x < y); }

int main(int argc, char **argv) { @autoreleasepool {
    if (argc < 2) { fprintf(stderr, "usage: bench_vanilla_terrain VMSL_DIR [fly|ground] [solid|cutout|translucent] [frames] [width height]\n"); return 1; }
    NSString *dir = [NSString stringWithUTF8String:argv[1]];
    BOOL fly = argc < 3 || strcmp(argv[2], "ground") != 0;
    int kind = argc > 3 && strcmp(argv[3], "cutout") == 0 ? CUTOUT : (argc > 3 && strcmp(argv[3], "translucent") == 0 ? TRANSLUCENT : SOLID);
    int frames = argc > 4 ? atoi(argv[4]) : 40;
    int width = argc > 6 ? atoi(argv[5]) : 3420, height = argc > 6 ? atoi(argv[6]) : 2148;
    const char *kindName[3] = {"solid", "cutout", "translucent"};
    id<MTLDevice> dev = MTLCreateSystemDefaultDevice(); id<MTLCommandQueue> queue = [dev newCommandQueue];
    printf("device %s, %dx%d, scene %s, pass %s, %d frames per variant\n", dev.name.UTF8String, width, height, fly ? "fly" : "ground", kindName[kind], frames);

    // Geometry: top face on every surface block plus side faces that stick out above a lower neighbour ----------------------
    const int N = 448;
    int tileCount = getenv("PM_TILES") ? atoi(getenv("PM_TILES")) : 256; if (tileCount < 1 || tileCount > 4096) tileCount = 256;
    NSMutableData *vertices = [NSMutableData new];
    uint32_t quadCount = 0;
    for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) {
        int h = (int)terrainHeight(x, z);
        int n[4] = {x + 1 < N ? (int)terrainHeight(x + 1, z) : h, x > 0 ? (int)terrainHeight(x - 1, z) : h,
                    z + 1 < N ? (int)terrainHeight(x, z + 1) : h, z > 0 ? (int)terrainHeight(x, z - 1) : h};
        for (int dir = 0; dir < 5; dir++) {
            int lowest = dir == 0 ? h - 1 : (n[dir - 1] > h - 6 ? n[dir - 1] : h - 6), highest = h - 1;
            if (lowest < 0) lowest = 0;
            for (int y = lowest; y <= highest; y++) {
                if (dir > 0 && y < n[dir - 1]) continue;
                float x0 = (float)x, y0 = (float)y, z0 = (float)z;
                float c[4][3];
                switch (dir) {
                    case 0: { float cc[4][3] = {{x0, y0 + 1, z0}, {x0 + 1, y0 + 1, z0}, {x0 + 1, y0 + 1, z0 + 1}, {x0, y0 + 1, z0 + 1}}; memcpy(c, cc, sizeof c); break; }
                    case 1: { float cc[4][3] = {{x0 + 1, y0, z0}, {x0 + 1, y0, z0 + 1}, {x0 + 1, y0 + 1, z0 + 1}, {x0 + 1, y0 + 1, z0}}; memcpy(c, cc, sizeof c); break; }
                    case 2: { float cc[4][3] = {{x0, y0, z0 + 1}, {x0, y0, z0}, {x0, y0 + 1, z0}, {x0, y0 + 1, z0 + 1}}; memcpy(c, cc, sizeof c); break; }
                    case 3: { float cc[4][3] = {{x0 + 1, y0, z0 + 1}, {x0, y0, z0 + 1}, {x0, y0 + 1, z0 + 1}, {x0 + 1, y0 + 1, z0 + 1}}; memcpy(c, cc, sizeof c); break; }
                    default: { float cc[4][3] = {{x0, y0, z0}, {x0 + 1, y0, z0}, {x0 + 1, y0 + 1, z0}, {x0, y0 + 1, z0}}; memcpy(c, cc, sizeof c); break; }
                }
                int tile = (int)(rnd() % tileCount), tx = tile % 64, ty = tile / 64;
                float us[4] = {tx * 16.0f, (tx + 1) * 16.0f, (tx + 1) * 16.0f, tx * 16.0f}, vs[4] = {(ty + 1) * 16.0f, (ty + 1) * 16.0f, ty * 16.0f, ty * 16.0f};
                float shade = dir == 0 ? 1.0f : (dir < 3 ? 0.8f : 0.65f); shade *= 0.8f + 0.2f * frand();
                int light0 = (int)(rnd() & 0xF0) | 0x0F, light1 = (int)(rnd() & 0xF0) | 0x0F;
                int origin[3] = {x / 16 * 16, (y / 16) * 16, z / 16 * 16};
                for (int j = 0; j < 4; j++) {
                    Vertex v; memset(&v, 0, sizeof v);
                    for (int k = 0; k < 3; k++) { v.pos[k] = c[j][k] - (float)origin[k]; v.chunk[k] = origin[k]; }
                    v.color[0] = v.color[1] = v.color[2] = (uint8_t)(255 * shade); v.color[3] = 255;
                    v.uv[0] = us[j] / 1024.0f; v.uv[1] = vs[j] / 1024.0f; v.uv2[0] = light0; v.uv2[1] = light1; v.visibility = 1.0f;
                    [vertices appendBytes:&v length:sizeof v];
                }
                quadCount++;
                if (dir == 0) break;
            }
        }
    }
    printf("fixture: %u vertices, %u quads\n", quadCount * 4, quadCount);
    id<MTLBuffer> vbuf = [dev newBufferWithBytes:vertices.bytes length:vertices.length options:MTLResourceStorageModeShared];
    uint32_t *indices = malloc((size_t)quadCount * 6 * 4);
    for (uint32_t q = 0; q < quadCount; q++) { uint32_t b = q * 4, *p = indices + q * 6; p[0] = b; p[1] = b + 1; p[2] = b + 2; p[3] = b; p[4] = b + 2; p[5] = b + 3; }
    id<MTLBuffer> ibuf = [dev newBufferWithBytes:indices length:(size_t)quadCount * 6 * 4 options:MTLResourceStorageModeShared]; free(indices);

    id<MTLTexture> atlas = makeAtlas(dev, queue, kind), light = makeLightmap(dev);
    MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
    sd.minFilter = MTLSamplerMinMagFilterLinear; sd.magFilter = MTLSamplerMinMagFilterLinear; sd.mipFilter = MTLSamplerMipFilterLinear;
    sd.sAddressMode = sd.tAddressMode = MTLSamplerAddressModeClampToEdge; sd.lodMaxClamp = 4;
    id<MTLSamplerState> blockSampler = [dev newSamplerStateWithDescriptor:sd];
    sd.mipFilter = MTLSamplerMipFilterNotMipmapped;
    id<MTLSamplerState> lightSampler = [dev newSamplerStateWithDescriptor:sd];

    // Camera and uniforms --------------------------------------------------------------------------------------------------
    float cam[3] = {N * 0.5f + 0.3f, fly ? 90.0f : 26.0f, N * 0.5f + 0.7f}, yaw = 0.45f, pitch = (fly ? 40.0f : 6.0f) * (float)M_PI / 180.0f;
    float fovY = 70.0f * (float)M_PI / 180.0f, aspect = (float)width / height, nearZ = 0.05f, farZ = 1024.0f;
    float fwd[3] = {sinf(yaw) * cosf(pitch), -sinf(pitch), -cosf(yaw) * cosf(pitch)};
    float right[3] = {-fwd[2], 0, fwd[0]}; float rl = sqrtf(right[0] * right[0] + right[2] * right[2]); right[0] /= rl; right[2] /= rl;
    float up[3] = {right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0]};
    Projection proj; memset(&proj, 0, sizeof proj);
    float f = 1.0f / tanf(fovY * 0.5f);
    proj.proj[0] = f / aspect; proj.proj[5] = f; proj.proj[10] = farZ / (nearZ - farZ); proj.proj[11] = -1; proj.proj[14] = nearZ * farZ / (nearZ - farZ);
    TerrainUniform tu; memset(&tu, 0, sizeof tu);
    float rot[3][3] = {{right[0], right[1], right[2]}, {up[0], up[1], up[2]}, {-fwd[0], -fwd[1], -fwd[2]}};
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) tu.mv[c * 4 + r] = rot[r][c];
    tu.mv[15] = 1; tu.texSize[0] = tu.texSize[1] = 1024;
    Globals g; memset(&g, 0, sizeof g);
    for (int i = 0; i < 3; i++) { g.cameraBlockPos[i] = (int32_t)floorf(cam[i]); g.cameraOffset[i] = cam[i] - floorf(cam[i]); }
    g.screenSize[0] = (float)width; g.screenSize[1] = (float)height;
    Fog fog; memset(&fog, 0, sizeof fog);
    fog.color[0] = 0.70f; fog.color[1] = 0.80f; fog.color[2] = 1.0f; fog.color[3] = 1.0f;
    fog.envStart = 1.0e6f; fog.envEnd = 1.0e6f + 1.0f; fog.rdStart = 0.8f * 224.0f; fog.rdEnd = 224.0f; fog.skyEnd = 224.0f; fog.cloudsEnd = 400.0f;

    MTLTextureDescriptor *cd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
    cd.storageMode = MTLStorageModePrivate; cd.usage = MTLTextureUsageRenderTarget;
    id<MTLTexture> color = [dev newTextureWithDescriptor:cd];
    MTLTextureDescriptor *dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:width height:height mipmapped:NO];
    dd.storageMode = MTLStorageModePrivate; dd.usage = MTLTextureUsageRenderTarget;
    id<MTLTexture> depth = [dev newTextureWithDescriptor:dd];
    MTLDepthStencilDescriptor *dsd = [MTLDepthStencilDescriptor new]; dsd.depthCompareFunction = MTLCompareFunctionLess; dsd.depthWriteEnabled = kind != TRANSLUCENT;
    id<MTLDepthStencilState> depthState = [dev newDepthStencilStateWithDescriptor:dsd];
    NSUInteger pixels = (NSUInteger)width * height;
    id<MTLBuffer> colorOut = [dev newBufferWithLength:pixels * 4 options:MTLResourceStorageModeShared];
    id<MTLBuffer> depthOut = [dev newBufferWithLength:pixels * 4 options:MTLResourceStorageModeShared];

    // Variants --------------------------------------------------------------------------------------------------------------
    NSString *base = kind == CUTOUT ? @"vcutout-eo" : @"vsolid-eo"; // translucent uses the solid shader, like the translucent pipeline (no ALPHA_CUTOUT)
    NSString *vsSource = readFile([dir stringByAppendingPathComponent:[base stringByAppendingString:@"-vertex.metal"]]);
    NSString *fsSource = readFile([dir stringByAppendingPathComponent:[base stringByAppendingString:@"-fragment.metal"]]);
    NSMutableArray<NSString *> *names = [NSMutableArray new]; NSMutableArray<id<MTLRenderPipelineState>> *states = [NSMutableArray new];
    void (^add)(NSString *, BOOL, BOOL) = ^(NSString *name, BOOL safeVertex, BOOL safeFragment) {
        id<MTLLibrary> lv = library(dev, vsSource, safeVertex, name), lf = library(dev, fsSource, safeFragment, name);
        [names addObject:name]; [states addObject:pipeline(dev, function(lv, NO), function(lf, YES), name, kind == TRANSLUCENT)];
    };
    add(@"A safe vertex + safe fragment (shipped)", YES, YES);
    add(@"B safe vertex + RELAXED fragment (FastTerrainMath)", YES, NO);
    add(@"C all RELAXED (safeMath=false)", NO, NO);

    int rgssSettings[2] = {1, 0};
    for (int s = 0; s < 2; s++) {
        g.useRgss = rgssSettings[s];
        printf("\n=== UseRgss = %d ===\n", g.useRgss);
        void (^render)(NSUInteger, BOOL, double *) = ^(NSUInteger variant, BOOL readback, double *ms) {
            id<MTLCommandBuffer> cb = [queue commandBuffer];
            MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
            rp.colorAttachments[0].texture = color; rp.colorAttachments[0].loadAction = MTLLoadActionClear;
            rp.colorAttachments[0].clearColor = MTLClearColorMake(0.55, 0.7, 1.0, 1.0); rp.colorAttachments[0].storeAction = MTLStoreActionStore;
            rp.depthAttachment.texture = depth; rp.depthAttachment.loadAction = MTLLoadActionClear; rp.depthAttachment.clearDepth = 1.0;
            rp.depthAttachment.storeAction = MTLStoreActionStore;
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
            [enc setRenderPipelineState:states[variant]]; [enc setDepthStencilState:depthState]; [enc setCullMode:MTLCullModeNone];
            // Vertex: Projection 1, TerrainUniform 2, Globals 3, lightmap texture/sampler 1. Fragment: Globals 0, TerrainUniform 2, Fog 3, atlas texture/sampler 0.
            [enc setVertexBytes:&proj length:sizeof proj atIndex:1]; [enc setVertexBytes:&tu length:sizeof tu atIndex:2]; [enc setVertexBytes:&g length:sizeof g atIndex:3];
            [enc setVertexTexture:light atIndex:1]; [enc setVertexSamplerState:lightSampler atIndex:1];
            [enc setFragmentBytes:&g length:sizeof g atIndex:0]; [enc setFragmentBytes:&tu length:sizeof tu atIndex:2]; [enc setFragmentBytes:&fog length:sizeof fog atIndex:3];
            [enc setFragmentTexture:atlas atIndex:0]; [enc setFragmentSamplerState:blockSampler atIndex:0];
            [enc setVertexBuffer:vbuf offset:0 atIndex:PM_VERTEX_BUFFER];
            [enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:quadCount * 6 indexType:MTLIndexTypeUInt32 indexBuffer:ibuf indexBufferOffset:0];
            [enc endEncoding];
            if (readback) {
                id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
                [blit copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(width, height, 1)
                    toBuffer:colorOut destinationOffset:0 destinationBytesPerRow:(NSUInteger)width * 4 destinationBytesPerImage:pixels * 4];
                [blit copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(width, height, 1)
                    toBuffer:depthOut destinationOffset:0 destinationBytesPerRow:(NSUInteger)width * 4 destinationBytesPerImage:pixels * 4 options:MTLBlitOptionDepthFromDepthStencil];
                [blit endEncoding];
            }
            [cb commit]; [cb waitUntilCompleted];
            if (cb.status != MTLCommandBufferStatusCompleted) { fprintf(stderr, "command buffer failed: %s\n", cb.error.description.UTF8String); exit(3); }
            if (ms) *ms = (cb.GPUEndTime - cb.GPUStartTime) * 1000.0;
        };
        NSUInteger count = names.count;
        uint8_t *refColor = malloc(pixels * 4); uint32_t *refDepth = malloc(pixels * 4);
        double *samples = calloc(count * (size_t)frames, sizeof(double)); int *taken = calloc(count, sizeof(int));
        for (NSUInteger v = 0; v < count; v++) {
            render(v, YES, NULL);
            const uint8_t *c = colorOut.contents; const uint32_t *dp = depthOut.contents;
            if (v == 0) { memcpy(refColor, c, pixels * 4); memcpy(refDepth, dp, pixels * 4); }
            int maxDiff = 0; NSUInteger pixelsDiffer = 0, pixelsOver1 = 0, depthDiffer = 0, depthDiffBig = 0; float maxDepth = 0;
            for (NSUInteger i = 0; i < pixels; i++) {
                int pd = 0; for (int k = 0; k < 4; k++) { int d = abs((int)c[i * 4 + k] - (int)refColor[i * 4 + k]); if (d > pd) pd = d; }
                if (pd > maxDiff) maxDiff = pd; if (pd > 0) pixelsDiffer++; if (pd > 1) pixelsOver1++;
                if (dp[i] != refDepth[i]) {
                    depthDiffer++; float a, b; memcpy(&a, &dp[i], 4); memcpy(&b, &refDepth[i], 4);
                    float d = fabsf(a - b); if (d > maxDepth) maxDepth = d; if (d > 1e-6f) depthDiffBig++; // 1e-6 is far below one depth step of any real surface gap
                }
            }
            printf("  pixels vs A: %-52s max channel diff %3d/255, pixels differing %lu (%.4f%%), >1/255: %lu, depth differing %lu (max %.3g, >1e-6: %lu = discard flips)\n",
                names[v].UTF8String, maxDiff, (unsigned long)pixelsDiffer, 100.0 * pixelsDiffer / pixels, (unsigned long)pixelsOver1, (unsigned long)depthDiffer, maxDepth, (unsigned long)depthDiffBig);
        }
        for (int round = -2; round < frames; round++) {
            for (NSUInteger k = 0; k < count; k++) {
                NSUInteger v = (round & 1) ? count - 1 - k : k; double ms = 0;
                @autoreleasepool { render(v, NO, &ms); }
                if (round >= 0) samples[v * (size_t)frames + (size_t)taken[v]++] = ms;
            }
        }
        double reference = 0;
        for (NSUInteger v = 0; v < count; v++) {
            double *a = samples + v * (size_t)frames; qsort(a, (size_t)frames, sizeof(double), cmpDouble);
            double median = (frames & 1) ? a[frames / 2] : 0.5 * (a[frames / 2 - 1] + a[frames / 2]);
            if (v == 0) reference = median;
            printf("  %-52s median %7.3f ms  [p10 %.3f, p90 %.3f]  vs A %+6.1f%%\n", names[v].UTF8String, median, a[frames / 10], a[(frames * 9) / 10], 100.0 * (median - reference) / reference);
        }
        free(refColor); free(refDepth); free(samples); free(taken);
    }
    printf("\nTimes are GPUEndTime - GPUStartTime of one command buffer holding one full main pass (colour + depth, one draw).\n");
    return 0;
} }
