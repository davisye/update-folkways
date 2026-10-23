package dev.davisye.folkways.update.core;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowsSystemProxyTest {
    static final String ENABLED = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\r\n    ProxyEnable    REG_DWORD    0x1\r\n";
    static String server(String endpoint) { return "    ProxyServer    REG_SZ    " + endpoint + "\r\n"; }

    @Test void enabledStaticEndpointIsUnresolvedAndDoesNotPerformDns() {
        InetSocketAddress endpoint = WindowsSystemProxy.parse(ENABLED, server("proxy.example.invalid:12345")).orElseThrow();
        assertEquals("proxy.example.invalid", endpoint.getHostString()); assertEquals(12345, endpoint.getPort()); assertTrue(endpoint.isUnresolved());
    }
    @Test void httpsMapSelectsOnlyItsHttpsEndpoint() {
        InetSocketAddress endpoint = WindowsSystemProxy.parse(ENABLED,
            server("http=127.0.0.1:11000;https=127.0.0.1:12000;ftp=127.0.0.1:13000")).orElseThrow();
        assertEquals("127.0.0.1", endpoint.getHostString()); assertEquals(12000, endpoint.getPort());
        assertTrue(WindowsSystemProxy.parse(ENABLED, server("http=127.0.0.1:11000")).isEmpty());
    }
    @Test void bracketedIpv6StaticProxyIsSupported() {
        InetSocketAddress endpoint = WindowsSystemProxy.parse(ENABLED, server("[::1]:12000")).orElseThrow();
        assertEquals("::1", endpoint.getHostString()); assertEquals(12000, endpoint.getPort());
    }
    @Test void disabledMissingOrAmbiguousRegistryValuesAreIgnored() {
        for (String enabled : new String[]{null, "", ENABLED.replace("0x1", "0x0"), ENABLED.replace("0x1", "0x2"), ENABLED+ENABLED})
            assertTrue(WindowsSystemProxy.parse(enabled, server("localhost:12345")).isEmpty());
        assertTrue(WindowsSystemProxy.parse(ENABLED, null).isEmpty());
        assertTrue(WindowsSystemProxy.parse(ENABLED, server("localhost:12345")+server("other:12346")).isEmpty());
        assertTrue(WindowsSystemProxy.parse(ENABLED, server("localhost:12345").replace("REG_SZ", "REG_EXPAND_SZ")).isEmpty());
    }
    @Test void credentialsPacSocksAndMalformedEndpointsAreRejected() {
        for (String value : new String[]{"user:password@localhost:12000", "https=user@localhost:12000", "http://localhost:12000",
                "https://example.invalid/proxy.pac", "socks=localhost:12000", "https=localhost:12000;socks=localhost:13000",
                "localhost:0", "localhost:65536", "localhost:-1", "localhost", "localhost:abc", "localhost:12000/path",
                "localhost:12000?x", "localhost:12000#x", "localhost:12000\\path", "localhost:12000;", "localhost:12 000",
                "https=localhost:12000;https=other:13000", "https=localhost:12000;unknown=other:13000"})
            assertTrue(WindowsSystemProxy.parse(ENABLED, server(value)).isEmpty(), "Unsupported static fixture accepted");
    }
    @Test void oversizedRegistryDataIsRejectedBeforeParsing() {
        assertTrue(WindowsSystemProxy.parse(ENABLED, server("localhost:12000")+" ".repeat(8193)).isEmpty());
        assertTrue(WindowsSystemProxy.parse(ENABLED+" ".repeat(8193), server("localhost:12000")).isEmpty());
    }
}
