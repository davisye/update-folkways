package dev.davisye.folkways.update.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransportProxyTest {
    static final String CDN = "https://cdn.modrinth.com/data/B1CBVXHX/versions/bpcbGDy4/ldlib2-neoforge-1.21.1-2.2.37-all.jar";
    static final String API = "https://api.github.com/repos/davisye/update-folkways/releases/tags/nightly";
    static final InetSocketAddress ENDPOINT = InetSocketAddress.createUnresolved("proxy.example.invalid", 12345);
    static HttpResponse<byte[]> response(HttpRequest request, int status, String type, byte[] body, String location) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return request; }
            public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(location == null ? Map.of("content-type", List.of(type))
                : Map.of("content-type", List.of(type), "location", List.of(location)), (a,b) -> true); }
            public byte[] body() { return body; }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return request.uri(); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
    static Transport transport(Transport.RequestSender sender, AtomicInteger reads) {
        return new Transport(new Config(), null, sender, () -> { reads.incrementAndGet(); return Optional.of(ENDPOINT); });
    }
    @Test void successfulDefaultRouteDoesNotReadSystemSettings() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        Transport t = transport((request, maximum, proxy) -> {
            assertNull(proxy); return response(request, 200, "application/octet-stream", new byte[]{1}, null);
        }, reads);
        assertArrayEquals(new byte[]{1}, t.fetch(CDN, 1)); assertEquals(0, reads.get());
    }
    @Test void connectionIoReadsFallbackOnceAndReusesExactRequestAndLimit() throws Exception {
        AtomicInteger reads = new AtomicInteger(); List<HttpRequest> requests = new ArrayList<>();
        Transport t = transport((request, maximum, proxy) -> {
            requests.add(request); assertEquals(1, maximum);
            if (proxy == null) throw new java.net.http.HttpConnectTimeoutException("Fixture connect timed out");
            assertEquals(ENDPOINT, proxy); return response(request, 200, "application/octet-stream", new byte[]{1}, null);
        }, reads);
        assertArrayEquals(new byte[]{1}, t.fetch(CDN, 1)); assertEquals(1, reads.get()); assertEquals(2, requests.size());
        assertSame(requests.get(0), requests.get(1));
    }
    @Test void missingSystemProxyPreservesOriginalFailureWithoutAnotherSend() {
        AtomicInteger sends = new AtomicInteger(); IOException first = new java.net.ConnectException("Original network failure");
        Transport t = new Transport(new Config(), null, (request, maximum, proxy) -> { sends.incrementAndGet(); throw first; }, Optional::empty);
        IOException error = assertThrows(IOException.class, () -> t.fetch(CDN, 1));
        assertSame(first, error.getCause()); assertEquals(1, sends.get());
    }
    @Test void failingFallbackDoesNotRetryOrExposeItsEndpointException() {
        AtomicInteger reads = new AtomicInteger(), sends = new AtomicInteger(); IOException first = new java.net.ConnectException("Original network failure");
        Transport t = transport((request, maximum, proxy) -> {
            sends.incrementAndGet(); if (proxy == null) throw first;
            throw new IOException("secret-proxy-value-that-must-not-be-retained");
        }, reads);
        IOException error = assertThrows(IOException.class, () -> t.fetch(CDN, 1));
        assertEquals(2, sends.get()); assertEquals(1, reads.get()); assertSame(first, error.getCause());
        StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains("secret-proxy-value"));
    }
    @Test void statusHtmlAndDigestRejectionsNeverReadSystemProxy() {
        for (int mode = 0; mode < 3; mode++) {
            final int selected = mode; AtomicInteger reads = new AtomicInteger(), sends = new AtomicInteger();
            Transport t = transport((request, maximum, proxy) -> {
                sends.incrementAndGet(); return response(request, selected == 0 ? 403 : 200,
                    selected == 1 ? "text/html" : "application/octet-stream", new byte[]{0}, null);
            }, reads);
            assertThrows(IOException.class, () -> t.fetchValidated(CDN, 1, bytes -> { throw new IOException("Digest rejected"); }));
            assertEquals(1, sends.get()); assertEquals(0, reads.get());
        }
    }
    @Test void realSubscriberSizeRejectionDoesNotTriggerFallback() throws Exception {
        Transport.LimitedBody subscriber = new Transport.LimitedBody(1); AtomicInteger cancelled = new AtomicInteger();
        subscriber.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled.incrementAndGet(); } });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2})));
        Throwable limit = assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get()).getCause();
        assertInstanceOf(Transport.BodyLimitException.class, limit); assertEquals(1, cancelled.get());
        AtomicInteger reads = new AtomicInteger();
        Transport t = transport((request, maximum, proxy) -> { throw new IOException("Wrapped subscriber failure", limit); }, reads);
        assertThrows(IOException.class, () -> t.fetch(CDN, 1)); assertEquals(0, reads.get());
    }
    @Test void fallbackStillCannotRedirectTheOfficialApiOrAcceptNonJson() {
        for (int mode = 0; mode < 2; mode++) {
            final int selected = mode; AtomicInteger reads = new AtomicInteger(), sends = new AtomicInteger();
            Transport t = transport((request, maximum, proxy) -> {
                sends.incrementAndGet(); if (proxy == null) throw new java.net.ConnectException("Connection unavailable");
                return response(request, selected == 0 ? 302 : 200, "text/html", new byte[]{1}, "https://example.invalid/");
            }, reads);
            assertThrows(IOException.class, () -> t.fetchOfficialApi(API, 1));
            assertEquals(2, reads.get()); assertEquals(4, sends.get());
        }
    }
    @Test void fallbackBytesStillPassThroughTheOriginalDigestValidator() {
        AtomicInteger reads = new AtomicInteger(), checks = new AtomicInteger();
        Transport t = transport((request, maximum, proxy) -> {
            if (proxy == null) throw new java.net.ConnectException("Connection unavailable");
            return response(request, 200, "application/octet-stream", new byte[]{0}, null);
        }, reads);
        assertThrows(IOException.class, () -> t.fetchValidated(CDN, 1, bytes -> { checks.incrementAndGet(); throw new IOException("Digest rejected"); }));
        assertEquals(1, reads.get()); assertEquals(1, checks.get());
    }
    @Test void interruptionAndUnapprovedHostsDoNotReadSystemSettings() {
        AtomicInteger reads = new AtomicInteger(), sends = new AtomicInteger();
        Transport t = transport((request, maximum, proxy) -> { sends.incrementAndGet(); throw new InterruptedException(); }, reads);
        try {
            assertThrows(InterruptedException.class, () -> t.fetchOfficialApi(API, 1));
            assertTrue(Thread.currentThread().isInterrupted());
            assertThrows(IOException.class, () -> t.fetch("https://example.invalid/unapproved", 1));
            assertEquals(1, sends.get()); assertEquals(0, reads.get());
        } finally { Thread.interrupted(); }
    }
    @Test void tlsTrustFailuresAndUnclassifiedIoDoNotTryAnotherProxy() {
        for (IOException failure : List.of(new javax.net.ssl.SSLHandshakeException("Untrusted certificate"),
                new IOException("Local or unclassified failure"),
                new java.net.http.HttpTimeoutException("Body or whole-request timeout"),
                new java.net.SocketException("Body socket reset"), new java.io.EOFException("Truncated response"),
                new IOException("Wrapper", new javax.net.ssl.SSLException("TLS trust validation failure")))) {
            AtomicInteger reads = new AtomicInteger();
            Transport t = transport((request, maximum, proxy) -> { throw failure; }, reads);
            assertThrows(IOException.class, () -> t.fetch(CDN, 1)); assertEquals(0, reads.get());
        }
    }
    @Test void interruptedAssetRequestPreservesCancellationWithoutTryingTheOtherRoute() {
        AtomicInteger reads = new AtomicInteger(), sends = new AtomicInteger();
        Transport t = transport((request, maximum, proxy) -> { sends.incrementAndGet(); throw new InterruptedException(); }, reads);
        try {
            assertThrows(InterruptedException.class, () -> t.fetchValidated(
                "https://github.com/IzakyL/folkways/releases/download/nightly/test.jar", 1, bytes -> fail("No download may complete")));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(1, sends.get()); assertEquals(0, reads.get());
        } finally { Thread.interrupted(); } // Do not leave JUnit's worker interrupted.
    }
}
