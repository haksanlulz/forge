package forge.gamemodes.net.sharedart;

import forge.gamemodes.net.event.ArtReplyEvent;
import forge.gamemodes.net.event.ArtRequestEvent;
import forge.util.IHasForgeLog;
import forge.util.LogSafe;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * The sharing side of one connection: answers the host's requests for this
 * player's pictures under limits of its own. The host is the one asking, so
 * nothing the host enforces protects this end.
 *
 * <p>A request past any limit is dropped without a reply, so a host that
 * floods gets nothing back to amplify: at most
 * {@link SharedArtPolicy#MAX_IN_FLIGHT} files are read at once, at most
 * {@link SharedArtPolicy#MAX_OWNER_QUEUE} requests wait, at most
 * {@link SharedArtPolicy#MAX_SERVES_PER_GAME} are taken per game, one picture
 * is taken at most {@link SharedArtPolicy#MAX_SERVES_PER_KEY} times per game
 * (once for each other player, whatever the key's spelling), and at most
 * {@link SharedArtPolicy#MAX_SERVED_BYTES_PER_GAME} bytes are sent per game.
 * With SHARE off a request is answered at once with a refusal, within the
 * same queue and per-game allowance, so a host that still routes here after
 * SHARE went off is not left waiting. A request that is taken is answered,
 * with the picture or without; a picture past the game's byte allowance is
 * refused. Each reply waits for the connection to drop below its high-water mark, so
 * at most one reply is ever written past it; one still backed up after
 * {@link #WRITABLE_WAIT_MS} is dropped, and the asking side's own timer
 * settles it. The wait happens on the reader's own thread
 * ({@link #readerThread()}), never on a pool other work shares.
 */
public final class SharedArtOwner implements IHasForgeLog {

    /** Where replies go. */
    public interface Link {
        /** False while the connection is gone or past its outbound high-water mark. */
        boolean isWritable();

        void send(ArtReplyEvent reply);
    }

    /** How long a reply waits for a backed-up connection before it is dropped. */
    static final long WRITABLE_WAIT_MS = 5_000;
    private static final long WRITABLE_POLL_MS = 50;

    private static final class ReaderHolder {
        static final ExecutorService READER = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "SharedArt-Owner");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /**
     * One thread of its own for sharing players' file reads and the waits on a
     * backed-up connection, so neither ever holds a thread of a shared pool.
     */
    public static Executor readerThread() {
        return ReaderHolder.READER;
    }

    private final Executor executor;
    private final BooleanSupplier sharing;
    private final Function<String, byte[]> source;
    private final Link link;
    private final IntSupplier game;

    // Guarded by this.
    private final Deque<ArtRequestEvent> queue = new ArrayDeque<>();
    private final Map<String, Integer> takenPerKey = new HashMap<>();
    private int readers;
    private int taken;
    private long bytesSent;
    private int takenGame;

    /**
     * @param executor runs the file reads, off the network thread
     * @param sharing  this player's SHARE switch
     * @param source   the picture for a key, or null to refuse
     * @param game     changes when a game ends, which renews the per-game allowance
     */
    public SharedArtOwner(final Executor executor, final BooleanSupplier sharing,
                          final Function<String, byte[]> source, final Link link, final IntSupplier game) {
        this.executor = executor;
        this.sharing = sharing;
        this.source = source;
        this.link = link;
        this.game = game;
        this.takenGame = game.getAsInt();
    }

    /** @return whether the request was taken */
    public boolean offer(final ArtRequestEvent request) {
        // Neither SHARE nor a backed-up connection refuses here: the queue cap is the flood bound.
        final String id = request == null ? null : SharedArtPolicy.requestIdOf(request.getImageKey());
        if (id == null) {
            return false;
        }
        final int current = game.getAsInt();
        synchronized (this) {
            if (current != takenGame) {
                takenGame = current;
                taken = 0;
                bytesSent = 0;
                takenPerKey.clear();
            }
            if (taken >= SharedArtPolicy.MAX_SERVES_PER_GAME || queue.size() >= SharedArtPolicy.MAX_OWNER_QUEUE
                    || takenPerKey.getOrDefault(id, 0) >= SharedArtPolicy.MAX_SERVES_PER_KEY) {
                return false;
            }
            taken++;
            takenPerKey.merge(id, 1, Integer::sum);
            queue.add(request);
            if (readers >= SharedArtPolicy.MAX_IN_FLIGHT) {
                return true; // a reader already running picks it up
            }
            readers++;
        }
        try {
            executor.execute(this::drain);
        } catch (final RuntimeException e) {
            synchronized (this) {
                readers--;
            }
            netLog.debug("Shared art reader not started: {}", LogSafe.forLog(e.toString()));
        }
        return true;
    }

    private void drain() {
        boolean emptied = false;
        try {
            while (true) {
                final ArtRequestEvent request;
                synchronized (this) {
                    request = queue.poll();
                    if (request == null) {
                        readers--;
                        emptied = true;
                        return;
                    }
                }
                answer(request);
            }
        } finally {
            if (!emptied) {
                synchronized (this) {
                    readers--; // an Error ended the reader: the next request starts another
                }
            }
        }
    }

    private void answer(final ArtRequestEvent request) {
        byte[] bytes = null;
        try {
            bytes = sharing.getAsBoolean() ? source.apply(request.getImageKey()) : null;
        } catch (final RuntimeException ignored) {
            // answered as a refusal
        }
        if (bytes != null) {
            synchronized (this) {
                if (bytesSent + bytes.length > SharedArtPolicy.MAX_SERVED_BYTES_PER_GAME) {
                    bytes = null; // past the game's byte allowance: refused
                } else {
                    bytesSent += bytes.length;
                }
            }
        }
        try {
            // One reply past the high-water mark at most; still backed up after the wait, it is dropped.
            if (awaitWritable(WRITABLE_WAIT_MS)) {
                link.send(new ArtReplyEvent(request.getRequestId(), request.getImageKey(), SharedArtPolicy.split(bytes)));
            }
        } catch (final RuntimeException e) {
            netLog.debug("Shared art reply not sent: {}", LogSafe.forLog(e.toString()));
        }
    }

    private boolean awaitWritable(final long ms) {
        final long end = System.currentTimeMillis() + ms;
        while (!link.isWritable()) {
            if (System.currentTimeMillis() >= end) {
                return false;
            }
            try {
                Thread.sleep(WRITABLE_POLL_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
