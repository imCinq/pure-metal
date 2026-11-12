package io.github.imcinq.puremetal.backend;

import io.github.imcinq.puremetal.Redact;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

/**
 * A compiled pipeline. Metal bakes the depth attachment format into the pipeline state, so one state is kept per
 * depth format actually used (none, D32 and so on); the common ones are built up front on the compile thread.
 */
final class MetalPipeline implements BackendRenderPipeline {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Buffer argument slots: uniforms 0..14, push constants 15, vertex buffers 16..23 (see puremetal.m). */
    static final int MAX_UNIFORM_BUFFERS = 15;
    static final int PUSH_CONSTANT_INDEX = 15;
    static final int MAX_SAMPLERS = 16;
    static final int MAX_TEXTURES = 31;
    private static final int NO_DEPTH = -1;

    private final MetalDevice device;
    private final String name;
    private final List<BindGroupLayout.UniformDescription> uniforms;
    private final Bindings bindings;
    private final long vertexFunction;
    private final long fragmentFunction;
    /** Fragment variant with the RGSS early-out specialized off; 0 unless the shader has it and the A/B tool is active. */
    private final long fragmentFunctionOff;
    /** Hand written terrain shader pair, alternate to the translated one; only built for the nativeTerrain A/B (otherwise it is the primary pair). */
    private final long nativeVertexFunction;
    private final long nativeFragmentFunction;
    private final long depthState;
    private final int[] descriptor;
    /** Pipeline state per depth format code + 1 (index 0 = no depth attachment); 0 = not built yet. Variant 0 primary, 1 RGSS early-out off, 2 native terrain. */
    private final long[][] states = new long[3][MetalConst.FORMAT_NAMES.size() + 1];
    private final boolean[][] failedStates = new boolean[3][MetalConst.FORMAT_NAMES.size() + 1];
    final int primitive;
    final boolean triangleFan;
    final boolean cull;
    final boolean wireframe;
    final float depthBiasConstant;
    final float depthBiasSlope;
    /** 0 unsupported, 1 pinned vanilla transform, 2 pinned Sodium transform; captured per compilation. */
    final int terrainProbeTransform;
    final boolean terrainProbeReverse;
    private boolean closed;

    /** Where each frontend uniform index lands in Metal's argument tables. */
    record Bindings(UniformType[] types, int[] buffers, int[] textures, int[] samplers) {
        int count() {
            return this.types.length;
        }

        int bufferIndex(final int i) {
            return Math.max(this.buffers[i], 0);
        }

        int textureIndex(final int i) {
            return Math.max(this.textures[i], 0);
        }

        int samplerIndex(final int i) {
            return Math.max(this.samplers[i], 0);
        }

        static Bindings of(final List<BindGroupLayout.UniformDescription> uniforms) {
            int n = uniforms.size();
            UniformType[] types = new UniformType[n];
            int[] buffers = new int[n];
            int[] textures = new int[n];
            int[] samplers = new int[n];
            int b = 0, t = 0, s = 0;
            for (int i = 0; i < n; i++) {
                types[i] = uniforms.get(i).type();
                buffers[i] = textures[i] = samplers[i] = -1;
                switch (types[i]) {
                    case UNIFORM_BUFFER -> buffers[i] = b++;
                    case COMBINED_IMAGE_SAMPLER -> {
                        textures[i] = t++;
                        samplers[i] = s++;
                    }
                    case TEXEL_BUFFER -> textures[i] = t++;
                }
            }
            if (b > MAX_UNIFORM_BUFFERS || t > MAX_TEXTURES || s > MAX_SAMPLERS) {
                throw new IllegalStateException("Too many bindings for Metal: " + b + " buffers, " + t + " textures, " + s + " samplers");
            }
            return new Bindings(types, buffers, textures, samplers);
        }
    }

