package dev.davisye.folkways.update.core;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Public resources only: never forwards cookies or GitHub access tokens. */
public final class Transport implements Updater.Fetcher {
    private final Config config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> GITHUB = Set.of("github.com", "api.github.com", "raw.githubusercontent.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "codeload.github.com");
    @FunctionalInterface interface RouteReader { byte[] read(URI uri, long maximum) throws Exception; }
    private final RouteReader routeReader;
    public Transport(Config config) { this(config, null); }
    Transport(Config config, RouteReader routeReader) { this.config = config; this.routeReader = routeReader; }

    static URI safeUri(String url) throws IOException {
        URI u;
        try { u = URI.create(url); } catch (RuntimeException e) { throw new IOException("Invalid URL", e); }
        if (!"https".equals(u.getScheme()) || u.getUserInfo() != null || u.getFragment() != null || (u.getPort() != -1 && u.getPort() != 443)
            || (!GITHUB.contains(u.getHost()) && !Set.of("github.davisye.cn", "pan.davisye.cn").contains(u.getHost()))) throw new IOException("Unapproved public HTTPS URL");
        return u;
    }

    static String proxy(String url) throws IOException {
        URI u = safeUri(url);
        if (!GITHUB.contains(u.getHost())) return url;
        return "https://github.davisye.cn/_github/" + u.getHost() + u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    @FunctionalInterface interface Validator { void verify(byte[] bytes) throws Exception; }
    /** Trust bootstrap: official API HTTPS only, with no mirror and no redirects. */
    byte[] fetchOfficialApi(String url, long maximum) throws Exception {
        URI uri = safeUri(url);
        if (!"api.github.com".equals(uri.getHost()) || !uri.getPath().startsWith("/repos/davisye/update-folkways/")) throw new IOException("Self-update API source is fixed");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
            .header("User-Agent", "folkways-bootstrap/0.1").header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<byte[]> response = client.send(request, ignored -> new LimitedBody(maximum));
        if (response.statusCode() != 200) throw new IOException("Official self-update API unavailable (HTTP " + response.statusCode() + ")");
        if (!response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT).contains("application/json")) throw new IOException("Official API returned a non-JSON response");
        return response.body();
    }
    public byte[] fetch(String url, long maximum) throws Exception { return fetchValidated(url, maximum, ignored -> {}); }
    byte[] fetchValidated(String url, long maximum, Validator validator) throws Exception {
        URI original = safeUri(url);
        List<String> candidates = new ArrayList<>();
        String mirror = proxy(url);
        boolean first = config.networkPolicy.equals("mirror-first") || (config.networkPolicy.equals("region") && "CN".equalsIgnoreCase(config.regionHint));
        if (first && !mirror.equals(url)) candidates.add(mirror);
        candidates.add(url);
        if (!candidates.contains(mirror)) candidates.add(mirror);
        Exception last = null;
        for (String candidate : candidates) {
            try {
                URI uri = safeUri(candidate);
                byte[] bytes = routeReader == null ? read(uri, maximum) : routeReader.read(uri, maximum);
                validator.verify(bytes); return bytes;
            }
            catch (Exception e) { last = e; }
        }
        throw new IOException("All download routes failed for " + original.getHost(), last);
    }

    private byte[] read(URI uri, long maximum) throws Exception {
        for (int i = 0; i < 6; i++) {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("User-Agent", "folkways-bootstrap/0.1").GET().build();
            HttpResponse<byte[]> r = client.send(req, ignored -> new LimitedBody(maximum));
            if (r.statusCode() >= 300 && r.statusCode() < 400) {
                String location = r.headers().firstValue("location").orElseThrow(() -> new IOException("Redirect without location"));
                uri = safeUri(uri.resolve(location).toString());
                continue;
            }
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            String type = r.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (type.contains("text/html")) throw new IOException("Server returned HTML");
            return r.body();
        }
        throw new IOException("Too many redirects");
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final long maximum;
        Flow.Subscription subscription;
        LimitedBody(long maximum) { this.maximum = maximum; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> list) {
            try {
                for (ByteBuffer b : list) {
                    if (bytes.size() + (long)b.remaining() > maximum) throw new IOException("Download exceeds limit");
                    byte[] data = new byte[b.remaining()]; b.get(data); bytes.write(data);
                }
                subscription.request(1);
            } catch (Exception e) { subscription.cancel(); result.completeExceptionally(e); }
        }
        public void onError(Throwable t) { result.completeExceptionally(t); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
