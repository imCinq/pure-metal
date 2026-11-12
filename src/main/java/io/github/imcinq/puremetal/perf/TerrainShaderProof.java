package io.github.imcinq.puremetal.perf;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo;
import io.github.imcinq.puremetal.backend.FastTerrainMath;
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
    private final Accepted previousRelaxed;
    /**
     * The exact compile input (CreateInfo) the proof was made for, with its family state; the backend only trusts it for that same object.
     * relaxed: the pipeline may compile relaxed. Otherwise it was refused (it compiles strict and waits for the family switch).
     */
    private record Accepted(CreateInfo info,FamilyState family,boolean relaxed) {}
    private static final ThreadLocal<Accepted> RELAXED=new ThreadLocal<>();
    /** Why a family is (turning) strict, in the words the settings page and F3 use. */
    public enum Why {PACK,SODIUM_VERSION,OTHER}
    /** A relaxed terrain pipeline of a family, which has to build a strict variant before the family can switch. */
    public interface Member {
        /** Start building the strict variant in the background (idempotent, quick). */
        void prepareStrict();
        /** The strict variant is built or its build failed (a failed member stays undrawn after the switch). */
        boolean strictDone();
    }
    /**
     * Math mode of one shader family for one resource generation, shared by every terrain pipeline of it. Relaxed pipelines draw relaxed until a member is refused.
     * Then the family only requests a switch: every relaxed member builds its strict variant in the background while it keeps drawing relaxed, and once all are
     * done the switch is published at the next frame boundary ({@link TerrainShaderProof#frameBoundary}) for all of them at once, so no frame mixes modes and
     * nothing blinks. Refused pipelines (compiled strict) wait for that same switch. A new resource generation (new ShaderSource) starts a fresh state.
     */
    public static final class FamilyState {
        private final String shader;
        private final boolean sodium;
        private volatile boolean requested;
        private volatile boolean published;
        private volatile boolean retired;
        private volatile Why why=Why.OTHER;
        private volatile String because;
        private volatile int generation;
        private final java.util.concurrent.CopyOnWriteArrayList<Member> members=new java.util.concurrent.CopyOnWriteArrayList<>();
        FamilyState(String shader,boolean sodium) {this.shader=shader;this.sodium=sodium;}
        /** The mode every draw of this family uses right now: true once the switch to strict math was published. Changes only at frame boundaries. */
        public boolean strict() {return published;}
        public boolean requested() {return requested;}
        public Why why() {return why;}
        public int relaxedMembers() {return members.size();}
        /** Registers a relaxed pipeline; if a switch is already requested it starts building its strict variant at once. */
        public void register(Member member) {
            members.add(member);
            if(requested) member.prepareStrict();
        }
        public void removeOnStrict(Member member) {
            members.remove(member);
            dispose();
        }
        /** Compilations that took this state in generation() and have not finished yet (they may still register a member or request the switch). */
        private final java.util.concurrent.atomic.AtomicInteger inFlight=new java.util.concurrent.atomic.AtomicInteger();
        void releaseCompile() {
            inFlight.decrementAndGet();
            dispose();
        }
        /**
         * Stops polling a retired state only when nothing can need it any more: no compilation in flight, no member, and no switch left to publish
         * (a refused pipeline of the old generation may still wait for it, so a requested switch is published first).
         */
        private void dispose() {
            if(retired && inFlight.get()==0 && members.isEmpty() && (!requested || published)) ACTIVE.remove(this);
        }
        synchronized void request(String reason,Why kind) {
            if(requested) return;
            because=reason;
            why=kind;
            requested=true;
            LOGGER.warn("Pure Metal: terrain shader family {} will switch to strict math ({}); relaxed pipelines build their strict variants first, then all switch at one frame boundary",shader,reason);
            for(Member member:members) {
                try {member.prepareStrict();} catch(RuntimeException failed) {LOGGER.error("Pure Metal: strict family member failed",failed);}
            }
        }
        /** Render thread, once per frame: publish the switch when every relaxed member has its strict variant. */
        void poll() {
            if(published || !requested) return;
            for(Member member:members) if(!member.strictDone()) return;
            generation++;
            published=true;
            LOGGER.info("Pure Metal: terrain shader family {} is strict now (generation {}, {} relaxed pipeline(s) switched together)",shader,generation,members.size());
            dispose();
        }
        void retire() {retired=true;dispose();}
    }
    /** Every family state that may still have to publish a switch (the current generations and retired ones that still have pipelines). */
    private static final java.util.concurrent.CopyOnWriteArrayList<FamilyState> ACTIVE=new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The current resource generation of each family: its source identity, family state and stock proof. */
    private static final class Generation {
        final java.lang.ref.WeakReference<ShaderSource> source;
        final FamilyState state;
        private Boolean stock;
        Generation(ShaderSource source,FamilyState state) {this.source=new java.lang.ref.WeakReference<>(source);this.state=state;}
        synchronized boolean stock(Family family,ShaderSource original) throws Exception {
            if(stock==null) stock=sourceStock(family,original);
            return stock;
        }
    }
    private static final java.util.concurrent.ConcurrentHashMap<Identifier,Generation> GENERATIONS=new java.util.concurrent.ConcurrentHashMap<>();
    private static Generation generation(Family family,ShaderSource original) {
        synchronized(family) {
            Generation current=GENERATIONS.get(family.shader());
            if(current==null || current.source.get()!=original) {
                // A resource reload (new ShaderSource): fresh state, so removing a shader changing pack restores relaxed math without a restart.
                if(current!=null) current.state.retire();
                current=new Generation(original,new FamilyState(family.shader().toString(),family.shader().getNamespace().equals("sodium")));
                GENERATIONS.put(family.shader(),current);
                ACTIVE.add(current.state);
            }
            // Taken under the same lock as retire(): a state with a compilation in flight is never dropped from ACTIVE until that compilation ends.
            current.state.inFlight.incrementAndGet();
            return current;
        }
    }
    /** Called once per frame on the render thread, before any pass: publishes pending family switches (see {@link FamilyState}). */
    public static void frameBoundary() {
        for(FamilyState state:ACTIVE) state.poll();
    }
    /** The family states of the current resource generation, for the status line. */
    public static java.util.List<FamilyState> currentStates() {
        java.util.ArrayList<FamilyState> states=new java.util.ArrayList<>();
        for(Generation generation:GENERATIONS.values()) states.add(generation.state);
        return states;
    }
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
    /** Vanilla core/block (the solid_block, cutout_block and translucent_block pipelines): the includes it reaches besides the OIT ones. */
    private static final Map<String,String> BLOCK=Map.of(
        "minecraft:fog.glsl","c96edf62b3dca66b2dc314516b0cd74f2e12fbe7514342e28a5440ad8dfc49be",
        "minecraft:dynamictransforms.glsl","13db01aebd7ceeabf7769b1d60d0d5691f88072f302350ca2499f5d962237c7a",
        "minecraft:projection.glsl","54ad58fcd6b3811992a9432628dba49900b2d9d33e075424224b0e7f0603818e",
        "minecraft:sample_lightmap.glsl","a9304edbf7f4cae659177dc47b813a5b8a715c9cb3e4a16214273a871eb508e9",
        "minecraft:oit.glsl","d90c5f454fffff35e81673b9806531d468d9597557a2bf7d9e37ed5cd7c18989");
    /** RenderPipelines.ALPHA_CUTOUT_THRESHOLD_CUTOUT_TERRAIN of Minecraft 26.3 (private there; read from the class constant). Re-check on a version bump. */
    private static final float VANILLA_CUTOUT_ALPHA=0.5F;
    /** Pinned stock text of one shader family version: both stages and every include they reach. */
    private record Variant(String vertexHash,String fragmentHash,Map<String,String> includes) {}
    /** A terrain shader family: one shader id (vertex and fragment share it), the ALPHA_CUTOUT values its pipelines use, and the stock versions known. */
    private record Family(Identifier shader,float[] alphaCutouts,Variant[] variants) {}
    /** ALPHA_CUTOUT: RenderPipelines threshold for cutout terrain / cutout_block (0.5) and its default for translucent and OIT terrain (0.1). */
    private static final float[] VANILLA_ALPHAS={VANILLA_CUTOUT_ALPHA,0.1F};
    /** Sodium 0.9.x pipelines (ShaderChunkRenderer): cutout 0.5, translucent 0.01. */
    private static final float[] SODIUM_ALPHAS={VANILLA_CUTOUT_ALPHA,0.01F};
    /** Sodium 0.9.3-beta.5 changed the vertex and fragment shader and chunk_vertex, fog and globals (camera relative positions); chunk_material and oit.glsl are unchanged. */
    private static final Map<String,String> SODIUM_BETA5=Map.of(
        "sodium:chunk_vertex.glsl","f81998545f37971d167e079b0ea78dee9ae99b660bbf7053053dcfd11e06a793",
        "sodium:fog.glsl","8f471f062405ed9928e2d298188601966f444072b33675b546b9ebffebe095be",
        "sodium:globals.glsl","bd44a4ef22ba629caf0c8f887807c340112635d464dc08bc04cfa88d88cfc518",
        "sodium:chunk_material.glsl","456e00dc7904a2e003fe4e1f06b2023bc3189cb7259fec77ba3e854fc4f7a452",
        "minecraft:oit.glsl","d90c5f454fffff35e81673b9806531d468d9597557a2bf7d9e37ed5cd7c18989");
    private static final Family[] FAMILIES={
        new Family(Identifier.fromNamespaceAndPath("minecraft","core/terrain"),VANILLA_ALPHAS,new Variant[] {
            new Variant("001120e03dd7b9d690d3c98a1563334497c465f79ce14dc557d3d4caa02299a0","e440d13f0c3c3aa0ae584a7e006410e4318922c5bc3c94a7a1be28b89b221bf9",VANILLA)}),
        new Family(Identifier.fromNamespaceAndPath("sodium","blocks/block_layer_opaque"),SODIUM_ALPHAS,new Variant[] {
            // Sodium 0.9.2 and 0.9.3-beta.1 (identical shaders)
            new Variant("eb663f22c1be14a3a1e391601e466a77b420f8e087f2edf2b06c0c9e64342ce8","380c49c7463bed6382cb993e39a55e4a07e4c00462f8980fc020b66e29bd27a1",SODIUM),
            // Sodium 0.9.3-beta.5
            new Variant("35b6b4307e2254adf90205733b34f96945939643b9fb6f0d8f3d69826cf74451","db2e196489be7a6be867d8ebfdfe96b58580c6de2b002dcedc3aebbe1e201abc",SODIUM_BETA5)}),
        new Family(Identifier.fromNamespaceAndPath("minecraft","core/block"),VANILLA_ALPHAS,new Variant[] {
            new Variant("3c2508a469916fa10c37d1e368b7de8e7505e289bb87c2134aaaf5fed992a9ea","bb8c8bbbae9227219e68ea3f0dab4ef6586c1dd4f745cab16c635c1553ed2ca9",BLOCK)})};
    private final FamilyState held;
    private TerrainShaderProof(int value,Accepted relaxed) {
        previous=CURRENT.get();CURRENT.set(value);previousRelaxed=RELAXED.get();RELAXED.set(relaxed);
        held=relaxed==null ? null : relaxed.family();
    }
    /** True while compiling exactly this CreateInfo, whose shader sources proved to be stock terrain (vanilla, Sodium or block), up to Pure Metal's own patch. */
    public static boolean relaxedMath(CreateInfo info) {return relaxedFamily(info)!=null;}
    /** The family state of the pipeline being compiled for this exact CreateInfo when its sources proved stock terrain (it may compile relaxed), else null. */
    public static FamilyState relaxedFamily(CreateInfo info) {
        Accepted accepted=RELAXED.get();
        return info!=null && accepted!=null && accepted.relaxed() && accepted.info()==info ? accepted.family() : null;
    }
    /** The family state of a terrain pipeline that was refused (it compiles strict and must not draw before the family switch), else null. */
    public static FamilyState refusedFamily(CreateInfo info) {
        Accepted accepted=RELAXED.get();
        return info!=null && accepted!=null && !accepted.relaxed() && accepted.info()==info ? accepted.family() : null;
    }
    public static int current() {return CURRENT.get();}
    public static boolean eligibleShape(DepthStencilState depth,PrimitiveTopology topology,PolygonMode polygon) {
        return depth!=null && depth.writeDepth() && (depth.depthTest()==CompareOp.LESS_THAN || depth.depthTest()==CompareOp.LESS_THAN_OR_EQUAL
            || depth.depthTest()==CompareOp.GREATER_THAN || depth.depthTest()==CompareOp.GREATER_THAN_OR_EQUAL)
            && depth.depthBiasConstant()==0 && depth.depthBiasScaleFactor()==0 && polygon==PolygonMode.FILL
            && (topology==PrimitiveTopology.TRIANGLES || topology==PrimitiveTopology.QUADS);
    }
    public static TerrainShaderProof enter(RenderPipeline pipeline,ShaderSource source,CreateInfo info) {
        int proof=validate(pipeline,source);
        if(Boolean.getBoolean("puremetal.terrainOcclusionProbe")) {
            var vertex=pipeline.getShaders().get(ShaderType.VERTEX);
            if(vertex!=null && (vertex.getNamespace().equals("sodium") || vertex.getPath().contains("terrain"))) {
                com.mojang.logging.LogUtils.getLogger().info("Pure Metal: terrain probe shader {} sources={} proof={} defines={}",
                    pipeline.getLocation(),pipeline.getShaders(),proof,pipeline.getShaderDefines());
            }
        }
        return new TerrainShaderProof(proof,FastTerrainMath.wanted() ? relaxedTerrain(pipeline,source,info) : null);
    }
    private static boolean isFloat(Object value,float expected) {
        try {return Float.parseFloat(String.valueOf(value))==expected;} catch(NumberFormatException bad) {return false;}
    }
    private static boolean hash(String value,String expected) throws Exception {
        return value!=null && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).equals(expected);
    }
    private static int validate(RenderPipeline pipeline,ShaderSource borrowed) {
        try {
            ShaderSource source=LodTextureRepeat.originalSource(borrowed);
            Identifier vertex=pipeline.getShaders().get(ShaderType.VERTEX);
            boolean sodium=Identifier.fromNamespaceAndPath("sodium","blocks/block_layer_opaque").equals(vertex);
            if(!sodium && !Identifier.fromNamespaceAndPath("minecraft","core/terrain").equals(vertex)) return 0;
            if(!pipeline.getShaderDefines().values().isEmpty() || !KNOWN_FLAGS.containsAll(pipeline.getShaderDefines().flags())) return 0;
            if(sodium && !pipeline.getShaderDefines().flags().contains("USE_VERTEX_COMPRESSION")) return 0;
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
    private static final java.util.Set<String> KNOWN_FLAGS=java.util.Set.of("MULTIDRAW_TERRAIN","USE_FOG","USE_VERTEX_COMPRESSION","VULKAN");
    /** Flags the stock terrain pipelines set: the four above, Sodium's USE_SPHERICAL_FOG (an #ifdef inside the hash-proven sodium:fog.glsl) and the stock OIT ones (OitPipelineSet, LevelRenderer.OIT_SNIPPET). */
    private static final java.util.Set<String> RELAXED_FLAGS=java.util.Set.of("MULTIDRAW_TERRAIN","USE_FOG","USE_VERTEX_COMPRESSION","VULKAN","USE_SPHERICAL_FOG",
        "OIT","OIT_ACCUMULATE","OIT_ALPHA_ONLY","OIT_DEPTH_BOUNDS","OIT_TRANSMITTANCE");
    private static boolean oitValue(String key,Object value) {
        int expected;
        switch(key) {
            case "OIT_WAVELET_RANK" -> expected=net.minecraft.client.renderer.LevelRenderer.OIT_WAVELET_RANK;
            case "OIT_COEFF_COUNT" -> expected=net.minecraft.client.renderer.LevelRenderer.OIT_COEFFICIENT_COUNT;
            case "OIT_COEFF_ATTACHMENT_COUNT" -> expected=net.minecraft.client.renderer.LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT;
            default -> {return false;}
        }
        return String.valueOf(value).equals(Integer.toString(expected));
    }
    private static final org.slf4j.Logger LOGGER=com.mojang.logging.LogUtils.getLogger();
    private static boolean includeMatches(ShaderSource source,Map.Entry<String,String> entry) throws Exception {
        var include=source.getInclude(Identifier.parse(entry.getKey()));
        if(include==null || include.includeResultPtr()==0) return false;
        var result=ShadercIncludeResult.create(include.includeResultPtr());
        long length=result.content_length();
        long address=org.lwjgl.system.MemoryUtil.memGetAddress(result.address()+ShadercIncludeResult.CONTENT);
        if(address==0 || length<=0 || length>1048576) return false;
        var content=org.lwjgl.system.MemoryUtil.memByteBuffer(address,(int)length);
        // shaderc includes have an explicit byte length; they need not be NUL terminated.
        return content!=null && hash(StandardCharsets.UTF_8.decode(content).toString(),entry.getValue());
    }
    private static boolean sourceStock(Family family,ShaderSource source) throws Exception {
        Identifier id=family.shader();
        for(Variant variant:family.variants()) {
            if(!hash(source.getShader(id,ShaderType.VERTEX),variant.vertexHash())
                || !hash(source.getShader(id,ShaderType.FRAGMENT),variant.fragmentHash())) continue;
            boolean all=true;
            for(var group:java.util.List.of(variant.includes(),OIT)) {
                for(var entry:group.entrySet()) all&=includeMatches(source,entry);
            }
            if(all) return true;
        }
        return false;
    }
    /**
     * Stock terrain shader of any family (vanilla terrain, Sodium terrain, vanilla block): no resource pack, mod or Iris edit, both stages and every include
     * hashed, only the define sets the stock pipelines use, and the compile input ({@code info}) names exactly the proven shaders. Pure Metal's own texture-repeat
     * and RGSS early-out patch is accepted: it wraps the source and only patches text it has itself proven stock, so the hashes are checked on the unpatched original.
     * Limit: the proof covers the GLSL source, defines and shader names; the SPIR-V that PipelineBuilder compiled from them is not re-derived.
     * Cheap for every other pipeline: the shader id is compared first.
     */
    private static Accepted relaxedTerrain(RenderPipeline pipeline,ShaderSource borrowed,CreateInfo info) {
        Identifier vertex=pipeline.getShaders().get(ShaderType.VERTEX);
        Family family=null;
        if(vertex!=null) for(Family candidate:FAMILIES) if(candidate.shader().equals(vertex)) family=candidate;
        if(family==null) return null;
        // Wireframe is a debug view, not a family member: decided before any other reason so it can never switch the family.
        if(pipeline.getPolygonMode()==PolygonMode.WIREFRAME || info.polygonMode()==PolygonMode.WIREFRAME) {
            LOGGER.info("Pure Metal: strict math kept for {} (wireframe debug pipeline)",info.name());
            return null;
        }
        ShaderSource original=LodTextureRepeat.originalSource(borrowed);
        Generation generation=generation(family,original);
        FamilyState state=generation.state;
        // generation() counted this compilation in flight; from here until the Accepted is returned, any failure must give the count back
        // (afterwards the proof object owns it and releases it on close).
        try {
            Why[] why={Why.OTHER};
            String reason=reject(pipeline,original,info,family,vertex,generation,why);
            if(reason==null) return new Accepted(info,state,true);
            LOGGER.info("Pure Metal: strict math kept for terrain pipeline {}: {}",info.name(),reason);
            // A refused member switches the whole family: this pipeline compiles strict and waits, the relaxed ones switch together with it.
            state.request(info.name()+": "+reason,why[0]);
            return new Accepted(info,state,false);
        } catch(Throwable failed) {
            state.releaseCompile();
            throw failed;
        }
    }
    private static String reject(RenderPipeline pipeline,ShaderSource source,CreateInfo info,Family family,Identifier vertex,Generation generation,Why[] why) {
        try {
            if(!vertex.equals(pipeline.getShaders().get(ShaderType.FRAGMENT))) return "vertex and fragment shaders differ";
            if(generation.state.requested()) return "its shader family is switching to strict ("+generation.state.because+")";
            // The compile input must be the proven pipeline: same name and exactly the proven vertex and fragment shader.
            if(!info.name().equals(String.valueOf(pipeline.getLocation())) || info.shaders().size()!=2) return "compile input does not match the pipeline";
            boolean hasVertex=false,hasFragment=false;
            for(var shader:info.shaders()) {
                if(!vertex.toString().equals(shader.name())) return "compiled shader "+shader.name()+" is not "+vertex;
                if(shader.module().type()==ShaderType.VERTEX) hasVertex=true; else if(shader.module().type()==ShaderType.FRAGMENT) hasFragment=true;
            }
            if(!hasVertex || !hasFragment) return "compile input lacks a vertex or fragment stage";
            for(String flag:pipeline.getShaderDefines().flags()) {
                if(!RELAXED_FLAGS.contains(flag)) return "unknown define "+flag;
            }
            for(var define:pipeline.getShaderDefines().values().entrySet()) {
                String key=define.getKey();
                if(key.equals("ALPHA_CUTOUT")) {
                    boolean known=false;
                    for(float alpha:family.alphaCutouts()) known|=isFloat(define.getValue(),alpha);
                    if(!known) return "unknown ALPHA_CUTOUT="+define.getValue();
                } else if(!oitValue(key,define.getValue())) return "unknown define "+key+"="+define.getValue();
            }
            if(!generation.stock(family,source)) {
                why[0]=generation.state.sodium ? Why.SODIUM_VERSION : Why.PACK;
                return "shader sources are not the stock ones (resource pack, mod, Iris or an unknown version)";
            }
            return null;
        } catch(Exception unsupported) {return "proof failed: "+unsupported;}
    }
    @Override public void close() {
        CURRENT.set(previous);RELAXED.set(previousRelaxed);
        // The compilation is over: its pipeline is registered (relaxed) or holds the state (refused).
        if(held!=null) held.releaseCompile();
    }
}
