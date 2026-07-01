package xiaozhi.modules.model.service.impl;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import java.net.http.WebSocket;

class BinaryResponseListener implements WebSocket.Listener {
    private final CompletableFuture<byte[]> responseFuture;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    BinaryResponseListener(CompletableFuture<byte[]> responseFuture) {
        this.responseFuture = responseFuture;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        synchronized (buffer) {
            buffer.write(bytes, 0, bytes.length);
            if (last && !responseFuture.isDone()) {
                responseFuture.complete(buffer.toByteArray());
            }
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        responseFuture.completeExceptionally(error);
    }
}
