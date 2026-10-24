package dev.davisye.folkways.update.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiRouteTest {
    private static final String SELF = "https://api.github.com/repos/davisye/update-folkways/releases/tags/nightly";
    private static final String FOLK = "https://api.github.com/repos/davisye/folkways/actions/workflows/build.yml/runs?head_sha=abc&event=push";
    private static final byte[] JSON = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);

    private Transport transport(Config config, Transport.RequestSender sender) {
        return new Transport(config, null, sender, Optional::empty);
    }
    @Test void mirrorFirstAppliesToSelfAndFolkwaysVerificationAndRetainsQuery() throws Exception {
        for (String canonical : List.of(SELF, FOLK)) {
            var calls = new ArrayList<URI>();
            Transport t = transport(new Config(), (request, maximum, proxy) -> {
                calls.add(request.uri()); assertEquals(64, maximum);
                assertEquals(Optional.empty(), request.headers().firstValue("Authorization"));
                assertEquals(Optional.empty(), request.headers().firstValue("Cookie"));
                assertEquals(Transport.proxy(canonical), request.uri().toString());
                return TransportProxyTest.response(request, 200, "application/json", JSON, null);
            });
            assertArrayEquals(JSON, canonical.equals(SELF) ? t.fetchOfficialApi(canonical, 64) : t.fetchFolkwaysApi(canonical, 64));
            assertEquals(1, calls.size());
        }
    }
    @Test void directFirstUsesOfficialApiWithoutCallingMirrorOnSuccess() throws Exception {
        Config c = new Config(); c.networkPolicy = "direct-first";
        Transport t = transport(c, (request, maximum, proxy) -> {
            assertEquals(SELF, request.uri().toString());
            return TransportProxyTest.response(request, 200, "application/json", JSON, null);
        });
        assertArrayEquals(JSON, t.fetchOfficialApi(SELF, 64));
    }
    @Test void directConnectionFailureFallsBackToApprovedMirror() throws Exception {
        Config c = new Config(); c.networkPolicy = "direct-first"; var calls = new ArrayList<String>();
        Transport t = transport(c, (request, maximum, proxy) -> {
            calls.add(request.uri().getHost());
            if (request.uri().getHost().equals("api.github.com")) throw new java.net.ConnectException("fixture");
            return TransportProxyTest.response(request, 200, "application/json", JSON, null);
        });
        assertArrayEquals(JSON, t.fetchOfficialApi(SELF, 64));
        assertEquals(List.of("api.github.com", "github.davisye.cn"), calls);
    }
    @Test void regionHintControlsVerificationRouteOrder() throws Exception {
        for (String hint : List.of("CN", "cn", "")) {
            Config c = new Config(); c.networkPolicy = "region"; c.regionHint = hint;
            String expected = hint.isEmpty() ? "api.github.com" : "github.davisye.cn";
            Transport t = transport(c, (request, maximum, proxy) -> {
                assertEquals(expected, request.uri().getHost());
                return TransportProxyTest.response(request, 200, "application/json", JSON, null);
            });
            assertArrayEquals(JSON, t.fetchOfficialApi(SELF, 64));
        }
    }
    @Test void badMirrorStatusHtmlMalformedJsonRedirectAndSizeAllFallBackWithSameLimit() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            final int bad = mode; var calls = new ArrayList<String>();
            Transport t = transport(new Config(), (request, maximum, proxy) -> {
                calls.add(request.uri().getHost()); assertEquals(16, maximum);
                if (request.uri().getHost().equals("api.github.com"))
                    return TransportProxyTest.response(request, 200, "application/json", JSON, null);
                return switch (bad) {
                    case 0 -> TransportProxyTest.response(request, 429, "application/json", JSON, null);
                    case 1 -> TransportProxyTest.response(request, 200, "text/html", "<html>".getBytes(StandardCharsets.UTF_8), null);
                    case 2 -> TransportProxyTest.response(request, 200, "application/json", "{broken".getBytes(StandardCharsets.UTF_8), null);
                    case 3 -> TransportProxyTest.response(request, 302, "application/json", JSON, "https://evil.invalid/");
                    default -> TransportProxyTest.response(request, 200, "application/json", new byte[17], null);
                };
            });
            assertArrayEquals(JSON, t.fetchOfficialApi(SELF, 16));
            assertEquals(List.of("github.davisye.cn", "api.github.com"), calls);
        }
    }
    @Test void unapprovedCanonicalApiOrMirrorInputIsRejectedBeforeAnySend() {
        AtomicInteger calls = new AtomicInteger();
        Transport t = transport(new Config(), (request, maximum, proxy) -> { calls.incrementAndGet(); throw new IOException("No request authorized"); });
        for (String input : List.of("https://api.github.com/repos/evil/update-folkways/releases", "https://github.davisye.cn/_github/api.github.com/repos/davisye/update-folkways/releases", "https://user:secret@api.github.com/repos/davisye/update-folkways/releases"))
            assertThrows(IOException.class, () -> t.fetchOfficialApi(input, 64));
        assertThrows(IOException.class, () -> t.fetchFolkwaysApi("https://api.github.com/repos/evil/folkways/releases", 64));
        assertEquals(0, calls.get());
    }
    @Test void modrinthMetadataRemainsOnItsApprovedOfficialApi() throws Exception {
        String url = "https://api.modrinth.com/v2/version/bpcbGDy4";
        Transport t = transport(new Config(), (request, maximum, proxy) -> {
            assertEquals(url, request.uri().toString());
            return TransportProxyTest.response(request, 200, "application/json", JSON, null);
        });
        assertArrayEquals(JSON, t.fetchFolkwaysApi(url, 64));
    }
    @Test void cancelledApiCheckStopsWithoutTryingAnotherRoute() {
        AtomicInteger calls = new AtomicInteger();
        Transport t = transport(new Config(), (request, maximum, proxy) -> { calls.incrementAndGet(); throw new InterruptedException(); });
        try {
            assertThrows(InterruptedException.class, () -> t.fetchOfficialApi(SELF, 64));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(1, calls.get());
        } finally { Thread.interrupted(); }
    }
}
