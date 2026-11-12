package io.github.imcinq.puremetal.backend;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The hand written Apple GPU MSL for Sodium's solid terrain shader (assets/puremetal/shaders/sodium_terrain.metal). It uses
 * the vertex layout, uniform block, push constants and varyings of the pinned GLSL; only the argument table slots depend on
 * the pipeline, so they are taken from its bindings by uniform name. Anything that doesn't line up returns null and the
 * SPIRV-Cross translation is used, with one logged reason.
 */
final class NativeTerrain {
    static final String VERTEX_ENTRY = "pmTerrainVertex";
    static final String FRAGMENT_ENTRY = "pmTerrainFragment";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static volatile String template;

    private NativeTerrain() {
    }

    /** The full MSL source (both entry points) for this pipeline, or null when it isn't the pipeline the native shader implements. */
    static @Nullable String source(final BackendRenderPipeline.CreateInfo info, final MetalPipeline.Bindings bindings) {
        String problem = check(info);
        Map<String, Integer> slots = new HashMap<>();
        List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
        for (int i = 0; i < uniforms.size(); i++) {
            slots.put(uniforms.get(i).name(), i);
        }
        int globals = slot(slots, bindings, "u_Globals", UniformType.UNIFORM_BUFFER);
        int sectionTime = slot(slots, bindings, "u_SectionTimeInfo", UniformType.TEXEL_BUFFER);
        int light = slot(slots, bindings, "u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER);
        int block = slot(slots, bindings, "u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER);
        if (problem == null && (globals < 0 || sectionTime < 0 || light < 0 || block < 0)) {
            problem = "unexpected uniforms " + uniforms.stream().map(BindGroupLayout.UniformDescription::name).toList();
        }
        if (problem != null) {
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.warn("Pure Metal: native terrain shader not used for {} ({}); the translated shader stays", info.name(), problem);
            }
            return null;
        }
        String defines = "#define PM_BUF_GLOBALS " + bindings.bufferIndex(globals) + "\n"
            + "#define PM_TEX_SECTION_TIME " + bindings.textureIndex(sectionTime) + "\n"
            + "#define PM_TEX_LIGHT " + bindings.textureIndex(light) + "\n"
            + "#define PM_SMP_LIGHT " + bindings.samplerIndex(light) + "\n"
            + "#define PM_TEX_BLOCK " + bindings.textureIndex(block) + "\n"
            + "#define PM_SMP_BLOCK " + bindings.samplerIndex(block) + "\n";
        return defines + template();
    }

    private static int slot(final Map<String, Integer> slots, final MetalPipeline.Bindings bindings, final String name, final UniformType type) {
        Integer index = slots.get(name);
        return index != null && bindings.types()[index] == type ? index : -1;
    }

    private static @Nullable String check(final BackendRenderPipeline.CreateInfo info) {
        int vertex = 0;
        int fragment = 0;
        for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
            if (shader.module().type() == ShaderType.VERTEX) {
                vertex++;
            } else if (shader.module().type() == ShaderType.FRAGMENT) {
                fragment++;
            }
        }
        if (vertex != 1 || fragment != 1 || info.shaders().size() != 2) {
            return "not one vertex and one fragment shader";
        }
        Map<Integer, GpuFormat> formats = new HashMap<>();
        for (BackendRenderPipeline.CreateInfo.AttribBinding attribute : info.attribBindings()) {
            formats.put(attribute.location(), attribute.format());
        }
        if (formats.size() != 4 || formats.get(0) != GpuFormat.RG32_UINT || formats.get(1) != GpuFormat.RGBA8_UNORM
            || formats.get(2) != GpuFormat.RG16_UINT || formats.get(3) != GpuFormat.RGBA8_UINT) {
            return "unexpected vertex format " + formats;
        }
        return null;
    }

    private static String template() {
        String text = template;
        if (text == null) {
            try (InputStream in = NativeTerrain.class.getResourceAsStream("/assets/puremetal/shaders/sodium_terrain.metal")) {
                if (in == null) {
                    throw new IllegalStateException("sodium_terrain.metal is missing from the jar");
                }
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't read sodium_terrain.metal", e);
            }
            template = text;
        }
        return text;
    }
}
