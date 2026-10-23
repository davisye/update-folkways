package dev.davisye.folkways.update.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One-use restart capability, completed only after Minecraft's normal save/stop event. */
public final class AutomaticRestart {
    private static final String COUNT = "FOLKWAYS_AUTOMATIC_RESTART_COUNT";
    private final Updater u;
    private final Request request;

    private AutomaticRestart(Updater u, Request request) { this.u = u; this.request = request; }

    /** Dedicated servers only. Never capture client launcher credentials. */
    public static AutomaticRestart prepare(Path game) throws Exception {
        Updater u = new Updater(game);
        Config c = Config.load(u.game);
        int count = inheritedCount();
        if (!c.commandsEnabled || count >= c.restartLimit) throw new IOException("Automatic restart is disabled or its limit was reached");
        Request r = new Request();
        r.pid = ProcessHandle.current().pid();
        r.start = ProcessHandle.current().info().startInstant().orElseThrow().toString();
        r.game = u.game.toString(); r.cwd = Path.of("").toAbsolutePath().toRealPath().toString();
        r.command = captureCommand(); verifyReplayable(r.command); r.count = count;
        byte[] token = new byte[32]; new SecureRandom().nextBytes(token);
        r.token = HexFormat.of().formatHex(token);
        return u.locked(() -> {
            Path root = root(u); u.directory(root); ownerOnly(root, true);
            prune(u, root);
            Path file = requestPath(u, r.pid, r.start); u.safe(file);
            // A second preparation in the same game must not invalidate an outstanding capability.
            if (Files.exists(file)) throw new IOException("Restart capability already exists");
            Updater.atomic(file, Updater.JSON.toJson(r).getBytes(StandardCharsets.UTF_8));
            ownerOnly(file, false);
            return new AutomaticRestart(u, r);
        });
    }

    public boolean available() {
        try {
            Config c = Config.load(u.game);
            if (!c.commandsEnabled || request.count >= c.restartLimit || !sameLiveProcess(request.pid, request.start)) return false;
            return matches(readRequest(u, requestPath(u, request.pid, request.start)), request);
        } catch (Exception e) { return false; }
    }

    /** Called only by ServerStoppedEvent, never at countdown or ServerStoppingEvent. */
    public void complete() throws Exception {
        u.locked(() -> {
            if (!available()) throw new IOException("Restart capability no longer available");
            Path target = completionPath(u, request.pid, request.start); u.safe(target);
            Completion done = new Completion(); done.pid = request.pid; done.start = request.start;
            done.token = request.token; done.action = "restart";
            Updater.atomic(target, Updater.JSON.toJson(done).getBytes(StandardCharsets.UTF_8));
            ownerOnly(target, false);
            return null;
        });
    }

