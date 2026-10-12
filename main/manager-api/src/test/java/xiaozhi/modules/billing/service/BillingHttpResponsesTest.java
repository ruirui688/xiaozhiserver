package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;
import org.junit.jupiter.api.Test;

class BillingHttpResponsesTest {
    @Test void oversizedBodyCancelsTransferBeforeAccumulatingUnboundedData() {
        var body = new BillingHttpResponses.LimitedBody(4); var subscription = mock(Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3}), ByteBuffer.wrap(new byte[]{4, 5})));
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        verify(subscription).cancel();
    }
    @Test void wholeBodyIsDeliveredOnlyAfterCompletion() throws Exception {
        var body = new BillingHttpResponses.LimitedBody(4); body.onSubscribe(mock(Subscription.class));
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}))); assertFalse(body.getBody().toCompletableFuture().isDone());
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{3, 4}))); body.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, body.getBody().toCompletableFuture().get());
    }
    @Test void stalledResponseTimesOutAndCancelsUnderlyingRequest() {
        HttpClient client = mock(HttpClient.class); CompletableFuture<HttpResponse<byte[]>> stalled = new CompletableFuture<>();
        when(client.<byte[]>sendAsync(any(), any())).thenReturn(stalled);
        assertThrows(TimeoutException.class, () -> BillingHttpResponses.send(client,
                HttpRequest.newBuilder(URI.create("https://example.invalid")).build(), 10, Duration.ofMillis(20)));
        assertTrue(stalled.isCancelled());
    }
}
