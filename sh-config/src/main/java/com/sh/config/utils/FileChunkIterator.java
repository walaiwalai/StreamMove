package com.sh.config.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @Author caiwen
 * @Date 2024 03 12 14 32
 **/
@Slf4j
public class FileChunkIterator implements AutoCloseable {
    private final String filePath;
    private final int chunkSize;
    private InputStream inputStream;
    private final Lock lock;


    public FileChunkIterator(String filePath, int chunkSize) {
        this.filePath = filePath;
        this.chunkSize = chunkSize;
        this.lock = new ReentrantLock();
    }

    public Iterable<byte[]> iterateChunks() {
        return () -> new FileChunkIteratorImpl(filePath, chunkSize, this);
    }

    private static class FileChunkIteratorImpl implements java.util.Iterator<byte[]> {
        private final InputStream inputStream;
        private final int chunkSize;
        private boolean hasNextChunk;
        private final FileChunkIterator parent;

        public FileChunkIteratorImpl(String filePath, int chunkSize, FileChunkIterator parent) {
            try {
                this.inputStream = Files.newInputStream(Paths.get(filePath));
                this.chunkSize = chunkSize;
                this.hasNextChunk = true;
                this.parent = parent;
            } catch (IOException e) {
                throw new RuntimeException("Failed to open file", e);
            }
        }

        @Override
        public boolean hasNext() {
            return hasNextChunk;
        }

        @Override
        public byte[] next() {
            parent.lock.lock();
            try {
                byte[] chunk = new byte[chunkSize];
                int bytesRead = inputStream.read(chunk);
                if (bytesRead == -1) {
                    log.info("read chunk finish!");
                    inputStream.close();
                    parent.close();
                    hasNextChunk = false;
                    return null;
                }
                if (bytesRead < chunkSize) {
                    byte[] smallerChunk = new byte[bytesRead];
                    System.arraycopy(chunk, 0, smallerChunk, 0, bytesRead);
                    return smallerChunk;
                }
                return chunk;
            } catch (IOException e) {
                throw new RuntimeException("Failed to read from file", e);
            } finally {
                parent.lock.unlock();
            }
        }
    }

    @Override
    public void close() {
        if (inputStream != null) {
            try {
                inputStream.close();
                log.info("save close inputStream, filePath: {}", filePath);
            } catch (IOException e) {
            }
        }
    }
}
