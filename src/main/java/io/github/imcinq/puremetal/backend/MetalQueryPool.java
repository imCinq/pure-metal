package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.OptionalLong;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Timestamp query pool backed by native refcounted slots in {@code puremetal.m}.
 *
 * <p>Each {@link #writeTimestamp} bumps the slot's generation and clears readiness immediately, so late GPU completions
 * from an earlier use of the same index can never overwrite a newer pending query. On Metal 3, frame-level timestamps
 * resolve from command-buffer {@code GPUStartTime}/{@code GPUEndTime} with zero extra encoders or barriers; on Metal 4,
 * per-write hardware timestamps resolve from a per-frame {@code MTL4CounterHeap} (with command-buffer fallback).
 * Closing the pool releases Java's reference while keeping native storage alive until any in-flight submit finishes.
 */
final class MetalQueryPool implements GpuQueryPool {
    private static final int READ_CHUNK = 1024;
    private static final int MAX_SIZE = 1 << 20;

    private final MetalDevice device;
    private final int size;
    private long handle;
    private boolean closed;

    MetalQueryPool(final MetalDevice device, final int size) {
        if (size < 0 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Query pool size must be in [0, " + MAX_SIZE + "]: " + size);
        }
        this.device = device;
        this.size = size;
        if (size > 0) {
            long h = MetalNative.queryPoolCreate(size);
            if (h == 0L) {
                throw new IllegalStateException("Failed to allocate native Metal query pool of size " + size);
            }
            this.handle = h;
        }
    }

    MetalDevice device() {
        return this.device;
    }

    @Override
    public int size() {
        return this.size;
    }

    synchronized void writeTimestamp(final int index, final boolean inRenderPass) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException("Query index " + index + " out of bounds for pool size " + this.size);
        }
        if (this.closed || this.handle == 0L || this.device.isShuttingDown()) {
            return;
        }
        if (MetalNative.queryWrite(this.device.handle(), this.handle, index, inRenderPass ? 1 : 0) == 0) {
            throw new IllegalStateException("Failed to record Metal timestamp query (per-submit query limit reached)");
        }
    }

    @Override
    public synchronized OptionalLong getValue(final int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException("Query index " + index + " out of bounds for pool size " + this.size);
        }
        if (this.closed || this.handle == 0L) {
            return OptionalLong.empty();
        }
        long ns = MetalNative.queryRead(this.handle, index);
        return ns >= 0L ? OptionalLong.of(ns) : OptionalLong.empty();
    }

    @Override
    public synchronized OptionalLong[] getValues(final int index, final int count) {
        if (index < 0 || count < 0 || count > this.size - index) {
            throw new IndexOutOfBoundsException(
                "Query range [" + index + ", " + index + " + " + count + ") out of bounds for pool size " + this.size
            );
        }
        OptionalLong[] values = new OptionalLong[count];
        if (count == 0) {
            return values;
        }
        if (this.closed || this.handle == 0L) {
            Arrays.fill(values, OptionalLong.empty());
            return values;
        }
        int offset = 0;
        while (offset < count) {
            int n = Math.min(READ_CHUNK, count - offset);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer out = stack.mallocLong(n);
                MetalNative.queryReadRange(this.handle, index + offset, n, MemoryUtil.memAddress(out));
                for (int i = 0; i < n; i++) {
                    long ns = out.get(i);
                    values[offset + i] = ns >= 0L ? OptionalLong.of(ns) : OptionalLong.empty();
                }
            }
            offset += n;
        }
        return values;
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        long h = this.handle;
        this.handle = 0L;
        if (h != 0L) {
            MetalNative.queryPoolDestroy(h);
        }
    }
}
