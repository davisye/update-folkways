package dev.davisye.folkways.update.mod;

import dev.davisye.folkways.update.core.Config;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClientCheckRequestTest {
    @TempDir Path game;
    @Test void confirmationOwnsItsSelectionDespiteLaterMutableEdits() throws Exception {
        Config draft = new Config();
        ClientCheckRequest confirmed = new ClientCheckRequest(draft, true);
        Object identity = confirmed.identity();
        draft.repository = "davisye/folkways";
        draft.modules.add("create");
        Config exposed = confirmed.settings();
        exposed.repository = draft.repository;
        exposed.modules.add("cookery");
        assertEquals("IzakyL/folkways", confirmed.settings().repository);
        assertEquals(List.of("core", "vanilla"), confirmed.settings().modules);
        assertEquals(identity, confirmed.identity());
        assertTrue(confirmed.takeOverExisting());
    }

    @Test void explicitTakeoverIsNotCoalescedWithAnActiveOrdinaryCheck() throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var checked = new ArrayList<Boolean>(); var results = new ArrayList<Boolean>();
        var ref = new AtomicReference<LatestCheckQueue<ClientCheckRequest, Boolean>>();
        ClientCheckRequest takeover = new ClientCheckRequest(new Config(), true);
        var queue = new LatestCheckQueue<ClientCheckRequest, Boolean>(tasks::add, (request, current) -> {
            checked.add(request.takeOverExisting());
            if (!request.takeOverExisting()) {
                ref.get().request(takeover);
                assertFalse(current.getAsBoolean());
            }
            return request.takeOverExisting();
        }, ignored -> {}, results::add, ClientCheckRequest::identity);
        ref.set(queue);
        queue.request(new ClientCheckRequest(new Config(), false));
        tasks.remove().run(); tasks.remove().run();
        assertEquals(List.of(false, true), checked);
        assertEquals(List.of(true), results);
        assertFalse(queue.running());
    }

    @Test void laterSavedSourceCancelsQueuedTakeoverWithoutTransferringAuthorization() throws Exception {
        var tasks = new ArrayDeque<Runnable>(); var checked = new ArrayList<ClientCheckRequest>();
        var queue = new LatestCheckQueue<ClientCheckRequest, Boolean>(tasks::add, (request, current) -> {
            checked.add(request); return request.takeOverExisting();
        }, ignored -> {}, ignored -> {}, ClientCheckRequest::identity);
        queue.request(new ClientCheckRequest(new Config(), true));
        Config newer = new Config(); newer.repository = "davisye/folkways"; newer.modules.add("create");
        queue.request(new ClientCheckRequest(newer, false));
        tasks.remove().run();
        assertEquals(1, checked.size());
        assertEquals("davisye/folkways", checked.getFirst().settings().repository);
        assertTrue(checked.getFirst().settings().modules.contains("create"));
        assertFalse(checked.getFirst().takeOverExisting());
    }

    @Test void nextOrdinaryRoundDoesNotInheritTheFinishedTakeover() throws Exception {
        var tasks = new ArrayDeque<Runnable>(); var checked = new ArrayList<Boolean>();
        var queue = new LatestCheckQueue<ClientCheckRequest, Boolean>(tasks::add, (request, current) -> {
            checked.add(request.takeOverExisting()); return request.takeOverExisting();
        }, ignored -> {}, ignored -> {}, ClientCheckRequest::identity);
        queue.request(new ClientCheckRequest(new Config(), true)); tasks.remove().run();
        queue.request(new ClientCheckRequest(new Config(), false)); tasks.remove().run();
        assertEquals(List.of(true, false), checked);
    }

    @Test void switchingAwayAndBackCannotReviveAnActiveTakeoverTicket() throws Exception {
        var tasks = new ArrayDeque<Runnable>(); var results = new ArrayList<Boolean>();
        var ref = new AtomicReference<LatestCheckQueue<ClientCheckRequest, Boolean>>();
        var queue = new LatestCheckQueue<ClientCheckRequest, Boolean>(tasks::add, (request, current) -> {
            if (request.takeOverExisting()) {
                Config other = new Config(); other.repository = "davisye/folkways";
                try {
                    ref.get().request(new ClientCheckRequest(other, false));
                    ref.get().request(new ClientCheckRequest(request.settings(), false));
                } catch (Exception error) { throw new AssertionError(error); }
                assertFalse(current.getAsBoolean(), "The original one-time authorization must stay revoked");
            }
            return request.takeOverExisting();
        }, ignored -> {}, results::add, ClientCheckRequest::identity);
        ref.set(queue);
        queue.request(new ClientCheckRequest(new Config(), true));
        tasks.remove().run(); tasks.remove().run();
        assertEquals(List.of(false), results);
    }

    @Test void enablingLocalModeDuringConfirmationCannotBeOverwrittenByOldConsent() throws Exception {
        Config.edit(game, current -> current.localDevelopment = false);
        Config confirmed = Config.load(game);
        Config.edit(game, current -> current.localDevelopment = true);
        Path file = game.resolve(".folkways-updater/config.json");
        byte[] before = Files.readAllBytes(file);
        assertThrows(IllegalStateException.class, () -> Config.edit(game, current -> {
            ClientCheckRequest.requireTakeoverAllowed(confirmed, current);
            current.localDevelopment = confirmed.localDevelopment;
        }));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(Config.load(game).localDevelopment);
    }

    @Test void switchingToSignedSourceDuringConfirmationAlsoRetainsTheCurrentConfig() throws Exception {
        Config confirmed = new Config();
        Config.edit(game, current -> current.manifestUrl = "https://pan.davisye.cn/d/manifest.json");
        Path file = game.resolve(".folkways-updater/config.json");
        byte[] before = Files.readAllBytes(file);
        assertThrows(IllegalStateException.class, () -> Config.edit(game, current -> {
            ClientCheckRequest.requireTakeoverAllowed(confirmed, current);
            current.repository = "davisye/folkways";
        }));
        assertArrayEquals(before, Files.readAllBytes(file));
    }
}
