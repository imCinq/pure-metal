// Headless GPU benchmark: SPIRV-Cross translation of Sodium's terrain shader vs the hand written MSL
// (src/main/resources/assets/puremetal/shaders/sodium_terrain.metal). No Java, no game, no window.
//
//   clang -fobjc-arc -O2 -Wall -Wextra -framework Metal -framework Foundation tools/bench_terrain_shader.m -o build/bench_terrain_shader
//   build/bench_terrain_shader TRANSLATED_DIR [fly|ground] [solid|cutout] [frames] [width height]
//
// TRANSLATED_DIR holds the translations written by tools/DumpTerrainMsl.java (solid-fog-vertex.metal, ...). The scene is a
// Sodium format heightfield (20 byte vertices, one draw per 16^3 section, 8x4x8 sections per region, fog, fade-in sections)
// seen from the air ("fly", like the benchmark flight) or from the ground, rendered into RGBA8 + D32 like the game's main pass.
// Reported per variant: median GPU ms per frame (ABBA interleaved) and the difference to the translated Safe math image.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define PM_VERTEX_BUFFER 16
static const int kRegionBlocks = 128;

typedef struct { float proj[16], mv[16], fogColor[4], camera[4], env[2], render[2], texel[2], shrink[2], fadeInv; uint32_t rgss; } Globals;
typedef struct { float region[3]; int32_t time; uint32_t regionId; } Push;
typedef struct { uint32_t region, draw, first, quads; } Draw;

static uint32_t rngState = 12345u;
static uint32_t rnd(void) { rngState = rngState * 1664525u + 1013904223u; return rngState >> 8; }
static float frand(void) { return (float)(rnd() & 0xFFFF) / 65536.0f; }

static float terrainHeight(int x, int z) {
    float h = 20.0f + 8.0f * sinf(x * 0.045f) * cosf(z * 0.037f) + 5.0f * sinf((x + z) * 0.11f) + 3.0f * sinf(x * 0.23f) * sinf(z * 0.19f);
    h = floorf(h);
    return h < 2 ? 2 : (h > 46 ? 46 : h);
}

typedef struct { uint32_t pos[2]; uint8_t color[4]; uint16_t uv[2]; uint8_t light[4]; } Vertex; // 20 bytes, like Sodium's
_Static_assert(sizeof(Vertex) == 20, "vertex layout");

static void pack(Vertex *v, float lx, float ly, float lz, uint32_t u, uint32_t vv, const uint8_t color[4], uint8_t l0, uint8_t l1, uint32_t draw) {
    uint32_t p[3] = {(uint32_t)((lx + 8.0f) * 32768.0f), (uint32_t)((ly + 8.0f) * 32768.0f), (uint32_t)((lz + 8.0f) * 32768.0f)};
    uint32_t hi = 0, lo = 0;
    for (int i = 0; i < 3; i++) { hi |= ((p[i] >> 10) & 0x3FF) << (10 * i); lo |= (p[i] & 0x3FF) << (10 * i); }
    v->pos[0] = hi; v->pos[1] = lo;
    memcpy(v->color, color, 4);
    v->uv[0] = (uint16_t)u; v->uv[1] = (uint16_t)vv;
    v->light[0] = l0; v->light[1] = l1; v->light[2] = 0; v->light[3] = (uint8_t)draw;
}

