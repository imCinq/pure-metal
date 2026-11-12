// Hand written Apple GPU version of Sodium's block_layer_opaque terrain shader (solid and cutout passes).
// Same vertex format, uniform block, push constants and varyings as the SPIRV-Cross translation of the pinned GLSL.
// The host prepends the #defines below; every knob has a default so the file also compiles stand-alone.
//
//   PM_BUF_GLOBALS / PM_TEX_SECTION_TIME / PM_TEX_LIGHT / PM_SMP_LIGHT / PM_TEX_BLOCK / PM_SMP_BLOCK:
//       argument table slots (the host takes them from the pipeline's bindings, push constants are always buffer 15)
//   PM_CUTOUT=<float>   alpha cutoff of the cutout pass (defined only for it)
//   PM_RGSS_MODE        0 = follow u_UseRGSS at run time (default), 1 = RGSS always on, 2 = always off
//   PM_HALF             1 = half precision colour math (default); positions, depth, derivatives, fog distance stay float
//   PM_EARLY_OUT        1 = skip the four RGSS taps where the blend factor is exactly zero (bit identical, default)
//   PM_FAST_MATRIX      0 = (proj * modelView) * p like the GLSL (default); 1 = proj * (modelView * p). The reordering is
//                       cheaper but moves depth by ~3e-7 and flips depth ties at shared edges, so it is off.
//   PM_RCP              1 = multiply by reciprocals of per draw constants instead of dividing per fragment (default)
#include <metal_stdlib>
using namespace metal;

#ifndef PM_BUF_GLOBALS
#define PM_BUF_GLOBALS 0
#define PM_TEX_SECTION_TIME 0
#define PM_TEX_LIGHT 1
#define PM_SMP_LIGHT 0
#define PM_TEX_BLOCK 2
#define PM_SMP_BLOCK 1
#endif
#ifndef PM_RGSS_MODE
#define PM_RGSS_MODE 0
#endif
#ifndef PM_HALF
#define PM_HALF 1
#endif
#ifndef PM_EARLY_OUT
#define PM_EARLY_OUT 1
#endif
#ifndef PM_FAST_MATRIX
#define PM_FAST_MATRIX 0
#endif
#ifndef PM_RCP
#define PM_RCP 1
#endif

#if PM_HALF
typedef half pmh;
typedef half3 pmh3;
typedef half4 pmh4;
#else
typedef float pmh;
typedef float3 pmh3;
typedef float4 pmh4;
#endif

struct u_Globals {
    float4x4 u_ProjectionMatrix;
    float4x4 u_ModelViewMatrix;
    float4 u_FogColor;
    float3 u_CameraOffset;
    float2 u_EnvironmentFog;
    float2 u_RenderFog;
    float2 u_TexelSize;
    float2 u_TexCoordShrink;
    float u_FadePeriodInv;
    uint u_UseRGSS;
};

struct PC {
    packed_float3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
};

struct VertexIn {
    uint2 a_Position [[attribute(0)]];
    pmh4 a_Color [[attribute(1)]];
    uint2 a_TexCoord [[attribute(2)]];
    uint4 a_LightAndData [[attribute(3)]];
};

struct VertexOut {
    pmh4 v_Color [[user(locn0)]];
    float2 v_TexCoord [[user(locn1)]];
    float2 v_FragDistance [[user(locn2)]];
    pmh v_fadeFactor [[user(locn3)]];
    float4 gl_Position [[position]];
};

vertex VertexOut pmTerrainVertex(VertexIn in [[stage_in]],
                                 constant u_Globals& g [[buffer(PM_BUF_GLOBALS)]],
                                 constant PC& pc [[buffer(15)]],
                                 texture_buffer<int> sectionTime [[texture(PM_TEX_SECTION_TIME)]],
                                 texture2d<pmh> lightTex [[texture(PM_TEX_LIGHT)]],
                                 sampler lightSmp [[sampler(PM_SMP_LIGHT)]]) {
    VertexOut out;
    // 20 bit coordinates, interleaved as two 10 bit halves in two words (see chunk_vertex.glsl).
    uint3 hi = (uint3(in.a_Position.x) >> uint3(0u, 10u, 20u)) & uint3(1023u);
    uint3 lo = (uint3(in.a_Position.y) >> uint3(0u, 10u, 20u)) & uint3(1023u);
    float3 local = float3((hi << uint3(10u)) | lo) * 3.0517578125e-05 + float3(-8.0);

    uint draw = in.a_LightAndData.w;
    // The section inside its region: 8 x 4 x 8, packed into the draw id.
    uint3 cell = (uint3(draw) >> uint3(5u, 0u, 2u)) & uint3(7u, 3u, 7u);
    float3 translation = float3(pc.u_RegionOffset) + float3(cell) * float3(16.0);
    float3 position = (local + translation) - g.u_CameraOffset;

    out.v_FragDistance = float2(max(length(position.xz), abs(position.y)), length(position));

    int chunkFade = sectionTime.read(uint(int((pc.u_RegionID * 256u) + draw))).x;
    float fade = fast::clamp(float(pc.u_CurrentTime - chunkFade) * g.u_FadePeriodInv, 0.0, 1.0);
    out.v_fadeFactor = pmh((chunkFade < 0) ? 1.0 : fade);

#if PM_FAST_MATRIX
    float4 clip = g.u_ProjectionMatrix * (g.u_ModelViewMatrix * float4(position, 1.0));
#else
    float4 clip = (g.u_ProjectionMatrix * g.u_ModelViewMatrix) * float4(position, 1.0);
#endif
    clip.y = -clip.y; // Metal's clip space Y points the other way
    out.gl_Position = clip;

    float2 lightCoord = float2(in.a_LightAndData.xy) * 0.00390625;
    out.v_Color = in.a_Color * lightTex.sample(lightSmp, lightCoord, level(0.0));

    float2 coord = float2(in.a_TexCoord & uint2(32767u)) * 3.0517578125e-05;
    float2 bias = float2(in.a_TexCoord >> uint2(15u)) * 2.0 - 1.0;
    out.v_TexCoord = bias * g.u_TexCoordShrink + coord;
    return out;
}

