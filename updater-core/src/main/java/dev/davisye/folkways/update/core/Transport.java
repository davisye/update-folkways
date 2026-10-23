package dev.davisye.folkways.update.core;

import java.io.*;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
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
    @FunctionalInterface interface RequestSender { HttpResponse<byte[]> send(HttpRequest request, long maximum, InetSocketAddress proxy) throws IOException, InterruptedException; }
    @FunctionalInterface interface ProxyLoader { Optional<InetSocketAddress> load() throws InterruptedException; }
    private final RequestSender sender;
    private final ProxyLoader proxyLoader;
    public Transport(Config config) { this(config, null); }
    Transport(Config config, RouteReader routeReader) { this(config, routeReader, null, WindowsSystemProxy::read); }
    Transport(Config config, RouteReader routeReader, RequestSender sender, ProxyLoader proxyLoader) {
        this.config = config; this.routeReader = routeReader;
        this.sender = sender == null ? this::send : sender; this.proxyLoader = proxyLoader;
    }

    private HttpResponse<byte[]> send(HttpRequest request, long maximum, InetSocketAddress proxy) throws IOException, InterruptedException {
        if (proxy == null) return client.send(request, ignored -> new LimitedBody(maximum));
        try (HttpClient fallback = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .proxy(ProxySelector.of(proxy)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            return fallback.send(request, ignored -> new LimitedBody(maximum));
        }
    }
    private HttpResponse<byte[]> sendWithFallback(HttpRequest request, long maximum) throws IOException, InterruptedException {
        try { return sender.send(request, maximum, null); }
        catch (IOException original) {
            if (!connectionFailure(original)) throw original;
            Optional<InetSocketAddress> endpoint = proxyLoader.load();
            if (endpoint.isEmpty()) throw original;
            try { return sender.send(request, maximum, endpoint.get()); }
            catch (IOException unavailable) {
                // Do not retain proxy exceptions: they can contain private endpoint details.
                original.addSuppressed(new IOException("Existing Windows HTTP proxy fallback was unavailable"));
                throw original;
            }
        }
    }
    private static boolean connectionFailure(IOException original) {
        boolean network = false; int depth = 0; Throwable reason = original;
        while (reason != null && depth++ < 32) {
            // Limits and TLS trust failures must not become another network attempt.
            if (reason instanceof BodyLimitException || reason instanceof javax.net.ssl.SSLException) return false;
            if (reason instanceof java.net.ConnectException || reason instanceof java.net.UnknownHostException
                    || reason instanceof java.net.NoRouteToHostException || reason instanceof HttpConnectTimeoutException
                    || reason instanceof java.nio.channels.UnresolvedAddressException) network = true;
            reason = reason.getCause();
        }
        return reason == null && network;
    }

    static URI safeUri(String url) throws IOException {
        URI u;
        try { u = URI.create(url); } catch (RuntimeException e) { throw new IOException("Invalid URL", e); }
        if (!"https".equals(u.getScheme()) || u.getUserInfo() != null || u.getFragment() != null || (u.getPort() != -1 && u.getPort() != 443)
            || (!GITHUB.contains(u.getHost()) && !Set.of("github.davisye.cn", "pan.davisye.cn", "api.modrinth.com", "cdn.modrinth.com").contains(u.getHost()))) throw new IOException("Unapproved public HTTPS URL");
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
        return officialJson(uri, maximum);
    }
    byte[] fetchFolkwaysApi(String url, long maximum) throws Exception {
        URI uri = safeUri(url);
        boolean github = "api.github.com".equals(uri.getHost()) && (uri.getPath().startsWith("/repos/IzakyL/folkways/") || uri.getPath().startsWith("/repos/davisye/folkways/"));
        boolean modrinth = ApprovedDependencies.PRODUCTION.allowsApi(uri.toString());
        if (!github && !modrinth) throw new IOException("Unsupported official Folkways/dependency API");
        return officialJson(uri, maximum);
    }
    private byte[] officialJson(URI uri, long maximum) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
            .header("User-Agent", "folkways-bootstrap/0.1").header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<byte[]> response = sendWithFallback(request, maximum);
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
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            catch (Exception e) { last = e; }
        }
        throw new IOException("All download routes failed for " + original.getHost(), last);
    }

    private byte[] read(URI uri, long maximum) throws Exception {
        for (int i = 0; i < 6; i++) {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("User-Agent", "folkways-bootstrap/0.1").GET().build();
            HttpResponse<byte[]> r = sendWithFallback(req, maximum);
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

    static final class BodyLimitException extends IOException {
        BodyLimitException() { super("Download exceeds limit"); }
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
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
                    if (bytes.size() + (long)b.remaining() > maximum) throw new BodyLimitException();
                    byte[] data = new byte[b.remaining()]; b.get(data); bytes.write(data);
                }
                subscription.request(1);
            } catch (Exception e) { subscription.cancel(); result.completeExceptionally(e); }
        }
        public void onError(Throwable t) { result.completeExceptionally(t); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
