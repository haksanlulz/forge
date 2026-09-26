package forge.gamemodes.net.sharedart;

import forge.deck.Deck;
import forge.gamemodes.net.event.ArtReplyEvent;
import forge.gamemodes.net.event.ArtRequestEvent;
import forge.util.IHasForgeLog;
import forge.util.LogSafe;
import forge.util.ThreadUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The asking side of shared custom art for one connection: which pictures were
 * asked for this game, which are on their way, and the bytes that arrived.
 * Everything here is memory only and cleared at game end; nothing received is
 * ever written to disk.
 *
 * <p>Touches neither preferences nor card data itself, so it stays testable
 * without either: the connection supplies the transport and the SHOW switch.
 */
public final class SharedArtSession implements IHasForgeLog {

    /** Sends a request towards the host. */
    public interface Transport {
        void send(ArtRequestEvent request);
    }

    /** Runs a task later. */
    public interface Timer {
        void schedule(int delayMs, Runnable task);
    }

    /** Told off the UI thread when a request settles, arrived or not, and when a session stops being the active one. */
    public interface Listener {
        void onArtSettled(SharedArtSession session, String imageKey, boolean arrived);

        default void onSessionEnded(SharedArtSession session) {
        }
    }

    private static final AtomicReference<SharedArtSession> ACTIVE = new AtomicReference<>();
    private static volatile Listener listener;

    /** The session the renderer asks, or null outside a network game with a capable peer. */
    public static SharedArtSession active() {
        return ACTIVE.get();
    }

    public static void setActive(final SharedArtSession session) {
        final SharedArtSession previous = ACTIVE.getAndSet(session);
        if (previous != null && previous != session) {
            previous.ended();
        }
    }

    /** Ends a session's turn as the active one; whatever it showed must go. */
    public static void clearActive(final SharedArtSession session) {
        if (session != null && ACTIVE.compareAndSet(session, null)) {
            session.ended();
        }
    }

    public static void setListener(final Listener l) {
        listener = l;
    }

    private final Transport transport;
    private final BooleanSupplier showEnabled;
    private final Timer timer;
    private volatile boolean peerCapable;
    private volatile Supplier<? extends Iterable<Deck>> lobbyDecks = List::of;

    // Game-scoped, guarded by this.
    private final Deque<String> queued = new ArrayDeque<>();
    private final Map<Integer, String> inFlight = new HashMap<>();
    /** Queued, in flight, arrived or failed this game: each key is asked for once. */
    private final Set<String> seen = new HashSet<>();
    private final Map<String, byte[]> received = new HashMap<>();
    /** Requests sent this game; never given back, so a refusal that frees a key cannot extend the budget. */
    private int requested;
    private long bytesReceived;
    private int nextId;
    private int generation;
    /** Requests in a row the host left unanswered, not even with a refusal. */
    private int consecutiveTimeouts;

    public SharedArtSession(final Transport transport, final BooleanSupplier showEnabled) {
        this(transport, showEnabled, ThreadUtil::delay);
    }

    public SharedArtSession(final Transport transport, final BooleanSupplier showEnabled, final Timer timer) {
        this.transport = transport;
        this.showEnabled = showEnabled;
        this.timer = timer;
    }

    /** Where the decks of the lobby this connection plays in come from: what is asked for when a match starts. */
    public void setLobbyDecks(final Supplier<? extends Iterable<Deck>> decks) {
        this.lobbyDecks = decks == null ? List::of : decks;
    }

