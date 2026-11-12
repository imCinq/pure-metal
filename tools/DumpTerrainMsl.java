import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * Headless (no game, no window): reproduces the GLSL -> SPIR-V -> SPIRV-Cross MSL translation Pure Metal applies to the
 * Sodium terrain shaders, using the same shaderc options as the frontend's GlslCompiler and the same SPIRV-Cross options
 * as MetalShaderTranslator. Needs only the LWJGL core, shaderc and spvc jars (plus macOS natives) on the class path.
 *
 * java -cp lwjgl.jar:lwjgl-shaderc.jar:lwjgl-spvc.jar:natives... tools/DumpTerrainMsl.java SODIUM_SHADER_DIR MC_INCLUDE_DIR OUT_DIR
 *   SODIUM_SHADER_DIR = assets/sodium/shaders extracted from the Sodium jar; MC_INCLUDE_DIR = directory holding oit.glsl.
 */
public final class DumpTerrainMsl {
    /** The unified uniform order the frontend would produce for the Sodium terrain pipeline (vertex first, then fragment). */
    private static final List<String> ORDER = List.of("u_Globals", "u_SectionTimeInfo", "u_LightTex", "u_BlockTex");
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s+<(\\w+):([\\w./]+)>\\s*$", Pattern.MULTILINE);

    /** earlyOut: also dump the fragment with the mod's bit identical RGSS early-out injected (a stronger baseline). */
    record Variant(String name, Map<String, String> defines, boolean earlyOut) {
    }

    public static void main(final String[] args) throws Exception {
        Path sodium = Path.of(args[0]);
        Path mc = Path.of(args[1]);
        Path out = Path.of(args[2]);
        Files.createDirectories(out);
        String flags = "MULTIDRAW_TERRAIN,USE_VERTEX_COMPRESSION";
        List<Variant> variants = List.of(
            new Variant("solid-fog", def(flags + ",USE_FOG"), false),
            new Variant("solid-fog-eo", def(flags + ",USE_FOG"), true),
            new Variant("cutout-fog", def(flags + ",USE_FOG,ALPHA_CUTOUT=0.5"), false),
            new Variant("cutout-fog-eo", def(flags + ",USE_FOG,ALPHA_CUTOUT=0.5"), true),
            new Variant("cutout01-fog", def(flags + ",USE_FOG,ALPHA_CUTOUT=0.1"), false));
        for (Variant v : variants) {
            for (boolean fragment : new boolean[] {false, true}) {
                String path = "blocks/block_layer_opaque." + (fragment ? "fsh" : "vsh");
                String source = inline(Files.readString(sodium.resolve(path)), sodium, mc);
                if (fragment && v.earlyOut()) {
                    String early = Files.readString(Path.of("src/main/resources/assets/puremetal/shaders/rgss_early_out_sodium.glsl"));
                    source = source.replace("void main() {", early + "\nvoid main() {");
                    source = source.replace("u_UseRGSS ? sampleRGSS(u_BlockTex", "u_UseRGSS ? pmSampleRGSS(u_BlockTex");
                }
                byte[] spirv = compile(source, fragment, v.defines());
                String msl = translate(spirv, fragment);
                Files.writeString(out.resolve(v.name() + (fragment ? "-fragment.metal" : "-vertex.metal")), msl);
            }
            System.out.println("dumped " + v.name());
        }
    }

    private static Map<String, String> def(final String list) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (String item : list.split(",")) {
            String[] kv = item.split("=", 2);
            map.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return map;
    }

