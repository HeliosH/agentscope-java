/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.sandbox.process;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Fixed-size byte buffer retaining the beginning and most recent end of process output. */
public final class BoundedOutputBuffer {

    private final byte[] head;
    private final byte[] tail;
    private int headSize;
    private int tailSize;
    private int tailPosition;
    private long bytesSeen;

    public BoundedOutputBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("output capacity must be positive");
        }
        head = new byte[capacity / 2 + capacity % 2];
        tail = new byte[capacity / 2];
    }

    public synchronized void append(byte[] bytes, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        bytesSeen += length;
        for (int i = offset; i < offset + length; i++) {
            if (headSize < head.length) {
                head[headSize++] = bytes[i];
            } else if (tail.length > 0) {
                tail[tailPosition] = bytes[i];
                tailPosition = (tailPosition + 1) % tail.length;
                tailSize = Math.min(tailSize + 1, tail.length);
            }
        }
    }

    public synchronized boolean truncated() {
        return bytesSeen > head.length + tail.length;
    }

    public synchronized String text() {
        byte[] suffix = new byte[tailSize];
        int start = tailSize < tail.length ? 0 : tailPosition;
        for (int i = 0; i < tailSize; i++) {
            suffix[i] = tail[(start + i) % tail.length];
        }
        if (!truncated()) {
            byte[] all = new byte[headSize + tailSize];
            System.arraycopy(head, 0, all, 0, headSize);
            System.arraycopy(suffix, 0, all, headSize, tailSize);
            return new String(all, StandardCharsets.UTF_8);
        }
        return decode(head, headSize)
                + "\n... output truncated ...\n"
                + decode(suffix, suffix.length);
    }

    private static String decode(byte[] bytes, int length) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.IGNORE)
                    .onUnmappableCharacter(CodingErrorAction.IGNORE)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
        } catch (CharacterCodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