    /** The decks every player in this connection's lobby received, as last seen; never null. */
    public Iterable<Deck> lobbyDecks() {
        try {
            final Iterable<Deck> decks = lobbyDecks.get();
            return decks == null ? List.of() : decks;
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    public boolean isShowEnabled() {
        return showEnabled.getAsBoolean();
    }

    /** Whether the other end announced {@code NetCapabilities.SHARED_ART}; nothing is sent until it has. */
    public boolean isPeerCapable() {
        return peerCapable;
    }

    /** Off while the connection is down; queued requests wait and go out once the peer answers again. */
    public void setPeerCapable(final boolean capable) {
        final List<ArtRequestEvent> toSend;
        synchronized (this) {
            peerCapable = capable;
            toSend = capable ? pumpLocked() : Collections.emptyList();
        }
        sendAll(toSend);
    }

    /**
     * Asks for a picture unless SHOW is off, the peer never announced the
     * capability, the key was already asked for this game, or the game's
     * request or byte budget is spent.
     *
     * @return whether a request was queued
     */
    public boolean request(final String key) {
        if (key == null || !showEnabled.getAsBoolean() || !peerCapable) {
            return false;
        }
        final List<ArtRequestEvent> toSend;
        synchronized (this) {
            if (seen.contains(key) || requested >= SharedArtPolicy.MAX_REQUESTS_PER_GAME
                    || bytesReceived + SharedArtPolicy.MAX_BYTES > SharedArtPolicy.MAX_BYTES_PER_GAME) {
                return false;
            }
            requested++;
            seen.add(key);
            queued.add(key);
            toSend = pumpLocked();
        }
        sendAll(toSend);
        return true;
    }

    /** Whether this key was already asked for this game, whatever came of it. */
    public synchronized boolean hasSeen(final String key) {
        return seen.contains(key);
    }

    public synchronized boolean isPending(final String key) {
        return queued.contains(key) || inFlight.containsValue(key);
    }

    /** The validated bytes that arrived for a key this game, or null. */
    public synchronized byte[] receivedBytes(final String key) {
        return received.get(key);
    }

    /** Drops a key's bytes once decoded or found unusable; the key stays asked-for, so it is not asked again. */
    public synchronized void discard(final String key) {
        received.remove(key);
    }

    /** Changes at every game end. */
    public synchronized int generation() {
        return generation;
    }

    /**
     * Takes a reply. Stale, foreign or invalid replies settle as not arrived, or
     * are ignored outright. A negative request id is the host saying no game is
     * running: the key settles as not arrived but may be asked for again.
     */
    public void onReply(final ArtReplyEvent reply) {
        if (reply == null) {
            return;
        }
        final boolean noGame = reply.getRequestId() < 0;
        final int id = noGame ? -reply.getRequestId() : reply.getRequestId();
        final String key;
        final boolean arrived;
        final List<ArtRequestEvent> toSend;
        synchronized (this) {
            key = inFlight.get(id);
            if (key == null || !key.equals(reply.getImageKey())) {
                return; // stale, from an earlier game, or not ours
            }
            inFlight.remove(id);
            consecutiveTimeouts = 0; // the host is answering
            final byte[] bytes = noGame ? null : SharedArtPolicy.join(reply.getChunks());
            arrived = bytes != null && bytesReceived + bytes.length <= SharedArtPolicy.MAX_BYTES_PER_GAME;
            if (arrived) {
                bytesReceived += bytes.length;
                received.put(key, bytes);
            } else if (noGame) {
                seen.remove(key);
            }
            toSend = pumpLocked();
        }
        settle(key, arrived);
        sendAll(toSend);
    }

    /** Forgets everything this game asked for and received. */
    public synchronized void endGame() {
        queued.clear();
        inFlight.clear();
        seen.clear();
        received.clear();
        requested = 0;
        bytesReceived = 0;
        consecutiveTimeouts = 0;
        generation++;
    }

    private List<ArtRequestEvent> pumpLocked() {
        final List<ArtRequestEvent> out = new ArrayList<>(SharedArtPolicy.MAX_IN_FLIGHT);
        while (peerCapable && inFlight.size() < SharedArtPolicy.MAX_IN_FLIGHT && !queued.isEmpty()) {
            final String key = queued.poll();
            final int id = ++nextId;
            inFlight.put(id, key);
            final int gen = generation;
            timer.schedule(SharedArtPolicy.REQUEST_TIMEOUT_MS, () -> expire(id, gen));
            out.add(new ArtRequestEvent(id, key));
        }
        return out;
    }

    /**
     * A request the host never answered, not even with a refusal, costs its
     * own key: a reply dropped on a backed-up connection looks the same. A
     * second in a row means the host has stopped answering, so every key
     * queued behind it settles as not arrived too, rather than each waiting
     * out a timeout of its own.
     */
    private void expire(final int id, final int gen) {
        final List<String> gaveUp = new ArrayList<>();
        final List<ArtRequestEvent> toSend;
        synchronized (this) {
            if (gen != generation) {
                return;
            }
            final String key = inFlight.remove(id);
            if (key == null) {
                return;
            }
            gaveUp.add(key);
            if (++consecutiveTimeouts >= 2) { // two silent requests in a row: the host has stopped answering
                gaveUp.addAll(queued);
                queued.clear();
                toSend = Collections.emptyList();
            } else {
                toSend = pumpLocked(); // one lost reply costs one key
            }
        }
        for (final String key : gaveUp) {
            settle(key, false);
        }
        sendAll(toSend);
    }

    private void sendAll(final List<ArtRequestEvent> requests) {
        for (final ArtRequestEvent request : requests) {
            try {
                transport.send(request);
            } catch (final RuntimeException e) {
                netLog.debug("Shared art request not sent: {}", LogSafe.forLog(e.toString()));
            }
        }
    }

    private void settle(final String key, final boolean arrived) {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onArtSettled(this, key, arrived);
        } catch (final RuntimeException e) {
            netLog.debug("Shared art listener failed: {}", LogSafe.forLog(e.toString()));
        }
    }

    private void ended() {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onSessionEnded(this);
        } catch (final RuntimeException e) {
            netLog.debug("Shared art listener failed: {}", LogSafe.forLog(e.toString()));
        }
    }
}
