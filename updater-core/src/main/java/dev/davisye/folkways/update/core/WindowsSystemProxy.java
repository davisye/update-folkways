package dev.davisye.folkways.update.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only, on-demand fallback to an existing Windows static HTTP proxy. */
final class WindowsSystemProxy {
    private static final String KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    private static final int OUTPUT_LIMIT = 8 * 1024; // Two queries, at most 16 KiB combined.
    // Shared deadline includes both queries and cold Windows process startup.
    private static final long QUERY_NANOS = Duration.ofSeconds(5).toNanos();

    private WindowsSystemProxy() {}

    static Optional<InetSocketAddress> read() throws InterruptedException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return Optional.empty();
        try {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null) return Optional.empty();
            Path root = Path.of(systemRoot);
            if (!root.isAbsolute() || root.getRoot() == null || root.toString().startsWith("\\\\")) return Optional.empty();
            // No PATH search, shell, PAC execution, or machine-wide setting changes.
            Path executable = root.resolve("System32/reg.exe");
            if (!Files.isRegularFile(executable) || Files.isSymbolicLink(executable)) return Optional.empty();
            long deadline = System.nanoTime() + QUERY_NANOS;
            String enabled = query(executable, "ProxyEnable", deadline);
            if (!"1".equals(enabledValue(enabled))) return Optional.empty();
            return parse(enabled, query(executable, "ProxyServer", deadline));
        } catch (IOException | RuntimeException ignored) {
            // Registry contents and endpoint values are never logged or retained in failures.
            return Optional.empty();
        }
    }

    private static String query(Path executable, String value, long deadline) throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Windows proxy query timed out");
        Process process = new ProcessBuilder(executable.toString(), "query", KEY, "/v", value).redirectErrorStream(true).start();
        FutureTask<byte[]> output = new FutureTask<>(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] bytes = stream.readNBytes(OUTPUT_LIMIT + 1);
                if (bytes.length > OUTPUT_LIMIT) throw new IOException("Windows proxy output exceeds limit");
                return bytes;
            }
        });
        Thread reader = Thread.ofVirtual().name("folkways-windows-proxy-query").start(output);
        try {
            remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw new IOException("Windows proxy query timed out");
            if (process.exitValue() != 0) throw new IOException("Windows proxy query unavailable");
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Windows proxy query timed out");
            return new String(output.get(remaining, TimeUnit.NANOSECONDS), StandardCharsets.UTF_8);
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("Windows proxy query unavailable");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            output.cancel(true);
            reader.interrupt();
        }
    }

    private static String registryValue(String output, String name, String type) {
        if (output == null || output.length() > OUTPUT_LIMIT) return null;
        Matcher match = Pattern.compile("(?im)^\\h*" + name + "\\h+" + type + "\\h+([^\\r\\n]*)$").matcher(output);
        if (!match.find()) return null;
        String value = match.group(1).trim();
        return match.find() ? null : value;
    }

    private static String enabledValue(String output) {
        String value = registryValue(output, "ProxyEnable", "REG_DWORD");
        return "0x1".equalsIgnoreCase(value) || "1".equals(value) ? "1" : null;
    }

    static Optional<InetSocketAddress> parse(String enabled, String server) {
        if (!"1".equals(enabledValue(enabled))) return Optional.empty();
        String value = registryValue(server, "ProxyServer", "REG_SZ");
        if (value == null || value.isBlank() || value.contains("@") || value.contains("/") || value.contains("\\")) return Optional.empty();
        String endpoint = value;
        if (value.contains("=") || value.contains(";")) {
            Map<String,String> protocols = new HashMap<>();
            for (String part : value.split(";", -1)) {
                String[] pair = part.trim().split("=", -1);
                if (pair.length != 2) return Optional.empty();
                String protocol = pair[0].trim().toLowerCase(Locale.ROOT);
                if (!java.util.Set.of("http", "https", "ftp").contains(protocol)
                        || protocols.putIfAbsent(protocol, pair[1].trim()) != null) return Optional.empty();
            }
            endpoint = protocols.get("https"); // Never reinterpret an HTTP-only or SOCKS map as HTTPS.
        }
        if (endpoint == null || !endpoint.matches("(?:\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9][A-Za-z0-9._-]*):[0-9]{1,5}")) return Optional.empty();
        try {
            URI uri = URI.create("http://" + endpoint);
            String host = uri.getHost();
            if (host == null || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null) return Optional.empty();
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length()-1);
            return Optional.of(InetSocketAddress.createUnresolved(host, uri.getPort()));
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
}