    private MetalPipeline(final MetalDevice device, final CreateInfo info, final Bindings bindings, final long vertexFunction, final long fragmentFunction, final long fragmentFunctionOff,
                          final long nativeVertexFunction, final long nativeFragmentFunction) {
        this.device = device;
        this.fragmentFunctionOff = fragmentFunctionOff;
        this.nativeVertexFunction = nativeVertexFunction;
        this.nativeFragmentFunction = nativeFragmentFunction;
        this.name = info.name();
        this.uniforms = List.copyOf(info.uniforms());
        this.bindings = bindings;
        this.vertexFunction = vertexFunction;
        this.fragmentFunction = fragmentFunction;
        DepthStencilState depth = info.depthStencilState();
        if(io.github.imcinq.puremetal.perf.TerrainProbeScope.ENABLED && io.github.imcinq.puremetal.perf.TerrainShaderProof.current()!=0) {
            LOGGER.info("Pure Metal: terrain probe pipeline shape {} proof={} depth={} topology={} polygon={}",
                this.name,io.github.imcinq.puremetal.perf.TerrainShaderProof.current(),depth,info.primitiveTopology(),info.polygonMode());
        }
        this.terrainProbeReverse=depth!=null && (depth.depthTest()==com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN
            || depth.depthTest()==com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN_OR_EQUAL);
        this.terrainProbeTransform = io.github.imcinq.puremetal.perf.TerrainShaderProof.eligibleShape(depth,info.primitiveTopology(),info.polygonMode())
            ? io.github.imcinq.puremetal.perf.TerrainShaderProof.current() : 0;
        this.depthBiasConstant = depth == null ? 0.0F : depth.depthBiasConstant();
        this.depthBiasSlope = depth == null ? 0.0F : depth.depthBiasScaleFactor();
        this.primitive = MetalConst.primitive(info.primitiveTopology());
        this.triangleFan = info.primitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
        this.cull = info.cull();
        this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
        this.descriptor = buildDescriptor(info);
        // Acquire native ownership last, after all fallible Java descriptor preparation.
        this.depthState = depth == null
            ? device.acquireDepthState(0, 0, 0)
            : device.acquireDepthState(1, MetalConst.compare(depth.depthTest()), depth.writeDepth() ? 1 : 0);
    }

    /**
     * Compiles everything on the calling (worker) thread. Returns null, with a logged reason, if it can't. A pipeline proven to be
     * the pinned Sodium solid terrain shader first tries the hand written MSL and falls back to the translation on any failure.
     */
    static @Nullable MetalPipeline compile(final MetalDevice device, final CreateInfo info) {
        if (io.github.imcinq.puremetal.perf.NativeTerrainShader.proven()) {
            MetalPipeline pipeline = compile(device, info, true);
            if (pipeline != null) {
                return pipeline;
            }
        }
        return compile(device, info, false);
    }