    /** The extracted helper calls this only after the exact parent exits and offline install succeeds. */
    public static void afterExit(Path game, long parentPid, String parentStart) throws Exception {
        Updater u = new Updater(game);
        if (sameLiveProcess(parentPid, parentStart)) throw new IOException("Original JVM is still alive");
        Path file = requestPath(u, parentPid, parentStart);
        Path completed = completionPath(u, parentPid, parentStart);
        u.safe(file); u.safe(completed);
        if (!Files.exists(file) || !Files.exists(completed)) return; // Crash / shutdown / cancelled request.
        Config c = Config.load(u.game);
        TimeUnit.SECONDS.sleep(c.restartCooldownSeconds);
        u.locked(() -> {
            RuntimeLease.ensureNoGame(u);
            for (String journal : List.of("journal.json", "dropin-journal.json", "self-journal.json")) {
                Path p = u.home.resolve(journal); u.safe(p);
                if (Files.exists(p)) throw new IOException("Unfinished update transaction; restart refused");
            }
            Request r = readRequest(u, file);
            Completion d = Updater.JSON.fromJson(new String(u.readBounded(completed, 4096), StandardCharsets.UTF_8), Completion.class);
            validate(r, u, parentPid, parentStart);
            ccheck(r, Config.load(u.game));
            if (d == null || d.pid != r.pid || !Objects.equals(d.start, r.start)
                    || !Objects.equals(d.token, r.token) || !"restart".equals(d.action)) throw new IOException("Invalid restart completion");
            Path consumed = completed.resolveSibling(completed.getFileName() + ".consumed"); u.safe(consumed);
            if (Files.exists(consumed)) throw new IOException("Restart capability already consumed");
            Files.move(completed, consumed, StandardCopyOption.ATOMIC_MOVE);
            Path log = u.home.resolve("automatic-restart.log"); u.safe(log);
            if (Files.exists(log) && Files.size(log) > 1024 * 1024) {
                Path old = log.resolveSibling("automatic-restart.previous.log"); u.safe(old);
                Files.move(log, old, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            ProcessBuilder pb = new ProcessBuilder(r.command).directory(Path.of(r.cwd).toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            pb.environment().put(COUNT, Integer.toString(r.count + 1));
            // Keep the original argument vector; no shell is involved. The child owns its normal Minecraft logs.
            Process next = startJava(pb); next.getOutputStream().close();
            Files.delete(file); Files.delete(consumed);
            return null;
        });
    }

    private static void ccheck(Request r, Config c) throws IOException {
        if (!c.commandsEnabled || r.count < 0 || r.count >= c.restartLimit) throw new IOException("Restart disabled or restart limit reached");
    }
    private static boolean matches(Request a, Request b) {
        return a != null && a.pid == b.pid && Objects.equals(a.start, b.start) && Objects.equals(a.token, b.token)
                && Objects.equals(a.game, b.game) && Objects.equals(a.cwd, b.cwd) && a.count == b.count && Objects.equals(a.command, b.command);
    }
    private static void validate(Request r, Updater u, long pid, String start) throws Exception {
        if (r == null || r.schema != 1 || r.pid != pid || !Objects.equals(r.start, start) || !u.game.toString().equals(r.game)
                || r.token == null || !r.token.matches("[0-9a-f]{64}") || r.count < 0 || r.count > 50
                || r.command == null || r.command.size() < 2 || r.command.size() > 16384 || r.cwd == null) throw new IOException("Invalid restart capability");
        for (String arg : r.command) if (arg == null || arg.indexOf('\0') >= 0) throw new IOException("Invalid restart argument");
        Path executable = Path.of(r.command.getFirst());
        if (!executable.isAbsolute() || !Files.isRegularFile(executable) || !Path.of(r.cwd).isAbsolute()
                || !Files.isDirectory(Path.of(r.cwd))) throw new IOException("Restart executable or working directory is unavailable");
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Set.of("java", "java.exe", "javaw.exe").contains(name)) throw new IOException("Restart requires a Java process");
    }
    private static Request readRequest(Updater u, Path file) throws IOException {
        return Updater.JSON.fromJson(new String(u.readBounded(file, 256 * 1024), StandardCharsets.UTF_8), Request.class);
    }
    private static int inheritedCount() throws IOException {
        String value = System.getenv(COUNT);
        if (value == null) return 0;
        try { int count = Integer.parseInt(value); if (count < 0 || count > 50) throw new NumberFormatException(); return count; }
        catch (NumberFormatException e) { throw new IOException("Invalid restart count"); }
    }
    private static Path root(Updater u) { return u.home.resolve("automatic-restart"); }
    private static String key(long pid, String start) { return pid + "-" + Instant.parse(start).toEpochMilli(); }
    private static Path requestPath(Updater u, long pid, String start) { return root(u).resolve(key(pid, start) + ".json"); }
    private static Path completionPath(Updater u, long pid, String start) { return root(u).resolve(key(pid, start) + ".complete.json"); }
    private static boolean sameLiveProcess(long pid, String start) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .map(p -> p.info().startInstant().map(t -> t.toString().equals(start)).orElse(true)).orElse(false);
    }
    private static void prune(Updater u, Path root) throws Exception {
        try (var paths = Files.list(root)) {
            for (Path p : paths.toList()) {
                u.safe(p);
                String name = p.getFileName().toString();
                if (!name.matches("[0-9]+-[0-9]+\\.(json|complete\\.json|complete\\.json\\.consumed)")) continue;
                String[] parts = name.split("[.-]", 3);
                long pid = Long.parseLong(parts[0]), millis = Long.parseLong(parts[1]);
                Optional<ProcessHandle> ph = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
                if (ph.isPresent() && ph.get().info().startInstant().map(t -> t.toEpochMilli() == millis).orElse(true)) continue;
                Files.delete(p);
            }
        }
    }
    private static void ownerOnly(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }

    static List<String> captureCommand() throws Exception {
        var info = ProcessHandle.current().info();
        Path executable = Path.of(info.command().orElseThrow(() -> new IOException("Java executable is unavailable"))).toRealPath();
        List<String> command = new ArrayList<>(); command.add(executable.toString());
        if (System.getProperty("os.name").startsWith("Windows")) {
            // ProcessHandle exposes neither arguments nor commandLine on Windows JDK 21. Read only this PID through CIM.
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null) throw new IOException("Windows system directory unavailable");
            Path ps = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            String script = "$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
                    + "[Console]::Write((Get-CimInstance -ClassName Win32_Process -Filter 'ProcessId=" + ProcessHandle.current().pid() + "').CommandLine)";
            Process query = new ProcessBuilder(ps.toString(), "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            java.util.concurrent.FutureTask<byte[]> output = new java.util.concurrent.FutureTask<>(() -> query.getInputStream().readNBytes(128 * 1024 + 1));
            Thread.ofVirtual().start(output);
            try {
                if (!query.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Command-line query timed out");
                byte[] bytes = output.get(1, TimeUnit.SECONDS);
                if (query.exitValue() != 0 || bytes.length > 128 * 1024) throw new IOException("Command-line query unavailable");
                List<String> parsed = parseWindows(new String(bytes, StandardCharsets.UTF_8));
                if (parsed.size() < 2) throw new IOException("Java launch arguments unavailable");
                String original = parsed.getFirst();
                boolean bareJava = Set.of("java", "java.exe", "javaw", "javaw.exe").contains(original.toLowerCase(Locale.ROOT));
                // A PATH-based argv[0] is normal for NeoForge run.bat. ProcessHandle identifies the actual executable.
                if (!bareJava && !Files.isSameFile(Path.of(original), executable)) throw new IOException("Java executable identity mismatch");
                command.addAll(parsed.subList(1, parsed.size()));
            } finally { if (query.isAlive()) query.destroyForcibly(); }
        } else if (System.getProperty("os.name").equals("Linux")) {
            // OpenJDK's process snapshot can omit arguments when the native read buffer is too small.
            // procfs preserves NUL boundaries, including empty arguments, without reconstructing a shell command.
            byte[] raw;
            try (var input = Files.newInputStream(Path.of("/proc/self/cmdline"))) { raw = input.readNBytes(128 * 1024 + 1); }
            if (raw.length == 0 || raw.length > 128 * 1024 || raw[raw.length - 1] != 0) throw new IOException("Linux command line unavailable or too large");
            List<String> parsed = new ArrayList<>(); int offset = 0;
            var charset = java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding", StandardCharsets.UTF_8.name()));
            for (int i = 0; i < raw.length; i++) if (raw[i] == 0) {
                parsed.add(charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(raw, offset, i - offset)).toString());
                offset = i + 1;
            }
            if (parsed.size() < 2) throw new IOException("Linux Java arguments unavailable");
            String original = parsed.getFirst();
            if (!Set.of("java", "java.exe").contains(original) && !Files.isSameFile(Path.of(original), executable))
                throw new IOException("Linux Java executable identity mismatch");
            command.addAll(parsed.subList(1, parsed.size()));
        } else command.addAll(Arrays.asList(info.arguments().orElseThrow(() -> new IOException("Java arguments unavailable"))));
        if (command.size() < 2) throw new IOException("Java launch arguments unavailable");
        return List.copyOf(command);
    }

    // Windows JDK 21 defaults to legacy escaping, which can silently swallow literal quotes.
    // The private helper selects Java's Win32 SAFE encoder; the game JVM's settings are never changed.
    static Process startJava(ProcessBuilder builder) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows")) return builder.start();
        synchronized (AutomaticRestart.class) {
            String key = "jdk.lang.Process.allowAmbiguousCommands", prior = System.getProperty(key);
            try { System.setProperty(key, "false"); return builder.start(); }
            finally { if (prior == null) System.clearProperty(key); else System.setProperty(key, prior); }
        }
    }
    static void verifyReplayable(List<String> command) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows")) return;
        StringBuilder rendered = new StringBuilder("\"").append(command.getFirst()).append('"');
        for (String arg : command.subList(1, command.size())) {
            // Java treats already enclosing quotes as syntax rather than literal characters.
            // Refuse that ambiguous case before shutting down the server.
            if (arg.length() >= 2 && arg.startsWith("\"") && arg.endsWith("\""))
                throw new IOException("Ambiguous enclosing quotes in Java arguments; restart refused");
            rendered.append(' ');
            if (!arg.isEmpty() && arg.chars().noneMatch(ch -> ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000b' || ch == '"')) {
                rendered.append(arg); continue;
            }
            rendered.append('"'); int slashes = 0;
            for (int i = 0; i < arg.length(); i++) {
                char ch = arg.charAt(i);
                if (ch == '\\') { slashes++; continue; }
                rendered.append("\\".repeat(ch == '"' ? slashes * 2 + 1 : slashes)).append(ch); slashes = 0;
            }
            rendered.append("\\".repeat(slashes * 2)).append('"');
        }
        if (!parseWindows(rendered.toString()).equals(command)) throw new IOException("Java arguments cannot be replayed exactly");
    }