struct FragmentIn {
    pmh4 v_Color [[user(locn0)]];
    float2 v_TexCoord [[user(locn1)]];
    float2 v_FragDistance [[user(locn2)]];
    pmh v_fadeFactor [[user(locn3)]];
};

// Snaps the lookup toward the texel centre by how small a texel is on screen (Sodium's "nearest" filtering).
static inline pmh4 pmNearest(texture2d<pmh> tex, sampler smp, float2 uv, float2 pixelSize, float2 invPixelSize,
                             float2 du, float2 dv, float2 texelScreenSize) {
#if PM_RCP
    float2 uvTexelCoords = uv * invPixelSize;
#else
    float2 uvTexelCoords = uv / pixelSize;
#endif
    float2 texelCenter = round(uvTexelCoords) - 0.5;
    float2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = fast::divide((texelOffset - 0.5) * pixelSize, texelScreenSize) + 0.5;
    texelOffset = fast::clamp(texelOffset, 0.0, 1.0);
    return tex.sample(smp, (texelCenter + texelOffset) * pixelSize, gradient2d(du, dv));
}

static inline float linearFog(float distance, float fogStart, float fogEnd) {
    if (distance <= fogStart) {
        return 0.0;
    } else if (distance >= fogEnd) {
        return 1.0;
    }
    return fast::divide(distance - fogStart, fogEnd - fogStart);
}

fragment pmh4 pmTerrainFragment(FragmentIn in [[stage_in]],
                                constant u_Globals& g [[buffer(PM_BUF_GLOBALS)]],
                                texture2d<pmh> blockTex [[texture(PM_TEX_BLOCK)]],
                                sampler blockSmp [[sampler(PM_SMP_BLOCK)]]) {
    float2 uv = in.v_TexCoord;
    float2 pixelSize = g.u_TexelSize;
    float2 invPixelSize = fast::divide(float2(1.0), pixelSize);
    float2 du = dfdx(uv);
    float2 dv = dfdy(uv);
    float2 texelScreenSize = fast::sqrt(du * du + dv * dv);

    pmh4 color;
#if PM_RGSS_MODE == 2
    bool rgss = false;
#elif PM_RGSS_MODE == 1
    bool rgss = true;
#else
    bool rgss = g.u_UseRGSS != 0u;
#endif
    if (rgss) {
        float maxTexelSize = fast::max(texelScreenSize.x, texelScreenSize.y);
        float minPixelSize = fast::min(pixelSize.x, pixelSize.y);
        // smoothstep(minPixelSize, 2 * minPixelSize, maxTexelSize): zero exactly while maxTexelSize <= minPixelSize.
        float t = fast::clamp((maxTexelSize - minPixelSize) * fast::divide(1.0, minPixelSize), 0.0, 1.0);
        float blend = t * t * (3.0 - 2.0 * t);
        pmh4 nearest = pmNearest(blockTex, blockSmp, uv, pixelSize, invPixelSize, du, dv, texelScreenSize);
#if PM_EARLY_OUT
        if (maxTexelSize <= minPixelSize) {
            color = nearest;
        } else
#endif
        {
            // log2(sqrt(|du| * |dv|) / minPixelSize), with |d| * |d| = dot(d, d)
            float lod = fast::max(0.0, 0.25 * fast::log2(dot(du, du) * dot(dv, dv)) - fast::log2(minPixelSize));
            pmh4 super = blockTex.sample(blockSmp, uv + float2(0.125, 0.375) * pixelSize, level(lod))
                       + blockTex.sample(blockSmp, uv + float2(-0.125, -0.375) * pixelSize, level(lod))
                       + blockTex.sample(blockSmp, uv + float2(0.375, -0.125) * pixelSize, level(lod))
                       + blockTex.sample(blockSmp, uv + float2(-0.375, 0.125) * pixelSize, level(lod));
            super *= pmh(0.25);
            color = nearest + (super - nearest) * pmh(blend);
        }
    } else {
        color = pmNearest(blockTex, blockSmp, uv, pixelSize, invPixelSize, du, dv, texelScreenSize);
    }
    color *= in.v_Color;

#ifdef PM_CUTOUT
    if (color.a < PM_CUTOUT) {
        discard_fragment();
    }
#endif

    float fogValue = fast::max(1.0 - float(in.v_fadeFactor),
        fast::max(linearFog(in.v_FragDistance.y, g.u_EnvironmentFog.x, g.u_EnvironmentFog.y),
                  linearFog(in.v_FragDistance.x, g.u_RenderFog.x, g.u_RenderFog.y)));
    pmh3 fog = pmh3(g.u_FogColor.rgb);
    pmh amount = pmh(fogValue * g.u_FogColor.a);
    return pmh4(mix(color.rgb, fog, amount), color.a);
}