    private static @Nullable MetalPipeline compile(final MetalDevice device, final CreateInfo info, final boolean tryNative) {
        long vertexFunction = 0L;
        long fragmentFunction = 0L;
        long fragmentFunctionOff = 0L;
        long nativeVertex = 0L;
        long nativeFragment = 0L;
        MetalPipeline pipeline = null;
        boolean nativePrimary = false;
        try {
            Bindings bindings = Bindings.of(info.uniforms());
            boolean points = info.primitiveTopology() == PrimitiveTopology.POINTS;
            String nativeSource = tryNative && !points ? NativeTerrain.source(info, bindings) : null;
            if (nativeSource != null) {
                try {
                    String label = info.name() + " (native terrain)";
                    nativeVertex = device.compileFunction(new MetalShaderTranslator.Result(nativeSource, NativeTerrain.VERTEX_ENTRY), label + " VERTEX");
                    nativeFragment = device.compileFunction(new MetalShaderTranslator.Result(nativeSource, NativeTerrain.FRAGMENT_ENTRY), label + " FRAGMENT");
                } catch (RuntimeException e) {
                    LOGGER.warn("Pure Metal: native terrain shader rejected for {}, the translated shader stays: {}", info.name(), Redact.home(e.getMessage()));
                    device.releaseFunction(nativeVertex);
                    device.releaseFunction(nativeFragment);
                    nativeVertex = 0L;
                    nativeFragment = 0L;
                }
            }
            // Without the nativeTerrain A/B the hand written pair is the only one; with it both pairs are built and the device flag picks.
            nativePrimary = nativeVertex != 0L && !io.github.imcinq.puremetal.perf.NativeTerrainShader.AB;
            if (nativePrimary) {
                vertexFunction = nativeVertex;
                fragmentFunction = nativeFragment;
                nativeVertex = 0L;
                nativeFragment = 0L;
                LOGGER.info("Pure Metal: native terrain shader built for {}", info.name());
            } else {
                if (nativeVertex != 0L) {
                    LOGGER.info("Pure Metal: native terrain A/B variant built for {}", info.name());
                }
                for (CreateInfo.Shader shader : info.shaders()) {
                    MetalShaderTranslator.Result msl = MetalShaderTranslator.translate(shader, bindings, points && shader.module().type() == ShaderType.VERTEX);
                    String label = info.name() + " (" + shader.name() + ") " + shader.module().type();
                    int constant = shader.module().type() != ShaderType.VERTEX
                        && msl.source().contains("function_constant(" + io.github.imcinq.puremetal.lod.LodTextureRepeat.EARLY_OUT_CONSTANT_ID + ")")
                        ? io.github.imcinq.puremetal.lod.LodTextureRepeat.EARLY_OUT_CONSTANT_ID : -1;
                    long function = constant >= 0 ? device.compileFunction(msl, label, constant, 1) : device.compileFunction(msl, label);
                    if (shader.module().type() == ShaderType.VERTEX) {
                        device.releaseFunction(vertexFunction);
                        vertexFunction = function;
                    } else {
                        device.releaseFunction(fragmentFunction);
                        fragmentFunction = function;
                    }
                    if (constant >= 0 && io.github.imcinq.puremetal.bench.Bench.abRequested()) {
                        device.releaseFunction(fragmentFunctionOff);
                        fragmentFunctionOff = 0L;
                        fragmentFunctionOff = device.compileFunction(msl, label, constant, 0);
                        LOGGER.info("Pure Metal: RGSS early-out A/B variants built for {}", info.name());
                    }
                }
            }
            if (vertexFunction == 0L) {
                throw new IllegalStateException("no vertex shader");
            }
            pipeline = new MetalPipeline(device, info, bindings, vertexFunction, fragmentFunction, fragmentFunctionOff, nativeVertex, nativeFragment);
            // Build the variants up front so the render thread never stalls on a first draw. Vanilla only uses
            // D32_FLOAT depth targets; pipelines without depth testing still get drawn into passes that have one.
            int d32 = MetalConst.format(com.mojang.renderpearl.api.GpuFormat.D32_FLOAT);
            for (int variant = 0; variant < 3; variant++) {
                if ((variant == 1 && fragmentFunctionOff == 0L) || (variant == 2 && nativeVertex == 0L)) {
                    continue;
                }
                long state = pipeline.state(info.depthStencilState() != null ? d32 : NO_DEPTH, variant);
                if (info.depthStencilState() == null) {
                    state = pipeline.state(d32, variant);
                }
                if ((nativePrimary || variant == 2) && state == 0L) {
                    // Metal refused the hand written pair in this pipeline (its reason is logged above): use the translation.
                    throw new IllegalStateException("native terrain pipeline state rejected");
                }
            }
            return pipeline;
        } catch (RuntimeException e) {
            if (nativePrimary || nativeVertex != 0L) {
                LOGGER.warn("Pure Metal: native terrain shader unusable for {}, the translated shader stays: {}", info.name(), Redact.home(e.getMessage()));
            } else {
                LOGGER.error("Pure Metal couldn't compile pipeline {}: {}", info.name(), Redact.home(e.getMessage()));
            }
            if (pipeline != null) {
                pipeline.close();
            } else {
                device.releaseFunction(vertexFunction);
                device.releaseFunction(fragmentFunction);
                device.releaseFunction(fragmentFunctionOff);
                device.releaseFunction(nativeVertex);
                device.releaseFunction(nativeFragment);
            }
            return null;
        }
    }

