package com.google.android.projection.common;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

@UsedByNative
public final class BufferPool {
    public static final int SMALL_BUFFER_SIZE = 512;
    public static final int MEDIUM_BUFFER_SIZE = 16384;
    public static final int LARGE_BUFFER_SIZE = 262144;

    private static final int MAX_SMALL_BUFFERS = 120;
    private static final int MAX_MEDIUM_BUFFERS = 20;
    private static final int MAX_LARGE_BUFFERS = 10;
    private static final BufferPool INSTANCE = new BufferPool();

    private final Deque<ByteBuffer> smallBuffers = new ArrayDeque<>(MAX_SMALL_BUFFERS);
    private final Deque<ByteBuffer> mediumBuffers = new ArrayDeque<>(MAX_MEDIUM_BUFFERS);
    private final Deque<ByteBuffer> largeBuffers = new ArrayDeque<>(MAX_LARGE_BUFFERS);

    private BufferPool() {
        prefill(smallBuffers, SMALL_BUFFER_SIZE, 60);
        prefill(mediumBuffers, MEDIUM_BUFFER_SIZE, 10);
        prefill(largeBuffers, LARGE_BUFFER_SIZE, 5);
    }

    @UsedByNative
    public static ByteBuffer getBuffer(int size) {
        return INSTANCE.checkout(size);
    }

    @UsedByNative
    public static void returnBuffer(ByteBuffer buffer) {
        if (buffer != null && buffer.isDirect()) {
            INSTANCE.recycle(buffer);
        }
    }

    private static void prefill(Deque<ByteBuffer> pool, int size, int count) {
        for (int i = 0; i < count; i++) {
            pool.add(ByteBuffer.allocateDirect(size));
        }
    }

    private ByteBuffer checkout(int size) {
        Deque<ByteBuffer> pool = poolForSize(size);
        ByteBuffer buffer = null;
        synchronized (pool) {
            while (!pool.isEmpty() && buffer == null) {
                ByteBuffer candidate = pool.removeLast();
                if (candidate.capacity() >= size) {
                    buffer = candidate;
                }
            }
        }
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(bucketCapacity(size));
        }
        buffer.clear();
        buffer.limit(size);
        return buffer;
    }

    private void recycle(ByteBuffer buffer) {
        buffer.clear();
        Deque<ByteBuffer> pool = poolForSize(buffer.capacity());
        int max = maxForSize(buffer.capacity());
        synchronized (pool) {
            if (pool.size() < max) {
                pool.addLast(buffer);
            }
        }
    }

    private static int bucketCapacity(int size) {
        if (size <= SMALL_BUFFER_SIZE) return SMALL_BUFFER_SIZE;
        if (size <= MEDIUM_BUFFER_SIZE) return MEDIUM_BUFFER_SIZE;
        if (size <= LARGE_BUFFER_SIZE) return LARGE_BUFFER_SIZE;
        return size;
    }

    private Deque<ByteBuffer> poolForSize(int size) {
        if (size <= SMALL_BUFFER_SIZE) return smallBuffers;
        if (size <= MEDIUM_BUFFER_SIZE) return mediumBuffers;
        return largeBuffers;
    }

    private static int maxForSize(int size) {
        if (size <= SMALL_BUFFER_SIZE) return MAX_SMALL_BUFFERS;
        if (size <= MEDIUM_BUFFER_SIZE) return MAX_MEDIUM_BUFFERS;
        return MAX_LARGE_BUFFERS;
    }
}
