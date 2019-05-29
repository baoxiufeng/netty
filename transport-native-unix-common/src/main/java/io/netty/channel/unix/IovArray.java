/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.unix;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundBuffer.MessageProcessor;
import io.netty.channel.internal.ChannelUtils;
import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;
import static io.netty.channel.unix.Limits.IOV_MAX;
import static io.netty.channel.unix.Limits.SSIZE_MAX;
import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.min;

/**
 * Represent an array of struct array and so can be passed directly over via JNI without the need to do any more
 * array copies.
 *
 * The buffers are written out directly into direct memory to match the struct iov. See also {@code man writev}.
 *
 * <pre>
 * struct iovec {
 *   void  *iov_base;
 *   size_t iov_len;
 * };
 * </pre>
 *
 * See also
 * <a href="http://rkennke.wordpress.com/2007/07/30/efficient-jni-programming-iv-wrapping-native-data-objects/"
 * >Efficient JNI programming IV: Wrapping native data objects</a>.
 */
public final class IovArray implements MessageProcessor {

    /** The size of an address which should be 8 for 64 bits and 4 for 32 bits. */
    private static final int ADDRESS_SIZE = Buffer.addressSize();

    /**
     * The size of an {@code iovec} struct in bytes. This is calculated as we have 2 entries each of the size of the
     * address.
     */
    private static final int IOV_SIZE = 2 * ADDRESS_SIZE;

    /**
     * The needed memory to hold up to {@code IOV_MAX} iov entries, where {@code IOV_MAX} signified
     * the maximum number of {@code iovec} structs that can be passed to {@code writev(...)}.
     */
    private static final int CAPACITY = IOV_MAX * IOV_SIZE;

    private final ByteBuffer memory;
    private final long memoryAddress;
    private int count;
    private long size;
    private long maxBytes = SSIZE_MAX;

    // Temporary IO ByteBufs.
    private final ByteBuf[] buffers = new ByteBuf[maxCount()];

    public IovArray() {
        memory = Buffer.allocateDirectWithNativeOrder(CAPACITY);
        memoryAddress = Buffer.memoryAddress(memory);
    }

    public void clear() {
        count = 0;
        size = 0;
    }

    public int maxCount() {
        return IOV_MAX;
    }

    /**
     * Add a {@link ByteBuf} to this {@link IovArray}.
     * @param buf The {@link ByteBuf} to add.
     * @return {@code true} if the entire {@link ByteBuf} has been added to this {@link IovArray}. Note in the event
     * that {@link ByteBuf} is a {@link CompositeByteBuf} {@code false} may be returned even if some of the components
     * have been added.
     */
    public boolean add(ByteBuf buf) {
        if (count == maxCount()) {
            // No more room!
            return false;
        } else if (buf.nioBufferCount() == 1) {
            final int len = buf.readableBytes();
            if (len == 0) {
                return true;
            }
            if (buf.hasMemoryAddress()) {
                return add(buf.memoryAddress(), buf.readerIndex(), len);
            } else {
                ByteBuffer nioBuffer = buf.internalNioBuffer(buf.readerIndex(), len);
                return add(Buffer.memoryAddress(nioBuffer), nioBuffer.position(), len);
            }
        } else {
            ByteBuffer[] buffers = buf.nioBuffers();
            for (ByteBuffer nioBuffer : buffers) {
                final int len = nioBuffer.remaining();
                if (len != 0 &&
                    (!add(Buffer.memoryAddress(nioBuffer), nioBuffer.position(), len) || count == IOV_MAX)) {
                    return false;
                }
            }
            return true;
        }
    }