    private static int[] buildDescriptor(final CreateInfo info) {
        List<CreateInfo.VertexBuffer> buffers = info.vertexBuffers();
        List<CreateInfo.AttribBinding> attributes = info.attribBindings();
        List<@Nullable ColorTargetState> colors = info.colorTargetStates();
        int[] d = new int[5 + buffers.size() * 3 + attributes.size() * 4 + colors.size() * 9];
        d[0] = MetalConst.primitive(info.primitiveTopology());
        d[1] = NO_DEPTH;
        d[2] = buffers.size();
        d[3] = attributes.size();
        d[4] = colors.size();
        int c = 5;
        for (CreateInfo.VertexBuffer buffer : buffers) {
            d[c++] = buffer.bufferSlot();
            d[c++] = buffer.stride();
            d[c++] = buffer.stepRate();
        }
        for (CreateInfo.AttribBinding attribute : attributes) {
            d[c++] = attribute.location();
            d[c++] = attribute.bufferSlot();
            d[c++] = attribute.offset();
            d[c++] = MetalConst.format(attribute.format());
        }
        for (ColorTargetState color : colors) {
            if (color == null) {
                d[c] = -1;
                c += 9;
                continue;
            }
            d[c++] = MetalConst.format(color.format());
            d[c++] = color.writeMask();
            if (color.blendFunction().isPresent()) {
                var blend = color.blendFunction().get();
                d[c++] = 1;
                d[c++] = MetalConst.blendFactor(blend.color().sourceFactor());
                d[c++] = MetalConst.blendFactor(blend.color().destFactor());
                d[c++] = MetalConst.blendOp(blend.color().op());
                d[c++] = MetalConst.blendFactor(blend.alpha().sourceFactor());
                d[c++] = MetalConst.blendFactor(blend.alpha().destFactor());
                d[c++] = MetalConst.blendOp(blend.alpha().op());
            } else {
                d[c++] = 0;
                c += 6;
            }
        }
        return d;
    }

    /** The pipeline state for a pass whose depth attachment has this format code (-1 = none); 0 if it can't be built. */
    long state(final int depthFormat) {
        if (this.nativeVertexFunction != 0L && this.device.nativeTerrain) {
            return this.state(depthFormat, 2);
        }
        return this.state(depthFormat, this.fragmentFunctionOff != 0L && !this.device.rgssEarlyOut ? 1 : 0);
    }

    private long state(final int depthFormat, final int variant) {
        int slot = depthFormat + 1;
        long state = this.states[variant][slot];
        if (state != 0L || this.failedStates[variant][slot]) {
            return state;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer desc = stack.mallocInt(this.descriptor.length);
            desc.put(0, this.descriptor);
            desc.put(1, depthFormat);
            ByteBuffer label = stack.UTF8(this.name);
            ByteBuffer err = stack.calloc(1024);
            state = MetalNative.pipelineCreate(
                this.device.handle(), variant == 2 ? this.nativeVertexFunction : this.vertexFunction,
                variant == 2 ? this.nativeFragmentFunction : variant == 1 ? this.fragmentFunctionOff : this.fragmentFunction,
                org.lwjgl.system.MemoryUtil.memAddress(desc), org.lwjgl.system.MemoryUtil.memAddress(label),
                org.lwjgl.system.MemoryUtil.memAddress(err), err.capacity()
            );
            if (state == 0L) {
                this.failedStates[variant][slot] = true;
                LOGGER.error("Pure Metal couldn't build pipeline {} (depth format {}): {}", this.name, depthFormat,
                    Redact.home(org.lwjgl.system.MemoryUtil.memUTF8(org.lwjgl.system.MemoryUtil.memAddress(err))));
            }
        }
        this.states[variant][slot] = state;
        return state;
    }

    Bindings bindings() {
        return this.bindings;
    }

    long depthState() {
        return this.depthState;
    }

    String name() {
        return this.name;
    }

    List<BindGroupLayout.UniformDescription> uniforms() {
        return this.uniforms;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            for (long[] variantStates : this.states) {
                for (long state : variantStates) {
                    MetalNative.release(state);
                }
            }
            this.device.releaseDepthState(this.depthState);
            this.device.releaseFunction(this.vertexFunction);
            this.device.releaseFunction(this.fragmentFunction);
            this.device.releaseFunction(this.fragmentFunctionOff);
            this.device.releaseFunction(this.nativeVertexFunction);
            this.device.releaseFunction(this.nativeFragmentFunction);
        }
    }
}