    /** Microsoft CRT argument rules. argv[0] uses the separate executable-name rule. */
    static List<String> parseWindows(String input) throws IOException {
        if (input == null || input.isBlank() || input.indexOf('\0') >= 0) throw new IOException("Empty or invalid Windows command line");
        List<String> result = new ArrayList<>(); int i = 0, n = input.length();
        while (i < n && (input.charAt(i) == ' ' || input.charAt(i) == '\t')) i++;
        StringBuilder exe = new StringBuilder(); boolean quote = false;
        while (i < n) {
            char ch = input.charAt(i);
            if (ch == '"') { quote = !quote; i++; }
            else if (!quote && (ch == ' ' || ch == '\t')) break;
            else { exe.append(ch); i++; }
        }
        if (quote || exe.isEmpty()) throw new IOException("Invalid executable quoting");
        result.add(exe.toString());
        while (i < n) {
            while (i < n && (input.charAt(i) == ' ' || input.charAt(i) == '\t')) i++;
            if (i == n) break;
            StringBuilder arg = new StringBuilder(); quote = false;
            while (i < n) {
                char ch = input.charAt(i);
                if (!quote && (ch == ' ' || ch == '\t')) break;
                int slashes = 0;
                while (i < n && input.charAt(i) == '\\') { slashes++; i++; }
                if (i < n && input.charAt(i) == '"') {
                    arg.append("\\".repeat(slashes / 2));
                    if (slashes % 2 == 1) { arg.append('"'); i++; }
                    else if (quote && i + 1 < n && input.charAt(i + 1) == '"') { arg.append('"'); i += 2; }
                    else { quote = !quote; i++; }
                } else {
                    arg.append("\\".repeat(slashes));
                    if (i < n && (quote || (input.charAt(i) != ' ' && input.charAt(i) != '\t'))) arg.append(input.charAt(i++));
                }
            }
            if (quote) throw new IOException("Unclosed Windows argument quoting");
            result.add(arg.toString());
        }
        return result;
    }
    static final class Request { int schema = 1; long pid; String start, game, cwd, token; List<String> command; int count; }
    static final class Completion { long pid; String start, token, action; }
}
