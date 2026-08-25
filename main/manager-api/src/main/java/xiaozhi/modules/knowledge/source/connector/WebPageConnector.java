package xiaozhi.modules.knowledge.source.connector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.source.entity.KnowledgeSourceEntity;

@Component
public class WebPageConnector {
    private static final int MAX_REDIRECTS = 3;
    private final HttpClient httpClient;
    private final int maxResponseBytes;

    public WebPageConnector(
            @Value("${knowledge.sources.web.max-response-bytes:2097152}") int maxResponseBytes,
            @Value("${knowledge.sources.web.connect-timeout-seconds:10}") int connectTimeoutSeconds) {
        this.maxResponseBytes = Math.max(65536, Math.min(maxResponseBytes, 10485760));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(2, Math.min(connectTimeoutSeconds, 30))))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public FetchResult fetch(KnowledgeSourceEntity source) {
        URI uri = PublicUrlGuard.requirePublicHttpUrl(source.getSourceUri());
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "text/html,text/plain,application/json,application/xml;q=0.8")
                    .header("User-Agent", "xiaozhi-knowledge-sync/1.0")
                    .GET();
            if (StringUtils.isNotBlank(source.getEtag())) {
                request.header("If-None-Match", source.getEtag());
            }
            if (StringUtils.isNotBlank(source.getLastModified())) {
                request.header("If-Modified-Since", source.getLastModified());
            }
            RawResponse response = send(request.build());
            if (response.statusCode() == 304) {
                return FetchResult.notModified(uri);
            }
            if (isRedirect(response.statusCode())) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new RenException("动态知识数据源重定向缺少 Location"));
                uri = PublicUrlGuard.requirePublicHttpUrl(uri.resolve(location).toString());
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RenException("动态知识数据源返回 HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("text/plain");
            requireSupportedContentType(contentType);
            byte[] body = response.body();
            String text = extractText(body, contentType, uri);
            if (text.length() < 20) {
                throw new RenException("动态知识数据源没有可索引的正文内容");
            }
            return new FetchResult(
                    false,
                    uri,
                    documentName(uri),
                    text,
                    sha256(text),
                    response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null),
                    body.length);
        }
        throw new RenException("动态知识数据源重定向次数过多");
    }

    private RawResponse send(HttpRequest request) {
        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > maxResponseBytes) {
                throw new RenException("动态知识数据源响应超过安全上限 " + maxResponseBytes + " 字节");
            }
            try (java.io.InputStream input = response.body()) {
                byte[] body = input.readNBytes(maxResponseBytes + 1);
                if (body.length > maxResponseBytes) {
                    throw new RenException("动态知识数据源响应超过安全上限 " + maxResponseBytes + " 字节");
                }
                return new RawResponse(response.statusCode(), response.headers(), body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenException("动态知识数据源抓取被中断", e);
        } catch (IOException e) {
            throw new RenException("动态知识数据源抓取失败", e);
        }
    }

    private String extractText(byte[] body, String contentType, URI uri) {
        if (!contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            return StringUtils.normalizeSpace(new String(body, StandardCharsets.UTF_8));
        }
        try {
            Document document = Jsoup.parse(new ByteArrayInputStream(body), null, uri.toString());
            document.select("script,style,noscript,svg,canvas,nav,footer").remove();
            Element root = firstNonEmpty(document.selectFirst("article"), document.selectFirst("main"), document.body());
            String title = StringUtils.trimToEmpty(document.title());
            String content = root == null ? "" : root.text();
            return StringUtils.normalizeSpace(StringUtils.joinWith("\n", title, content));
        } catch (IOException e) {
            throw new RenException("动态知识网页解析失败", e);
        }
    }

    private Element firstNonEmpty(Element... elements) {
        return Arrays.stream(elements)
                .filter(element -> element != null && StringUtils.isNotBlank(element.text()))
                .findFirst()
                .orElse(null);
    }

    private void requireSupportedContentType(String contentType) {
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (!normalized.contains("text/html")
                && !normalized.contains("text/plain")
                && !normalized.contains("application/json")
                && !normalized.contains("application/xml")
                && !normalized.contains("text/xml")) {
            throw new RenException("动态知识数据源不支持该 Content-Type");
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private String documentName(URI uri) {
        String host = StringUtils.defaultIfBlank(uri.getHost(), "web-source");
        return StringUtils.abbreviate(host.replaceAll("[^a-zA-Z0-9._-]", "_") + ".md", 180);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record FetchResult(
            boolean notModified,
            URI finalUri,
            String documentName,
            String content,
            String contentHash,
            String etag,
            String lastModified,
            long bytesFetched) {
        static FetchResult notModified(URI uri) {
            return new FetchResult(true, uri, null, null, null, null, null, 0L);
        }
    }

    private record RawResponse(int statusCode, HttpHeaders headers, byte[] body) {
    }
}
