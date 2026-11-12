package io.github.imcinq.puremetal.perf;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import io.github.imcinq.puremetal.lod.LodTextureRepeat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

/** Per-compilation proof of the pinned stock vertex transform, including all its transitive includes. */
public final class TerrainShaderProof implements AutoCloseable {
    private static final ThreadLocal<Integer> CURRENT=ThreadLocal.withInitial(()->0);
    private final int previous;
    private final boolean previousStock;
    private static final ThreadLocal<Boolean> STOCK=ThreadLocal.withInitial(()->false);
    /** The extra includes the OIT variants pull in; with VANILLA they cover everything minecraft:core/terrain can reach. */
    private static final Map<String,String> OIT=Map.of(
        "minecraft:oit_common.glsl","bd5f404aea0c8b75f32471e763332fdfa81a30106dfd1318a3fec0a82e1c7720",
        "minecraft:oit_depth_sample.glsl","fcf65ad828e0a079652096ceea20da9367ef38c53d77e88da74a7f00718e8b90",
        "minecraft:oit_depth_bounds.glsl","892f076e405e784f44da5f1a59c4aedeffe45b6a1b56ef733ff515c3218c4a04",
        "minecraft:oit_add_transmittance.glsl","79f5b662a4550428757bac08b9e725dae775d9efc73416b96940009c8ae2a8f4",
        "minecraft:oit_sample.glsl","92c2735235ac4c7122230baeac027b24784d0d2d4cc0b3740c2ca97855832b90");
    private static final Map<String,String> VANILLA=Map.of(
        "minecraft:chunksection.glsl","8bcd0bc9365f6d5cf44254a27247737ed88637b9a917ebf9a574ab8fdd9af4ae",
        "minecraft:terrainglobals.glsl","92fce71e096e2bab975c192b0b52c433a1fe2694c13259e2d4654bde3814aac9",
        "minecraft:sample_lightmap.glsl","a9304edbf7f4cae659177dc47b813a5b8a715c9cb3e4a16214273a871eb508e9",
        "minecraft:projection.glsl","54ad58fcd6b3811992a9432628dba49900b2d9d33e075424224b0e7f0603818e",
        "minecraft:globals.glsl","85784f154ace14501ff33310a69b48e6d01f3af30b52444ce6192b9049be1060",
        "minecraft:fog.glsl","c96edf62b3dca66b2dc314516b0cd74f2e12fbe7514342e28a5440ad8dfc49be",
        "minecraft:texture_sampling.glsl","ffd52a2ed71dc43f83295c71ba7648e20b5b81d3ae43b8d64b027b1881c55c7b",
        "minecraft:oit.glsl","d90c5f454fffff35e81673b9806531d468d9597557a2bf7d9e37ed5cd7c18989");
    private static final Map<String,String> SODIUM=Map.of(
        "sodium:chunk_vertex.glsl","58f051273c13a4eab5658ff74a1e9725b419bbe92b3b1461520ac675a17fd5e1",
        "sodium:fog.glsl","48bf6249e3e8131e67504f4147122041efa871be20725525744f7c717e8898b4",
        "sodium:globals.glsl","59ab558e82d31ea2a0a2b2d95b6a63e4cf960d676cc73c309b7c17e47d02d057",
        "sodium:chunk_material.glsl","456e00dc7904a2e003fe4e1f06b2023bc3189cb7259fec77ba3e854fc4f7a452",
        "minecraft:oit.glsl","d90c5f454fffff35e81673b9806531d468d9597557a2bf7d9e37ed5cd7c18989");
    private TerrainShaderProof(int value,boolean stock) {previous=CURRENT.get();CURRENT.set(value);previousStock=STOCK.get();STOCK.set(stock);}
    /** True while compiling a pipeline whose vertex and fragment shaders and every include are byte identical to stock vanilla terrain. */
    public static boolean stockTerrain() {return STOCK.get();}
    public static int current() {return CURRENT.get();}
    public static boolean eligibleShape(DepthStencilState depth,PrimitiveTopology topology,PolygonMode polygon) {
        return depth!=null && depth.writeDepth() && (depth.depthTest()==CompareOp.LESS_THAN || depth.depthTest()==CompareOp.LESS_THAN_OR_EQUAL
            || depth.depthTest()==CompareOp.GREATER_THAN || depth.depthTest()==CompareOp.GREATER_THAN_OR_EQUAL)
            && depth.depthBiasConstant()==0 && depth.depthBiasScaleFactor()==0 && polygon==PolygonMode.FILL
            && (topology==PrimitiveTopology.TRIANGLES || topology==PrimitiveTopology.QUADS);
    }
    public static TerrainShaderProof enter(RenderPipeline pipeline,ShaderSource source) {
        int proof=validate(pipeline,source);
        if(Boolean.getBoolean("puremetal.terrainOcclusionProbe")) {
            var vertex=pipeline.getShaders().get(ShaderType.VERTEX);
            if(vertex!=null && (vertex.getNamespace().equals("sodium") || vertex.getPath().contains("terrain"))) {
                com.mojang.logging.LogUtils.getLogger().info("Pure Metal: terrain probe shader {} sources={} proof={} defines={}",
                    pipeline.getLocation(),pipeline.getShaders(),proof,pipeline.getShaderDefines());
            }
        }
        return new TerrainShaderProof(proof,stock(pipeline,source));
    }
    /** Relaxed terrain math over the LOD-patched fragment text (-Dpuremetal.fastTerrainMath.allowLodPatched=true); not proven, off by default. */
    private static final boolean ALLOW_LOD_PATCHED=Boolean.getBoolean("puremetal.fastTerrainMath.allowLodPatched");
    /** RenderPipelines.ALPHA_CUTOUT_THRESHOLD_CUTOUT_TERRAIN of Minecraft 26.3 (private there; read from the class constant). Re-check on a version bump. */
    private static final float VANILLA_CUTOUT_ALPHA=0.5F;
    private static boolean isFloat(Object value,float expected) {
        try {return Float.parseFloat(String.valueOf(value))==expected;} catch(NumberFormatException bad) {return false;}
    }
    private static boolean hash(String value,String expected) throws Exception {
        return value!=null && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).equals(expected);
    }
    private static int validate(RenderPipeline pipeline,ShaderSource borrowed) {
        try {
            ShaderSource source=LodTextureRepeat.originalSource(borrowed);
            if(!pipeline.getShaderDefines().values().isEmpty()
                || !java.util.Set.of("MULTIDRAW_TERRAIN","USE_FOG","USE_VERTEX_COMPRESSION","VULKAN").containsAll(pipeline.getShaderDefines().flags())) return 0;
            Identifier vertex=pipeline.getShaders().get(ShaderType.VERTEX);
            boolean sodium=Identifier.fromNamespaceAndPath("sodium","blocks/block_layer_opaque").equals(vertex);
            if(sodium && !pipeline.getShaderDefines().flags().contains("USE_VERTEX_COMPRESSION")) return 0;
            if(!sodium && !Identifier.fromNamespaceAndPath("minecraft","core/terrain").equals(vertex)) return 0;
            if(!hash(source.getShader(vertex,ShaderType.VERTEX),sodium?
                "eb663f22c1be14a3a1e391601e466a77b420f8e087f2edf2b06c0c9e64342ce8":
                "001120e03dd7b9d690d3c98a1563334497c465f79ce14dc557d3d4caa02299a0")) return 0;
            if(!vertex.equals(pipeline.getShaders().get(ShaderType.FRAGMENT))
                || !hash(source.getShader(vertex,ShaderType.FRAGMENT),sodium?
                    "380c49c7463bed6382cb993e39a55e4a07e4c00462f8980fc020b66e29bd27a1":
                    "e440d13f0c3c3aa0ae584a7e006410e4318922c5bc3c94a7a1be28b89b221bf9")) return 0;
            for(var entry:(sodium?SODIUM:VANILLA).entrySet()) {
                var include=source.getInclude(Identifier.parse(entry.getKey()));
                if(include==null || include.includeResultPtr()==0) return 0;
                var result=ShadercIncludeResult.create(include.includeResultPtr());
                long length=result.content_length();
                long address=org.lwjgl.system.MemoryUtil.memGetAddress(result.address()+ShadercIncludeResult.CONTENT);
                if(address==0 || length<=0 || length>1048576) return 0;
                var content=org.lwjgl.system.MemoryUtil.memByteBuffer(address,(int)length);
                // shaderc includes have an explicit byte length; they need not be NUL terminated.
                if(content==null || !hash(StandardCharsets.UTF_8.decode(content).toString(),entry.getValue())) return 0;
            }
            return sodium?2:1;
        } catch(Exception unsupported) {return 0;}
    }
    /** Stock vanilla terrain shader (any define set the vanilla terrain pipelines use, including cutout and OIT), no resource pack or mod edits. */
    private static boolean stock(RenderPipeline pipeline,ShaderSource borrowed) {
        try {
            ShaderSource source=LodTextureRepeat.originalSource(borrowed);
            // The LOD patch (and any other wrapper) compiles different fragment text than the hashes below prove: relaxed math only on it with explicit approval.
            if(source!=borrowed && !ALLOW_LOD_PATCHED) return false;
            Identifier vertex=pipeline.getShaders().get(ShaderType.VERTEX);
            if(!Identifier.fromNamespaceAndPath("minecraft","core/terrain").equals(vertex) || !vertex.equals(pipeline.getShaders().get(ShaderType.FRAGMENT))) return false;
            for(String flag:pipeline.getShaderDefines().flags()) {
                if(!(flag.startsWith("OIT_") || java.util.Set.of("MULTIDRAW_TERRAIN","USE_FOG","USE_VERTEX_COMPRESSION","VULKAN").contains(flag))) return false;
            }
            for(var define:pipeline.getShaderDefines().values().entrySet()) {
                if(!define.getKey().equals("ALPHA_CUTOUT") || !isFloat(define.getValue(),VANILLA_CUTOUT_ALPHA)) return false;
            }
            if(!hash(source.getShader(vertex,ShaderType.VERTEX),"001120e03dd7b9d690d3c98a1563334497c465f79ce14dc557d3d4caa02299a0")
                || !hash(source.getShader(vertex,ShaderType.FRAGMENT),"e440d13f0c3c3aa0ae584a7e006410e4318922c5bc3c94a7a1be28b89b221bf9")) return false;
            for(var group:java.util.List.of(VANILLA,OIT)) {
                for(var entry:group.entrySet()) {
                    var include=source.getInclude(Identifier.parse(entry.getKey()));
                    if(include==null || include.includeResultPtr()==0) return false;
                    var result=ShadercIncludeResult.create(include.includeResultPtr());
                    long length=result.content_length();
                    long address=org.lwjgl.system.MemoryUtil.memGetAddress(result.address()+ShadercIncludeResult.CONTENT);
                    if(address==0 || length<=0 || length>1048576) return false;
                    var content=org.lwjgl.system.MemoryUtil.memByteBuffer(address,(int)length);
                    if(content==null || !hash(StandardCharsets.UTF_8.decode(content).toString(),entry.getValue())) return false;
                }
            }
            return true;
        } catch(Exception unsupported) {return false;}
    }
    @Override public void close() {CURRENT.set(previous);STOCK.set(previousStock);}
}