    private static String inline(final String text, final Path sodium, final Path mc) throws Exception {
        Matcher m = INCLUDE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Path file = m.group(1).equals("sodium") ? sodium.resolve("include").resolve(m.group(2)) : mc.resolve(m.group(2));
            // Includes behind #ifdef OIT only exist for the OIT variants; those are not dumped, so a missing file is skipped.
            String body = Files.exists(file) ? inline(Files.readString(file), sodium, mc) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(body));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static byte[] compile(final String source, final boolean fragment, final Map<String, String> defines) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        // Mirrors GlslCompiler.createBaseShaderOptions: Vulkan 1.2, auto bound uniforms, debug info, no optimization.
        Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
        Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
        Shaderc.shaderc_compile_options_set_generate_debug_info(options);
        Shaderc.shaderc_compile_options_set_optimization_level(options, 0);
        Shaderc.shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
        for (var e : defines.entrySet()) {
            Shaderc.shaderc_compile_options_add_macro_definition(options, e.getKey(), e.getValue());
        }
        long result = Shaderc.shaderc_compile_into_spv(compiler, source, fragment ? Shaderc.shaderc_glsl_fragment_shader : Shaderc.shaderc_glsl_vertex_shader,
            "block_layer_opaque", "main", options);
        if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
            throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(result));
        }
        ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
        byte[] copy = new byte[bytes.remaining()];
        bytes.get(copy);
        Shaderc.shaderc_result_release(result);
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);
        return copy;
    }

    private static String translate(final byte[] spirvBytes, final boolean fragment) {
        int model = fragment ? 4 : 0;
        ByteBuffer raw = MemoryUtil.memAlloc(spirvBytes.length).put(spirvBytes).flip();
        IntBuffer spirv = raw.asIntBuffer();
        long context = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer ptr = stack.callocPointer(1);
            check(Spvc.spvc_context_create(ptr));
            context = ptr.get(0);
            check(Spvc.spvc_context_parse_spirv(context, spirv, spirv.remaining(), ptr));
            long ir = ptr.get(0);
            check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr));
            long compiler = ptr.get(0);
            check(Spvc.spvc_compiler_create_compiler_options(compiler, ptr));
            long options = ptr.get(0);
            // Same options as MetalShaderTranslator (non point pipeline).
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, 30000);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, false);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_DEFAULT, false);
            if (!fragment) {
                Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
            }
            check(Spvc.spvc_compiler_install_compiler_options(compiler, options));

            check(Spvc.spvc_compiler_create_shader_resources(compiler, ptr));
            long resources = ptr.get(0);
            PointerBuffer count = stack.callocPointer(1);
            SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
            List<String> seen = new ArrayList<>();
            int[] types = {Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE};
            for (int type : types) {
                check(Spvc.spvc_resources_get_resource_list_for_type(resources, type, ptr, count));
                for (int i = 0; i < (int) count.get(0); i++) {
                    SpvcReflectedResource r = SpvcReflectedResource.create(ptr.get(0) + (long) i * SpvcReflectedResource.SIZEOF);
                    String name = r.nameString();
                    int glslangBinding = Spvc.spvc_compiler_get_decoration(compiler, r.id(), 33);
                    int unified = ORDER.indexOf(name);
                    if (unified < 0) {
                        throw new IllegalStateException("unexpected resource " + name);
                    }
                    // Counters like MetalPipeline.Bindings.of: buffers, textures (incl. texel buffers) and samplers count separately.
                    int buffer = 0;
                    int texture = textureIndex(name);
                    int sampler = samplerIndex(name);
                    Spvc.spvc_msl_resource_binding_init(binding);
                    binding.stage(model).desc_set(0).binding(glslangBinding).msl_buffer(buffer).msl_texture(texture).msl_sampler(sampler);
                    check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding));
                    seen.add(name + "=set0/binding" + glslangBinding + " tex" + texture + " smp" + sampler);
                }
            }
            Spvc.spvc_msl_resource_binding_init(binding);
            binding.stage(model).desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET).binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING).msl_buffer(15);
            check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding));
            check(Spvc.spvc_compiler_set_entry_point(compiler, "main", model));
            check(Spvc.spvc_compiler_compile(compiler, ptr));
            return MemoryUtil.memUTF8(ptr.get(0));
        } finally {
            if (context != 0L) {
                Spvc.spvc_context_destroy(context);
            }
            MemoryUtil.memFree(raw);
        }
    }

    /** u_Globals is the only uniform buffer (buffer 0); textures are numbered in ORDER among non-buffer uniforms. */
    private static int textureIndex(final String name) {
        return switch (name) {
            case "u_SectionTimeInfo" -> 0;
            case "u_LightTex" -> 1;
            case "u_BlockTex" -> 2;
            default -> 0;
        };
    }

    private static int samplerIndex(final String name) {
        return switch (name) {
            case "u_LightTex" -> 0;
            case "u_BlockTex" -> 1;
            default -> 0;
        };
    }

    private static void check(final int result) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross error " + result);
        }
    }
}
