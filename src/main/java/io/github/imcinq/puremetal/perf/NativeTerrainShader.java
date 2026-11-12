package io.github.imcinq.puremetal.perf;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

/**
 * Per-compilation proof that a pipeline compiles exactly the pinned Sodium 0.9.3-beta.5 solid terrain shader (no
 * ALPHA_CUTOUT, no OIT), so the hand written MSL in sodium_terrain.metal may stand in for the SPIRV-Cross translation.
 * Any other source text, include or define leaves the translated shader untouched. -Dpuremetal.nativeTerrain=false turns
 * the substitution off; -Dpuremetal.benchAB=nativeTerrain builds both variants and alternates them.
 */
public final class NativeTerrainShader implements AutoCloseable {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("puremetal.nativeTerrain", "true"));
    public static final boolean AB = "nativeTerrain".equals(System.getProperty("puremetal.benchAB"));
    private static final ThreadLocal<Boolean> PROVEN = ThreadLocal.withInitial(() -> false);
    private static final Identifier SODIUM = Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");
    private static final String VERTEX_HASH = "35b6b4307e2254adf90205733b34f96945939643b9fb6f0d8f3d69826cf74451";
    private static final String FRAGMENT_HASH = "db2e196489be7a6be867d8ebfdfe96b58580c6de2b002dcedc3aebbe1e201abc";
    private static final Map<String, String> INCLUDES = Map.of(
        "sodium:chunk_vertex.glsl", "f81998545f37971d167e079b0ea78dee9ae99b660bbf7053053dcfd11e06a793",
        "sodium:fog.glsl", "8f471f062405ed9928e2d298188601966f444072b33675b546b9ebffebe095be",
        "sodium:globals.glsl", "bd44a4ef22ba629caf0c8f887807c340112635d464dc08bc04cfa88d88cfc518",
        "sodium:chunk_material.glsl", "456e00dc7904a2e003fe4e1f06b2023bc3189cb7259fec77ba3e854fc4f7a452",
        "minecraft:oit.glsl", "d90c5f454fffff35e81673b9806531d468d9597557a2bf7d9e37ed5cd7c18989");
    private static final Set<String> ALLOWED_FLAGS = Set.of("MULTIDRAW_TERRAIN", "USE_FOG", "USE_VERTEX_COMPRESSION", "VULKAN");

    private final boolean previous;

    private NativeTerrainShader(final boolean value) {
        this.previous = PROVEN.get();
        PROVEN.set(value);
    }

    /** True on the compiling thread while the pipeline being compiled is the pinned solid terrain shader. */
    public static boolean proven() {
        return PROVEN.get();
    }

    public static NativeTerrainShader enter(final RenderPipeline pipeline, final ShaderSource source) {
        return new NativeTerrainShader((ENABLED || AB) && validate(pipeline, source));
    }

    private static boolean hash(final String value, final String expected) throws Exception {
        return value != null && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).equals(expected);
    }

    private static boolean validate(final RenderPipeline pipeline, final ShaderSource source) {
        try {
            var defines = pipeline.getShaderDefines();
            if (!defines.values().isEmpty() || !ALLOWED_FLAGS.containsAll(defines.flags())
                || !defines.flags().contains("USE_FOG") || !defines.flags().contains("USE_VERTEX_COMPRESSION")) {
                return false;
            }
            Identifier vertex = pipeline.getShaders().get(ShaderType.VERTEX);
            if (!SODIUM.equals(vertex) || !vertex.equals(pipeline.getShaders().get(ShaderType.FRAGMENT))) {
                return false;
            }
            // Hash the text that is actually compiled: a source patched by the LOD texture rewrite never matches.
            if (!hash(source.getShader(vertex, ShaderType.VERTEX), VERTEX_HASH) || !hash(source.getShader(vertex, ShaderType.FRAGMENT), FRAGMENT_HASH)) {
                return false;
            }
            for (var entry : INCLUDES.entrySet()) {
                var include = source.getInclude(Identifier.parse(entry.getKey()));
                if (include == null || include.includeResultPtr() == 0) {
                    return false;
                }
                var result = ShadercIncludeResult.create(include.includeResultPtr());
                long length = result.content_length();
                long address = org.lwjgl.system.MemoryUtil.memGetAddress(result.address() + ShadercIncludeResult.CONTENT);
                if (address == 0 || length <= 0 || length > 1048576) {
                    return false;
                }
                var content = org.lwjgl.system.MemoryUtil.memByteBuffer(address, (int) length);
                if (content == null || !hash(StandardCharsets.UTF_8.decode(content).toString(), entry.getValue())) {
                    return false;
                }
            }
            return true;
        } catch (Exception unsupported) {
            return false;
        }
    }

    @Override
    public void close() {
        PROVEN.set(this.previous);
    }
}
