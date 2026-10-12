package xiaozhi.modules.billing.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

/** Bounds both bytes and full response duration, including a supplier that stalls mid-body. */
final class BillingHttpResponses {
    private BillingHttpResponses() { }

    static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int maxBytes) throws Exception {
        return send(client, request, maxBytes, request.timeout().orElse(Duration.ofSeconds(45)));
    }
    static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int maxBytes, Duration timeout) throws Exception {
        var subscriber = new LimitedBody(maxBytes);
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> subscriber);
        try { return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (Exception error) { subscriber.cancel(); pending.cancel(true); throw error; }
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private Subscription subscription;
        private boolean cancelled;
        LimitedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return completed; }
        @Override public synchronized void onSubscribe(Subscription value) {
            if (subscription != null || cancelled) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> chunks) {
            if (cancelled) return;
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > maximum - bytes.size()) {
                    completed.completeExceptionally(new IOException("Supplier response exceeds size limit")); cancel(); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) { completed.completeExceptionally(error); }
        @Override public synchronized void onComplete() { if (!cancelled) completed.complete(bytes.toByteArray()); }
        synchronized void cancel() {
            cancelled = true;
            if (subscription != null) subscription.cancel();
            completed.cancel(false);
        }
    }
}
