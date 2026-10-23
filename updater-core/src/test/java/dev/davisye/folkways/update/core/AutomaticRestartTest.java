package dev.davisye.folkways.update.core;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutomaticRestartTest {
    @TempDir Path game;
    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }
    static String classpath() throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        for (Class<?> c : List.of(AutomaticRestart.class, AutomaticRestartTest.class, Gson.class))
            paths.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString());
        return String.join(System.getProperty("path.separator"), paths);
    }
    private void run(String mode, int expected) throws Exception {
        Updater.initialize(game);
        Config config = Config.load(game); config.commandsEnabled = true; config.restartLimit = 1; config.restartCooldownSeconds = 1;
        Files.writeString(game.resolve(".folkways-updater/config.json"), Updater.JSON.toJson(config));
        List<String> args = List.of(mode.equals("path") ? "java" : javaExecutable(), "-cp", classpath(), Child.class.getName(), game.toString(), mode,
                "space arg", "中文路径", "unicode-\u1c50", "quote\"arg", "backslash\\", "", "x".repeat(10000));
        Process p = AutomaticRestart.startJava(new ProcessBuilder(args).directory(game.toFile()).redirectErrorStream(true)
                .redirectOutput(game.resolve("parent.log").toFile()));
        try {
            assertTrue(p.waitFor(25, TimeUnit.SECONDS), "Original parent JVM did not exit");
            assertEquals(0, p.exitValue(), Files.readString(game.resolve("parent.log")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while ((!Files.exists(game.resolve("helper-done")) || (expected == 2 && !Files.exists(game.resolve("second-launch")))) && System.nanoTime() < deadline)
                Thread.sleep(50);
            assertTrue(Files.exists(game.resolve("helper-done")), "After-exit process did not finish: " + Files.readString(game.resolve("helper.log")));
            assertEquals(Integer.toString(expected), Files.readString(game.resolve("launch-count")));
            if (expected == 2) ProcessHandle.of(Long.parseLong(Files.readString(game.resolve("second-pid"))))
                    .filter(ProcessHandle::isAlive).ifPresent(child -> child.onExit().join());
            assertFalse(Files.exists(game.resolve("third-launch")), "Restart budget was bypassed");
        } finally {
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                assertTrue(p.waitFor(10, TimeUnit.SECONDS), "Synthetic parent did not terminate");
            }
            Path helper = game.resolve("helper-pid");
            if (Files.exists(helper)) {
                ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(helper))).orElse(null);
                if (child != null && child.isAlive()) {
                    // helper-done is written before the JVM releases helper.log on Windows.
                    try { child.onExit().get(10, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException timeout) {
                        child.destroyForcibly();
                        child.onExit().get(10, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }
    @Test void completedSaveRestartsExactJavaArgumentsAndPreservesWorkingDirectoryOnce() throws Exception { run("normal", 2); }
    @Test void pathBasedJavaLaunchRestartsUsingActualExecutable() throws Exception { run("path", 2); }
    @Test void noCompletedSaveNeverRestarts() throws Exception { run("crash", 1); }
    @Test void wrongCompletionTokenNeverRestarts() throws Exception { run("wrong-token", 1); }
    @Test void incompleteTransactionNeverRestarts() throws Exception { run("journal", 1); }
    @Test void disablingCommandsAfterCompletionNeverRestarts() throws Exception { run("disabled", 1); }

    @Test void microsoftDocumentedQuotingExamplesAndEmptyArguments() throws Exception {
        assertEquals(List.of("java.exe", "ab\"c", "\\", "d"), AutomaticRestart.parseWindows("java.exe \"ab\\\"c\" \"\\\\\" d"));
        assertEquals(List.of("C:\\Program Files\\java.exe", "", "de fg", "h"),
                AutomaticRestart.parseWindows("\"C:\\Program Files\\java.exe\" \"\" d\"e f\"g h"));
        assertThrows(java.io.IOException.class, () -> AutomaticRestart.parseWindows("java.exe \"unfinished"));
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            Path game = Path.of(args[0]);
            if (!Files.isSameFile(Path.of("").toAbsolutePath(), game)) throw new AssertionError("cwd changed");
            List<String> rawArgs = List.of("space arg", "中文路径", "unicode-\u1c50", "quote\"arg", "backslash\\", "", "x".repeat(10000));
            // Windows java.exe decodes main arguments using the host native code page. US hosts cannot
            // represent Chinese there; compare the original Java-visible baseline as well as the raw OS vector.
            var nativeCharset = java.nio.charset.Charset.forName(System.getProperty("native.encoding", "UTF-8"));
            List<String> expectedArgs = System.getProperty("os.name").startsWith("Windows")
                    ? rawArgs.stream().map(arg -> new String(arg.getBytes(nativeCharset), nativeCharset)).toList() : rawArgs;
            List<String> actualArgs = Arrays.asList(args).subList(2, args.length);
            if (!expectedArgs.equals(actualArgs)) {
                StringBuilder diagnostic = new StringBuilder("Synthetic argument vector changed: expected count=").append(expectedArgs.size()).append(", actual count=").append(actualArgs.size());
                for (int i = 0; i < Math.min(expectedArgs.size(), actualArgs.size()); i++) if (!expectedArgs.get(i).equals(actualArgs.get(i)))
                    diagnostic.append("; index=").append(i).append(" expectedLength=").append(expectedArgs.get(i).length())
                            .append(" actualLength=").append(actualArgs.get(i).length()).append(" firstCodePoints=")
                            .append(Arrays.toString(actualArgs.get(i).codePoints().limit(24).toArray()));
                throw new AssertionError(diagnostic);
            }
            List<String> captured = AutomaticRestart.captureCommand();
            if (!captured.subList(captured.size() - rawArgs.size(), captured.size()).equals(rawArgs))
                throw new AssertionError("Raw OS argument vector changed, including Unicode");
            Path countFile = game.resolve("launch-count");
            int count = Files.exists(countFile) ? Integer.parseInt(Files.readString(countFile)) + 1 : 1;
            Files.writeString(countFile, Integer.toString(count));
            if (count > 2) Files.writeString(game.resolve("third-launch"), "unexpected");
            if (count == 2) {
                try { AutomaticRestart.prepare(game); throw new AssertionError("Restart budget ignored"); }
                catch (java.io.IOException expected) { }
                Files.writeString(game.resolve("second-pid"), Long.toString(ProcessHandle.current().pid()));
                Files.writeString(game.resolve("second-launch"), "ok"); return;
            }
            AutomaticRestart restart = AutomaticRestart.prepare(game);
            if (!restart.available()) throw new AssertionError("Restart capability unavailable");
            if (!args[1].equals("crash")) restart.complete();
            if (args[1].equals("wrong-token")) {
                try (var files = Files.list(game.resolve(".folkways-updater/automatic-restart"))) {
                    Path completion = files.filter(p -> p.getFileName().toString().endsWith(".complete.json")).findFirst().orElseThrow();
                    var obj = com.google.gson.JsonParser.parseString(Files.readString(completion)).getAsJsonObject();
                    obj.addProperty("token", "wrong"); Files.writeString(completion, obj.toString());
                }
            }
            if (args[1].equals("journal")) Files.writeString(game.resolve(".folkways-updater/dropin-journal.json"), "{}");
            if (args[1].equals("disabled")) {
                Config c = Config.load(game); c.commandsEnabled = false;
                Files.writeString(game.resolve(".folkways-updater/config.json"), Updater.JSON.toJson(c));
            }
            String start = ProcessHandle.current().info().startInstant().orElseThrow().toString();
            Process helper = new ProcessBuilder(javaExecutable(), "-cp", classpath(), Waiter.class.getName(), game.toString(),
                    Long.toString(ProcessHandle.current().pid()), start).redirectErrorStream(true)
                    .redirectOutput(game.resolve("helper.log").toFile()).start();
            Files.writeString(game.resolve("helper-pid"), Long.toString(helper.pid()));
        }
    }
    public static class Waiter {
        public static void main(String[] args) throws Exception {
            Path game = Path.of(args[0]);
            ProcessHandle.of(Long.parseLong(args[1])).ifPresent(p -> p.onExit().join());
            try { AutomaticRestart.afterExit(game, Long.parseLong(args[1]), args[2]); }
            catch (Exception expected) { Files.writeString(game.resolve("refusal"), expected.getClass().getSimpleName()); }
            Files.writeString(game.resolve("helper-done"), "done");
        }
    }
}