    private boolean add(long addr, int offset, int len) {
        assert addr != 0;

        // If there is at least 1 entry then we enforce the maximum bytes. We want to accept at least one entry so we
        // will attempt to write some data and make progress.
        if (maxBytes - len < size && count > 0) {
            // If the size + len will overflow SSIZE_MAX we stop populate the IovArray. This is done as linux
            //  not allow to write more bytes then SSIZE_MAX with one writev(...) call and so will
            // return 'EINVAL', which will raise an IOException.
            //
            // See also:
            // - http://linux.die.net/man/2/writev
            return false;
        }
        final int baseOffset = idx(count);
        final int lengthOffset = baseOffset + ADDRESS_SIZE;

        size += len;
        ++count;

        if (ADDRESS_SIZE == 8) {
            // 64bit
            if (PlatformDependent.hasUnsafe()) {
                PlatformDependent.putLong(baseOffset + memoryAddress, addr + offset);
                PlatformDependent.putLong(lengthOffset + memoryAddress, len);
            } else {
                memory.putLong(baseOffset, addr + offset);
                memory.putLong(lengthOffset, len);
            }
        } else {
            assert ADDRESS_SIZE == 4;
            if (PlatformDependent.hasUnsafe()) {
                PlatformDependent.putInt(baseOffset + memoryAddress, (int) addr + offset);
                PlatformDependent.putInt(lengthOffset + memoryAddress, len);
            } else {
                memory.putInt(baseOffset, (int) addr + offset);
                memory.putInt(lengthOffset, len);
            }
        }
        return true;
    }

    /**
     * Returns the number if iov entries.
     */
    public int count() {
        return count;
    }

    /**
     * Returns the size in bytes
     */
    public long size() {
        return size;
    }

    /**
     * Set the maximum amount of bytes that can be added to this {@link IovArray} via {@link #add(ByteBuf)}.
     * <p>
     * This will not impact the existing state of the {@link IovArray}, and only applies to subsequent calls to
     * {@link #add(ByteBuf)}.
     * <p>
     * In order to ensure some progress is made at least one {@link ByteBuf} will be accepted even if it's size exceeds
     * this value.
     * @param maxBytes the maximum amount of bytes that can be added to this {@link IovArray} via {@link #add(ByteBuf)}.
     */
    public void maxBytes(long maxBytes) {
        this.maxBytes = min(SSIZE_MAX, checkPositive(maxBytes, "maxBytes"));
    }

    /**
     * Get the maximum amount of bytes that can be added to this {@link IovArray} via {@link #add(ByteBuf)}.
     * @return the maximum amount of bytes that can be added to this {@link IovArray} via {@link #add(ByteBuf)}.
     */
    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Returns the {@code memoryAddress} for the given {@code offset}.
     */
    public long memoryAddress(int offset) {
        return memoryAddress + idx(offset);
    }

    /**
     * Release the {@link IovArray}. Once release further using of it may crash the JVM!
     */
    public void release() {
        Buffer.free(memory);
    }

    @Override
    public boolean processMessage(Object msg) {
        return msg instanceof ByteBuf && add((ByteBuf) msg);
    }

    private static int idx(int index) {
        return IOV_SIZE * index;
    }

    /**
     * Write multiple bytes to the given {@link Socket}.
     * @param socket the {@link Socket} to write to.
     * @param in the collection which contains objects to write.
     * @param allocator the {@link ByteBufAllocator} which may be used to obtain temporary direct buffers.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - write failed due socket non-writable</li>
     *     <li>-1 - no buffers were found to write.
     *     <li>1+ - written bytes</li>
     * </ul>
     * @throws IOException If an I/O exception occurs during write.
     */
    public long writeTo(Socket socket, ChannelOutboundBuffer in, final ByteBufAllocator allocator) throws Exception {
        assert count == 0;

        ChannelOutboundBuffer.MessageProcessor processor = new ChannelOutboundBuffer.MessageProcessor() {
            private int idx;

            @Override
            public boolean processMessage(Object msg) {
                if (msg instanceof ByteBuf) {
                    ByteBuf buffer = (ByteBuf) msg;
                    if (!buffer.isDirect()) {
                        ByteBuf ioBuffer = UnixChannelUtil.ioBuffer(allocator, buffer.readableBytes());
                        ioBuffer.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
                        if (add(ioBuffer)) {
                            buffers[idx++] = ioBuffer;
                            return true;
                        } else {
                            // We could not fit in the ioBuffer anymore
                            ioBuffer.release();
                            return false;
                        }
                    } else {
                        return add(buffer);
                    }
                }
                return false;
            }
        };

        try {
            in.forEachFlushedMessage(processor);
            final int cnt = count();
            if (cnt == 0) {
                return -1;
            }

            final long expectedWrittenBytes = size();
            assert expectedWrittenBytes != 0;
            return socket.writevAddresses(memoryAddress(0), cnt);
        } finally {
            for (int i = 0; i < buffers.length; i++) {
                ByteBuf buffer = buffers[i];
                if (buffer == null) {
                    break;
                }
                buffers[i] = null;
                buffer.release();
            }
        }
    }
}
