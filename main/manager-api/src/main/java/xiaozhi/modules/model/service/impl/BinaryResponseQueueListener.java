package xiaozhi.modules.model.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import java.net.http.WebSocket;

class BinaryResponseQueueListener implements WebSocket.Listener {
    private final BlockingQueue<byte[]> responses = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile Throwable error;

    byte[] poll(long timeout, TimeUnit unit) throws Exception {
        if (error != null) {
            throw new IOException(error);
        }
        byte[] response = responses.poll(timeout, unit);
        if (response == null) {
            if (error != null) {
                throw new IOException(error);
            }
            throw new IOException("等待 ASR 响应超时");
        }
        return response;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        synchronized (buffer) {
            buffer.write(bytes, 0, bytes.length);
            if (last) {
                responses.offer(buffer.toByteArray());
                buffer.reset();
            }
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        this.error = error;
    }
}