// Texture helpers --------------------------------------------------------------------------------------------------
static id<MTLTexture> makeAtlas(id<MTLDevice> dev, id<MTLCommandQueue> q, BOOL cutout) {
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
                BOOL holes = cutout && (rnd() % 10) < 4;
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    float n = 0.75f + 0.25f * frand(); uint8_t *p = px + (((size_t)(ty * 16 + y)) * s + tx * 16 + x) * 4;
                    p[0] = (uint8_t)(255.0f * fminf(1.0f, br * n)); p[1] = (uint8_t)(255.0f * fminf(1.0f, bg * n));
                    p[2] = (uint8_t)(255.0f * fminf(1.0f, bb * n)); p[3] = (holes && frand() < 0.35f) ? 0 : 255;
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

// Shader compilation -----------------------------------------------------------------------------------------------
typedef struct { NSString *name; id<MTLRenderPipelineState> state; } Variant;

static id<MTLLibrary> library(id<MTLDevice> dev, NSString *source, BOOL safe, NSString *what) {
    MTLCompileOptions *o = [MTLCompileOptions new];
    o.languageVersion = MTLLanguageVersion3_0; o.preserveInvariance = YES; // as pm_library_create
    if (@available(macOS 15.0, *)) o.mathMode = safe ? MTLMathModeSafe : MTLMathModeRelaxed;
    NSError *e = nil; id<MTLLibrary> lib = [dev newLibraryWithSource:source options:o error:&e];
    if (!lib) { fprintf(stderr, "compile %s failed: %s\n", what.UTF8String, e.localizedDescription.UTF8String); exit(2); }
    return lib;
}

static id<MTLFunction> function(id<MTLLibrary> lib, NSString *name, BOOL earlyOutConstant) {
    if (!earlyOutConstant) return [lib newFunctionWithName:name];
    MTLFunctionConstantValues *cv = [MTLFunctionConstantValues new]; BOOL one = YES;
    [cv setConstantValue:&one type:MTLDataTypeBool atIndex:77];
    NSError *e = nil; id<MTLFunction> f = [lib newFunctionWithName:name constantValues:cv error:&e];
    if (!f) { fprintf(stderr, "function %s: %s\n", name.UTF8String, e.localizedDescription.UTF8String); exit(2); }
    return f;
}

static id<MTLRenderPipelineState> pipeline(id<MTLDevice> dev, id<MTLFunction> vs, id<MTLFunction> fs, NSString *label) {
    MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
    pd.label = label; pd.vertexFunction = vs; pd.fragmentFunction = fs;
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm; pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
    vd.layouts[PM_VERTEX_BUFFER].stride = sizeof(Vertex); vd.layouts[PM_VERTEX_BUFFER].stepFunction = MTLVertexStepFunctionPerVertex;
    MTLVertexFormat formats[4] = {MTLVertexFormatUInt2, MTLVertexFormatUChar4Normalized, MTLVertexFormatUShort2, MTLVertexFormatUChar4};
    NSUInteger offsets[4] = {0, 8, 12, 16};
    for (int i = 0; i < 4; i++) { vd.attributes[i].format = formats[i]; vd.attributes[i].offset = offsets[i]; vd.attributes[i].bufferIndex = PM_VERTEX_BUFFER; }
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
    if (argc < 2) { fprintf(stderr, "usage: bench_terrain_shader TRANSLATED_DIR [fly|ground] [solid|cutout] [frames] [width height]\n"); return 1; }
    NSString *dir = [NSString stringWithUTF8String:argv[1]];
    BOOL fly = argc < 3 || strcmp(argv[2], "ground") != 0;
    BOOL cutout = argc > 3 && strcmp(argv[3], "cutout") == 0;
    int frames = argc > 4 ? atoi(argv[4]) : 40;
    int width = argc > 6 ? atoi(argv[5]) : 3420, height = argc > 6 ? atoi(argv[6]) : 2148;
    id<MTLDevice> dev = MTLCreateSystemDefaultDevice(); id<MTLCommandQueue> queue = [dev newCommandQueue];
    printf("device %s, %dx%d, scene %s, pass %s, %d frames per variant\n", dev.name.UTF8String, width, height, fly ? "fly" : "ground", cutout ? "cutout" : "solid", frames);

    // Geometry --------------------------------------------------------------------------------------------------
    const int N = 448; // blocks per side (28 chunks), like a 14 chunk render distance
    // Distinct block textures in use (PM_TILES, 1..4096); more of them means a bigger texture cache working set.
    int tileCount = getenv("PM_TILES") ? atoi(getenv("PM_TILES")) : 256; if (tileCount < 1 || tileCount > 4096) tileCount = 256;
    int regionsPerSide = (N + kRegionBlocks - 1) / kRegionBlocks;
    NSMutableData *all = [NSMutableData new];
    NSMutableArray<NSValue *> *draws = [NSMutableArray new];
    // Collect quads per (region, draw) first, then lay them out contiguously.
    typedef struct { float x, y, z; int dir; int tile; } Face; // dir 0 top, 1 +x, 2 -x, 3 +z, 4 -z; (x,y,z) = lower corner of the block
    NSMutableDictionary<NSNumber *, NSMutableData *> *faces = [NSMutableDictionary new];
    for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) {
        int h = (int)terrainHeight(x, z);
        int n[4] = {x + 1 < N ? (int)terrainHeight(x + 1, z) : h, x > 0 ? (int)terrainHeight(x - 1, z) : h,
                    z + 1 < N ? (int)terrainHeight(x, z + 1) : h, z > 0 ? (int)terrainHeight(x, z - 1) : h};
        // Top face on the surface block, plus a side face for every block level that sticks out above a lower neighbour.
        for (int dir = 0; dir < 5; dir++) {
            int lowest = dir == 0 ? h - 1 : (n[dir - 1] > h - 6 ? n[dir - 1] : h - 6), highest = h - 1;
            if (lowest < 0) lowest = 0;
            for (int y = lowest; y <= highest; y++) {
                if (dir > 0 && y < n[dir - 1]) continue;
                int sx = x / 16, sz = z / 16, sy = y / 16;
                int rx = (sx / 8), rz = (sz / 8);
                uint32_t key = ((uint32_t)(rx + rz * regionsPerSide) << 16) | (uint32_t)(((sx % 8) << 5) | ((sz % 8) << 2) | sy);
                NSMutableData *d = faces[@(key)]; if (!d) { d = [NSMutableData new]; faces[@(key)] = d; }
                Face f = {(float)x, (float)y, (float)z, dir, (int)(rnd() % tileCount)};
                [d appendBytes:&f length:sizeof f];
                if (dir == 0) break;
            }
        }
    }
    uint32_t vertexTotal = 0;
    NSArray<NSNumber *> *keys = [[faces allKeys] sortedArrayUsingSelector:@selector(compare:)];
    for (NSNumber *k in keys) {
        uint32_t key = k.unsignedIntValue; uint32_t region = key >> 16, draw = key & 0xFFFF;
        int rx = region % regionsPerSide, rz = region / regionsPerSide;
        int sxl = (draw >> 5) & 7, szl = (draw >> 2) & 7, sy = draw & 3;
        float ox = rx * kRegionBlocks + sxl * 16.0f, oy = sy * 16.0f, oz = rz * kRegionBlocks + szl * 16.0f;
        NSData *fd = faces[k]; const Face *f = fd.bytes; int count = (int)(fd.length / sizeof(Face));
        Draw dr = {region, draw, vertexTotal, (uint32_t)count};
        for (int i = 0; i < count; i++) {
            float x0 = f[i].x - ox, y0 = f[i].y - oy, z0 = f[i].z - oz;
            float c[4][3]; // corners of the quad
            switch (f[i].dir) {
                case 0: { float cc[4][3] = {{x0, y0 + 1, z0}, {x0 + 1, y0 + 1, z0}, {x0 + 1, y0 + 1, z0 + 1}, {x0, y0 + 1, z0 + 1}}; memcpy(c, cc, sizeof c); break; }
                case 1: { float cc[4][3] = {{x0 + 1, y0, z0}, {x0 + 1, y0, z0 + 1}, {x0 + 1, y0 + 1, z0 + 1}, {x0 + 1, y0 + 1, z0}}; memcpy(c, cc, sizeof c); break; }
                case 2: { float cc[4][3] = {{x0, y0, z0 + 1}, {x0, y0, z0}, {x0, y0 + 1, z0}, {x0, y0 + 1, z0 + 1}}; memcpy(c, cc, sizeof c); break; }
                case 3: { float cc[4][3] = {{x0 + 1, y0, z0 + 1}, {x0, y0, z0 + 1}, {x0, y0 + 1, z0 + 1}, {x0 + 1, y0 + 1, z0 + 1}}; memcpy(c, cc, sizeof c); break; }
                default: { float cc[4][3] = {{x0, y0, z0}, {x0 + 1, y0, z0}, {x0 + 1, y0 + 1, z0}, {x0, y0 + 1, z0}}; memcpy(c, cc, sizeof c); break; }
            }
            int tx = f[i].tile % 64, ty = f[i].tile / 64;
            uint32_t u0 = tx * 512, u1 = (tx + 1) * 512 - 1, v0 = ty * 512, v1 = (ty + 1) * 512 - 1;
            uint32_t us[4] = {u0, u1, u1, u0}, vs[4] = {v1, v1, v0, v0};
            float shade = f[i].dir == 0 ? 1.0f : (f[i].dir < 3 ? 0.8f : 0.65f); shade *= 0.8f + 0.2f * frand();
            uint8_t color[4] = {(uint8_t)(255 * shade), (uint8_t)(255 * shade), (uint8_t)(255 * shade), 255};
            uint8_t l0 = (uint8_t)(rnd() & 0xF0) | 0x0F, l1 = (uint8_t)(rnd() & 0xF0) | 0x0F;
            Vertex quad[4];
            for (int j = 0; j < 4; j++) pack(&quad[j], c[j][0], c[j][1], c[j][2], us[j] | (j == 1 || j == 2 ? 0x8000u : 0), vs[j] | (j == 0 || j == 1 ? 0x8000u : 0), color, l0, l1, draw);
            [all appendBytes:quad length:sizeof quad];
        }
        vertexTotal += (uint32_t)count * 4;
        [draws addObject:[NSValue valueWithBytes:&dr objCType:@encode(Draw)]];
    }
    printf("fixture: %u vertices, %u quads, %lu draws\n", vertexTotal, vertexTotal / 4, (unsigned long)draws.count);
    id<MTLBuffer> vbuf = [dev newBufferWithBytes:all.bytes length:all.length options:MTLResourceStorageModeShared];
    uint32_t maxQuads = 0; for (NSValue *v in draws) { Draw d; [v getValue:&d]; if (d.quads > maxQuads) maxQuads = d.quads; }
    uint32_t *indices = malloc((size_t)maxQuads * 6 * 4);
    for (uint32_t q = 0; q < maxQuads; q++) { uint32_t b = q * 4, *p = indices + q * 6; p[0] = b; p[1] = b + 1; p[2] = b + 2; p[3] = b; p[4] = b + 2; p[5] = b + 3; }
    id<MTLBuffer> ibuf = [dev newBufferWithBytes:indices length:(size_t)maxQuads * 6 * 4 options:MTLResourceStorageModeShared]; free(indices);

    // Section fade table (R32Sint texel buffer, like u_SectionTimeInfo): most sections done fading (-1), some mid fade.
    int regionCount = regionsPerSide * regionsPerSide, texels = regionCount * 256;
    id<MTLBuffer> timeBuf = [dev newBufferWithLength:(NSUInteger)texels * 4 options:MTLResourceStorageModeShared];
    int32_t *tp = timeBuf.contents; for (int i = 0; i < texels; i++) tp[i] = (rnd() % 12 == 0) ? 900 + (int)(rnd() % 100) : -1;
    MTLTextureDescriptor *tbd = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:MTLPixelFormatR32Sint width:(NSUInteger)texels
        resourceOptions:MTLResourceStorageModeShared usage:MTLTextureUsageShaderRead];
    id<MTLTexture> timeTex = [timeBuf newTextureWithDescriptor:tbd offset:0 bytesPerRow:(NSUInteger)texels * 4];

    id<MTLTexture> atlas = makeAtlas(dev, queue, cutout), light = makeLightmap(dev);
    MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
    sd.minFilter = MTLSamplerMinMagFilterLinear; sd.magFilter = MTLSamplerMinMagFilterLinear; sd.mipFilter = MTLSamplerMipFilterLinear;
    sd.sAddressMode = sd.tAddressMode = MTLSamplerAddressModeClampToEdge; sd.lodMaxClamp = 4;
    id<MTLSamplerState> blockSampler = [dev newSamplerStateWithDescriptor:sd];
    sd.mipFilter = MTLSamplerMipFilterNotMipmapped;
    id<MTLSamplerState> lightSampler = [dev newSamplerStateWithDescriptor:sd];

    // Camera and uniforms ---------------------------------------------------------------------------------------------
    float cam[3] = {N * 0.5f, fly ? 90.0f : 26.0f, N * 0.5f}, yaw = 0.45f, pitch = (fly ? 40.0f : 6.0f) * (float)M_PI / 180.0f;
    float fovY = 70.0f * (float)M_PI / 180.0f, aspect = (float)width / height, nearZ = 0.05f, farZ = 1024.0f;
    float fwd[3] = {sinf(yaw) * cosf(pitch), -sinf(pitch), -cosf(yaw) * cosf(pitch)};
    float right[3] = {-fwd[2], 0, fwd[0]}; float rl = sqrtf(right[0] * right[0] + right[2] * right[2]); right[0] /= rl; right[2] /= rl;
    float up[3] = {right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0]};
    Globals g; memset(&g, 0, sizeof g);
    float f = 1.0f / tanf(fovY * 0.5f);
    g.proj[0] = f / aspect; g.proj[5] = f; g.proj[10] = farZ / (nearZ - farZ); g.proj[11] = -1; g.proj[14] = nearZ * farZ / (nearZ - farZ);
    float rot[3][3] = {{right[0], right[1], right[2]}, {up[0], up[1], up[2]}, {-fwd[0], -fwd[1], -fwd[2]}};
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) g.mv[c * 4 + r] = rot[r][c];
    g.mv[15] = 1;
    g.fogColor[0] = 0.70f; g.fogColor[1] = 0.80f; g.fogColor[2] = 1.0f; g.fogColor[3] = 1.0f;
    g.camera[0] = cam[0]; g.camera[1] = cam[1]; g.camera[2] = cam[2];
    g.env[0] = 1.0e6f; g.env[1] = 1.0e6f + 1.0f; g.render[0] = 0.8f * 224.0f; g.render[1] = 224.0f * 1.25f;
    g.texel[0] = g.texel[1] = 1.0f / 1024.0f; g.shrink[0] = g.shrink[1] = -1.0e-5f; g.fadeInv = 1.0f / 200.0f;

    MTLTextureDescriptor *cd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
    cd.storageMode = MTLStorageModePrivate; cd.usage = MTLTextureUsageRenderTarget;
    id<MTLTexture> color = [dev newTextureWithDescriptor:cd];
    MTLTextureDescriptor *dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:width height:height mipmapped:NO];
    dd.storageMode = MTLStorageModePrivate; dd.usage = MTLTextureUsageRenderTarget;
    id<MTLTexture> depth = [dev newTextureWithDescriptor:dd];
    MTLDepthStencilDescriptor *dsd = [MTLDepthStencilDescriptor new]; dsd.depthCompareFunction = MTLCompareFunctionLess; dsd.depthWriteEnabled = YES;
    id<MTLDepthStencilState> depthState = [dev newDepthStencilStateWithDescriptor:dsd];
    NSUInteger pixels = (NSUInteger)width * height;
    id<MTLBuffer> colorOut = [dev newBufferWithLength:pixels * 4 options:MTLResourceStorageModeShared];
    id<MTLBuffer> depthOut = [dev newBufferWithLength:pixels * 4 options:MTLResourceStorageModeShared];

    // Variants ----------------------------------------------------------------------------------------------------------
    NSString *base = cutout ? @"cutout-fog" : @"solid-fog";
    NSString *tVS = readFile([dir stringByAppendingPathComponent:[base stringByAppendingString:@"-vertex.metal"]]);
    NSString *tFS = readFile([dir stringByAppendingPathComponent:[base stringByAppendingString:@"-fragment.metal"]]);
    NSString *eoFS = readFile([dir stringByAppendingPathComponent:[base stringByAppendingString:@"-eo-fragment.metal"]]);
    NSString *nativePath = argc > 7 ? [NSString stringWithUTF8String:argv[7]] : @"src/main/resources/assets/puremetal/shaders/sodium_terrain.metal";
    NSString *nativeSource = readFile(nativePath);
    NSString *cut = cutout ? @"#define PM_CUTOUT 0.5\n" : @"";

    NSMutableArray<NSString *> *names = [NSMutableArray new]; NSMutableArray<id<MTLRenderPipelineState>> *states = [NSMutableArray new];
    void (^addT)(NSString *, BOOL, BOOL) = ^(NSString *name, BOOL safe, BOOL early) {
        id<MTLLibrary> lv = library(dev, tVS, safe, name), lf = library(dev, early ? eoFS : tFS, safe, name);
        [names addObject:name]; [states addObject:pipeline(dev, function(lv, @"main0", NO), function(lf, @"main0", early), name)];
    };
    void (^addN)(NSString *, NSString *, BOOL) = ^(NSString *name, NSString *defines, BOOL safe) {
        id<MTLLibrary> lib = library(dev, [NSString stringWithFormat:@"%@%@%@", cut, defines, nativeSource], safe, name);
        [names addObject:name]; [states addObject:pipeline(dev, function(lib, @"pmTerrainVertex", NO), function(lib, @"pmTerrainFragment", NO), name)];
    };
    addT(@"translated (Safe math, as shipped)", YES, NO);
    addT(@"translated + RGSS early-out", YES, YES);
    addT(@"translated, Relaxed math", NO, NO);
    addT(@"translated + early-out, Relaxed math", NO, YES);
    addN(@"native", @"", YES);
    addN(@"native, float colour math", @"#define PM_HALF 0\n", YES);
    addN(@"native, no early-out", @"#define PM_EARLY_OUT 0\n", YES);
    addN(@"native, proj*(mv*p) matrix (inexact)", @"#define PM_FAST_MATRIX 1\n", YES);
    addN(@"native, divides kept", @"#define PM_RCP 0\n", YES);
    addN(@"native, Relaxed math", @"", NO);
    addN(@"native, RGSS forced on", @"#define PM_RGSS_MODE 1\n", YES);
    addN(@"native, RGSS forced off", @"#define PM_RGSS_MODE 2\n", YES);

    // Rendering ------------------------------------------------------------------------------------------------------------
    int rgssSettings[2] = {1, 0};
    for (int s = 0; s < 2; s++) {
        int rgss = rgssSettings[s]; g.rgss = (uint32_t)rgss;
        printf("\n=== u_UseRGSS = %d ===\n", rgss);
        void (^render)(NSUInteger, BOOL, double *) = ^(NSUInteger variant, BOOL readback, double *ms) {
            id<MTLCommandBuffer> cb = [queue commandBuffer];
            MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
            rp.colorAttachments[0].texture = color; rp.colorAttachments[0].loadAction = MTLLoadActionClear;
            rp.colorAttachments[0].clearColor = MTLClearColorMake(0.55, 0.7, 1.0, 1.0); rp.colorAttachments[0].storeAction = MTLStoreActionStore;
            rp.depthAttachment.texture = depth; rp.depthAttachment.loadAction = MTLLoadActionClear; rp.depthAttachment.clearDepth = 1.0;
            rp.depthAttachment.storeAction = MTLStoreActionStore;
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
            [enc setRenderPipelineState:states[variant]]; [enc setDepthStencilState:depthState]; [enc setCullMode:MTLCullModeNone];
            [enc setVertexBytes:&g length:sizeof g atIndex:0]; [enc setFragmentBytes:&g length:sizeof g atIndex:0];
            [enc setVertexTexture:timeTex atIndex:0]; [enc setVertexTexture:light atIndex:1]; [enc setVertexSamplerState:lightSampler atIndex:0];
            [enc setFragmentTexture:atlas atIndex:2]; [enc setFragmentSamplerState:blockSampler atIndex:1];
            [enc setVertexBuffer:vbuf offset:0 atIndex:PM_VERTEX_BUFFER];
            for (NSValue *v in draws) {
                Draw d; [v getValue:&d];
                Push p = {{(float)((d.region % regionsPerSide) * kRegionBlocks), 0.0f, (float)((d.region / regionsPerSide) * kRegionBlocks)}, 1000, d.region};
                [enc setVertexBytes:&p length:sizeof p atIndex:15];
                [enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:d.quads * 6 indexType:MTLIndexTypeUInt32 indexBuffer:ibuf indexBufferOffset:0
                    instanceCount:1 baseVertex:(NSInteger)d.first baseInstance:0];
            }
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
        BOOL (^skip)(NSUInteger) = ^BOOL(NSUInteger v) {
            BOOL on = [names[v] containsString:@"forced on"], off = [names[v] containsString:@"forced off"];
            return (on && !rgss) || (off && rgss);
        };
        uint8_t *refColor = malloc(pixels * 4); uint32_t *refDepth = malloc(pixels * 4);
        double *samples = calloc(count * (size_t)frames, sizeof(double)); int *taken = calloc(count, sizeof(int));
        // Pixel comparison against the translated Safe math image.
        for (NSUInteger v = 0; v < count; v++) {
            if (skip(v)) continue;
            render(v, YES, NULL);
            const uint8_t *c = colorOut.contents; const uint32_t *dp = depthOut.contents;
            if (v == 0) { memcpy(refColor, c, pixels * 4); memcpy(refDepth, dp, pixels * 4); }
            int maxDiff = 0; NSUInteger over1 = 0, depthDiffer = 0; float maxDepth = 0;
            for (NSUInteger i = 0; i < pixels * 4; i++) { int d = abs((int)c[i] - (int)refColor[i]); if (d > maxDiff) maxDiff = d; if (d > 1) over1++; }
            for (NSUInteger i = 0; i < pixels; i++) if (dp[i] != refDepth[i]) {
                depthDiffer++; float a, b; memcpy(&a, &dp[i], 4); memcpy(&b, &refDepth[i], 4); if (fabsf(a - b) > maxDepth) maxDepth = fabsf(a - b);
            }
            printf("  pixels vs translated: %-40s max channel diff %3d/255, channels >1: %7lu, depth differing: %lu (max %.3g)%s\n", names[v].UTF8String, maxDiff,
                (unsigned long)over1, (unsigned long)depthDiffer, maxDepth, maxDiff <= 1 ? "" : "   <-- FAILS 1/255 gate");
        }
        // ABBA style interleaving: every round visits all variants, alternating the direction, after two warmup rounds.
        for (int round = -2; round < frames; round++) {
            for (NSUInteger k = 0; k < count; k++) {
                NSUInteger v = (round & 1) ? count - 1 - k : k; double ms = 0;
                if (skip(v)) continue;
                @autoreleasepool { render(v, NO, &ms); }
                if (round >= 0) samples[v * (size_t)frames + (size_t)taken[v]++] = ms;
            }
        }
        double reference = 0, referenceEo = 0;
        for (NSUInteger v = 0; v < count; v++) {
            if (skip(v)) continue;
            double *a = samples + v * (size_t)frames; qsort(a, (size_t)frames, sizeof(double), cmpDouble);
            double median = (frames & 1) ? a[frames / 2] : 0.5 * (a[frames / 2 - 1] + a[frames / 2]);
            if (v == 0) reference = median;
            if (v == 1) referenceEo = median;
            printf("  %-40s median %7.3f ms  [p10 %.3f, p90 %.3f]  vs translated %+6.1f%%  vs translated+early-out %+6.1f%%\n", names[v].UTF8String, median,
                a[frames / 10], a[(frames * 9) / 10], 100.0 * (median - reference) / reference, 100.0 * (median - referenceEo) / referenceEo);
        }
        free(refColor); free(refDepth); free(samples); free(taken);
    }
    printf("\nTimes are GPUEndTime - GPUStartTime of one command buffer holding one full main pass (colour + depth, %lu draws).\n", (unsigned long)draws.count);
    return 0;
} }
