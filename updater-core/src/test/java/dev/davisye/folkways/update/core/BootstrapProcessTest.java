package dev.davisye.folkways.update.core;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual child JVMs exercise the supervisor loop without starting a real world. */
class BootstrapProcessTest {
    @TempDir Path game;

    private String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private String classpath() throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        for (Class<?> type : List.of(Bootstrap.class, BootstrapProcessTest.class, Gson.class)) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        return String.join(System.getProperty("path.separator"), paths);
    }

    private int run(String mode) throws Exception {
        Updater.initialize(game);
        Config config = Config.load(game);
        config.restartCooldownSeconds = 1;
        config.restartLimit = 1;
        Files.writeString(game.resolve(".folkways-updater/config.json"), Updater.JSON.toJson(config));
        List<String> args = List.of(javaExecutable(), "-cp", classpath(), Bootstrap.class.getName(),
                "launch", "--game-dir", game.toString(), "--dedicated", "--", javaExecutable(),
                "-cp", classpath(), Child.class.getName(), mode, game.toString());
        Path log = game.resolve("process-test.log");
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Supervisor did not terminate");
            return process.exitValue();
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test void normalOneTimeRequestRestartsWholeChildJvm() throws Exception {
        assertEquals(0, run("normal"));
        assertEquals("2", Files.readString(game.resolve("launch-count")));
        assertFalse(Files.exists(game.resolve(".folkways-updater/child.json")));
    }

    @Test void nonzeroExitAndWrongTokenNeverRestart() throws Exception {
        assertEquals(7, run("crash"));
        assertEquals("1", Files.readString(game.resolve("launch-count")));
        Files.delete(game.resolve("launch-count"));
        assertEquals(0, run("wrong-token"));
        assertEquals("1", Files.readString(game.resolve("launch-count")));
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Path game = Path.of(args[1]);
            Path countFile = game.resolve("launch-count");
            int count = Files.exists(countFile) ? Integer.parseInt(Files.readString(countFile)) + 1 : 1;
            Files.writeString(countFile, Integer.toString(count));
            if (count == 1) {
                String token = args[0].equals("wrong-token") ? "wrong" : System.getenv("FOLKWAYS_SUPERVISOR_TOKEN");
                Map<String, String> request = Map.of("token", token, "action", "restart");
                Path session = Path.of(System.getenv("FOLKWAYS_SUPERVISOR_DIR"));
                Files.writeString(session.resolve("restart-request.json"), new Gson().toJson(request));
            }
            if (args[0].equals("crash")) System.exit(7);
        }
    }
}
