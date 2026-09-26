package forge.gamemodes.net.server;

import forge.deck.Deck;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.ArtReplyEvent;
import forge.gamemodes.net.event.ArtRequestEvent;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.event.NetCapabilities;
import forge.gamemodes.net.sharedart.SharedArtPolicy;
import forge.gamemodes.net.sharedart.SharedArtSession;
import forge.gamemodes.net.sharedart.SharedArtSource;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IHasForgeLog;
import forge.util.LogSafe;
import forge.util.ThreadUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The host's router for shared custom art. A request names a card image key;
 * the owners are the seats whose decks hold that picture and whose players
 * share, in index order, and the host asks them in turn until one sends it.
 *
 * <p>Which seat holds what is fixed once per game, from the decks the running
 * game was built from where the host can map a seat to its player, and from
 * the lobby otherwise; a client can rewrite its lobby deck at any time, even
 * mid-match. Only cards this host's own card database holds in a custom
 * edition of that code count, since a deck from the wire may name any card in
 * any edition. Every logged-in player already sees every deck in the lobby
 * state, so routing reveals nothing new.
 *
 * <p>The host serves its own seats' pictures itself and relays everyone
 * else's, accepting a reply only from the seat it asked. A refusal, a reply
 * that is not a picture, a departed seat, or silence past
 * {@link SharedArtPolicy#RELAY_TIMEOUT_MS} moves the request on to the next
 * seat, at most {@link SharedArtPolicy#MAX_RELAY_HOPS} remote seats per
 * request; when none is left the request is refused.
 *
 * <p>Budgets are per requester per game and reset when the hosted game
 * changes. Past the request budget a requester gets no answer at all, since
 * an honest one never gets there. Outside a match a request is refused with a
 * negated id, which tells the asking side it may ask again later, at most
 * {@link SharedArtPolicy#MAX_REQUESTS_PER_GAME} times per requester until a
 * game starts. A key that is not servable is dropped unanswered, in a match
 * or out of one, since an honest asker never sends one.
 *
 * <p>The host relays for any players who opted in, whatever its own SHARE
 * and SHOW switches say; those govern only its own seats and its own view.
 *
 * <p>Locking: this object's monitor guards the routing state. Sends happen
 * outside it, and the host's own requests and the replies to them go through
 * the executor, so nothing re-enters it on the caller's stack.
 */
final class SharedArtRelay implements IHasForgeLog {

    /** What the relay needs from the server, so it runs without sockets or a live game. */
    interface Host {
        /** The running game, or null outside a match; budgets reset when it changes. */
        Object currentGame();

        int seatCount();

        /** Null for an empty seat. */
        LobbySlotType seatType(int seat);

        /**
         * The art ids ({@link SharedArtPolicy#artIdOf}) of the cards the seat
         * brought to the running game that this host knows in a custom set.
         * Read once per game.
         */
        Set<String> seatArt(int seat);

        RemoteClient seatClient(int seat);

        /** The host player's SHARE switch, which covers every seat on this machine. */
        boolean hostShares();

        /** The host player's SHOW switch. */
        boolean hostShows();

        /** The lobby's decks, as every player receives them: what the host's own view asks for. */
        List<Deck> lobbyDecks();

        /** A picture from this machine's files for a seat on it, or null. */
        byte[] serveLocal(int seat, String key);
    }

    /** Runs a task later. */
    interface Timer {
        void schedule(int delayMs, Runnable task);
    }

    private static final class Budget {
        int requests;
        int pending;
        /** Bytes granted this game, counted when answered. */
        long bytes;
        /** Pictures asked for this game, by {@link SharedArtPolicy#requestIdOf}, so no respelling asks again. */
        final Set<String> keys = new HashSet<>();
    }

    /** One request on its way through the seats that hold its picture. */
    private static final class Relay {
        final RemoteClient requester; // null for the host's own request
        final int requesterId;
        final String key;
        final Budget budget;
        final List<Integer> seats;
        int next;
        int remoteHops;
        /** The remote seat asked now, and the id it was asked under; null while none is. */
        RemoteClient owner;
        int relayId;

        Relay(final RemoteClient requester, final int requesterId, final String key,
              final Budget budget, final List<Integer> seats) {
            this.requester = requester;
            this.requesterId = requesterId;
            this.key = key;
            this.budget = budget;
            this.seats = seats;
        }
    }

    /** Budget key for the host's own requests. */
    private static final Object HOST = new Object();
    private static final Runnable NOTHING = () -> { };

    private final Host host;
    private final Executor executor;
    private final Timer timer;
    private final SharedArtSession hostSession;
    /** The game the budgets and the seats' holdings belong to. */
    private Object game;
    private final Map<Integer, Set<String>> seatArt = new HashMap<>();
    private final Map<Object, Budget> budgets = new HashMap<>();
    private final Map<Integer, Relay> relays = new HashMap<>();
    /** Refusals sent outside a match, per requester; cleared when a game starts. */
    private final Map<Object, Integer> idleRefusals = new HashMap<>();
    private int nextRelayId;

    SharedArtRelay(final FServerManager server) {
        this(new ServerHost(server), task -> ThreadUtil.getServicePool().submit(task),
                (delayMs, task) -> ThreadUtil.delay(delayMs, task));
    }

    SharedArtRelay(final Host host, final Executor executor, final Timer timer) {
        this.host = host;
        this.executor = executor;
        this.timer = timer;
        this.hostSession = new SharedArtSession(request -> executor.execute(() -> onRequest(null, request)), host::hostShows);
        hostSession.setLobbyDecks(host::lobbyDecks);
        hostSession.setPeerCapable(true); // the host is its own router
    }

    /**
     * Records what a peer announced in its login trailer, and answers only a
     * peer that announced. The trailer is a handshake, not lobby state, so it
     * is taken off the login before the host passes the login on.
     */
    static void acceptCapabilities(final RemoteClient client, final LoginEvent event) {
        final NetCapabilities caps = event.getCapabilities();
        event.setCapabilities(null);
        client.setSharedArt(caps != null && caps.has(NetCapabilities.SHARED_ART));
        client.setServesSharedArt(client.supportsSharedArt() && caps.has(NetCapabilities.SHARED_ART_SERVE));
        if (client.supportsSharedArt()) {
            client.send(NetCapabilities.local());
        }
    }

    /** The host player's own asking side; its requests enter this relay through the executor. */
    SharedArtSession hostSession() {
        return hostSession;
    }

    synchronized void reset() {
        game = null;
        seatArt.clear();
        budgets.clear();
        relays.clear();
        idleRefusals.clear();
        hostSession.endGame();
    }

    /** A request from a seat, or from the host itself when {@code from} is null. */
    void onRequest(final RemoteClient from, final ArtRequestEvent request) {
        final Runnable next;
        synchronized (this) {
            next = route(from, request.getRequestId(), request.getImageKey());
        }
        next.run();
    }

    private Runnable route(final RemoteClient from, final int id, final String key) {
        // An honest asker only sends servable keys. Anything else is dropped unanswered, so an
        // oversized key is never echoed (writeUTF throws past 65,535 bytes) or logged.
        final String requestId = SharedArtPolicy.requestIdOf(key);
        if (requestId == null) {
            return NOTHING;
        }
        final Object current = host.currentGame();
        if (current == null) {
            if (idleRefusals.merge(from == null ? HOST : from, 1, Integer::sum) > SharedArtPolicy.MAX_REQUESTS_PER_GAME) {
                return NOTHING; // an honest asker only asks while a match view is open
            }
            return () -> deliver(from, -id, key, null); // no game: the asking side may ask again in one
        }
        if (current != game) {
            game = current;
            budgets.clear();
            relays.clear();
            idleRefusals.clear();
            takeSeatArt();
        }
        final Budget budget = budgets.computeIfAbsent(from == null ? HOST : from, k -> new Budget());
        if (budget.requests >= SharedArtPolicy.MAX_REQUESTS_PER_GAME) {
            return NOTHING; // silent: an honest asker never gets here, and a flood gets nothing to amplify
        }
        budget.requests++;
        if (budget.pending >= SharedArtPolicy.MAX_IN_FLIGHT
                // Every picture in flight could be the largest, so the byte budget is reserved up front.
                || budget.bytes + (budget.pending + 1L) * SharedArtPolicy.MAX_BYTES > SharedArtPolicy.MAX_BYTES_PER_GAME
                || !budget.keys.add(requestId)) {
            return () -> deliver(from, id, key, null);
        }
        final List<Integer> seats = holders(SharedArtPolicy.artIdOf(key), from);
        if (seats.isEmpty()) {
            return () -> deliver(from, id, key, null);
        }
        budget.pending++;
        return advance(new Relay(from, id, key, budget, seats));
    }

    /** Which seat holds what this game. A seat whose deck cannot be read holds nothing. */
    private void takeSeatArt() {
        seatArt.clear();
        for (int i = 0; i < host.seatCount(); i++) {
            try {
                final Set<String> art = host.seatArt(i);
                seatArt.put(i, art == null ? Collections.emptySet() : art);
            } catch (final RuntimeException e) {
                netLog.debug("Shared art: deck of slot {} unreadable: {}", i, LogSafe.forLog(e.toString()));
                seatArt.put(i, Collections.emptySet());
            }
        }
    }

    /**
     * The seats, in index order, whose decks hold the picture and whose
     * players share. A remote seat counts only if it announced it serves;
     * the seats on this machine only if the host player shares. The
     * requester's own seat never counts, and for the host's own request
     * neither do the seats on this machine.
     */
    private List<Integer> holders(final String artId, final RemoteClient from) {
        final List<Integer> seats = new ArrayList<>();
        for (int i = 0; i < host.seatCount(); i++) {
            final LobbySlotType type = host.seatType(i);
            if (type == null || !seatArt.getOrDefault(i, Collections.emptySet()).contains(artId)) {
                continue;
            }
            final boolean local = type == LobbySlotType.LOCAL || type == LobbySlotType.AI;
            if (from == null ? local : i == from.getIndex()) {
                continue;
            }
            if (local ? host.hostShares() : type == LobbySlotType.REMOTE && serves(host.seatClient(i))) {
                seats.add(i);
            }
        }
        return seats;
    }

    private static boolean serves(final RemoteClient client) {
        return client != null && client.supportsSharedArt() && client.servesSharedArt();
    }

    /**
     * Asks the relay's next seat, or refuses the request when none is left.
     * Called under this object's monitor; the returned action runs outside it.
     */
    private Runnable advance(final Relay relay) {
        relay.owner = null;
        while (relay.next < relay.seats.size()) {
            final int seat = relay.seats.get(relay.next++);
            if (host.seatType(seat) != LobbySlotType.REMOTE) {
                // A seat on this machine: the host's own files, read off the IO thread.
                return () -> executor.execute(() -> serveFromHost(relay, seat));
            }
            final RemoteClient owner = host.seatClient(seat);
            if (!serves(owner) || owner == relay.requester || !owner.isWritable()) {
                continue;
            }
            if (relay.remoteHops >= SharedArtPolicy.MAX_RELAY_HOPS) {
                break;
            }
            relay.remoteHops++;
            final int relayId = ++nextRelayId;
            relay.owner = owner;
            relay.relayId = relayId;
            relays.put(relayId, relay);
            timer.schedule(SharedArtPolicy.RELAY_TIMEOUT_MS, () -> expireRelay(relayId));
            return () -> owner.send(new ArtRequestEvent(relayId, relay.key));
        }
        return finish(relay, null);
    }

    /** The request's answer: frees its place in flight and counts its bytes. Called under this object's monitor. */
    private Runnable finish(final Relay relay, final byte[][] chunks) {
        relay.budget.pending--;
        relay.budget.bytes += SharedArtPolicy.total(chunks);
        return () -> deliver(relay.requester, relay.requesterId, relay.key, chunks);
    }

    private void serveFromHost(final Relay relay, final int seat) {
        byte[][] chunks = null;
        try {
            chunks = SharedArtPolicy.split(host.serveLocal(seat, relay.key));
        } catch (final RuntimeException e) {
            netLog.debug("Shared art not served: {}", LogSafe.forLog(e.toString()));
        } finally {
            // In a finally, so even an Error frees the requester's place in flight.
            final Runnable next;
            synchronized (this) {
                next = chunks == null ? advance(relay) : finish(relay, chunks);
            }
            runQuietly(next);
        }
    }

    /** A reply from a seat the host asked on someone's behalf. */
    void onOwnerReply(final RemoteClient owner, final ArtReplyEvent reply) {
        final Relay relay;
        synchronized (this) {
            relay = relays.get(reply.getRequestId());
            if (relay == null || relay.owner != owner || !relay.key.equals(reply.getImageKey())) {
                return; // unasked, expired, or from the wrong seat: dropped before anything is checked
            }
            relays.remove(reply.getRequestId());
        }
        // Checked in place, and only for a reply this host asked for: a bad owner costs the requester
        // nothing but a move on to the next seat.
        final byte[][] chunks = SharedArtPolicy.isWellFormed(reply.getChunks()) ? reply.getChunks() : null;
        final Runnable next;
        synchronized (this) {
            next = chunks == null ? advance(relay) : finish(relay, chunks);
        }
        next.run();
    }

    /** A seat that left can answer nothing: requests waiting on it move on at once rather than time out. */
    void onClientGone(final RemoteClient client) {
        final List<Runnable> next = new ArrayList<>();
        synchronized (this) {
            final List<Relay> gone = new ArrayList<>();
            relays.values().removeIf(relay -> {
                if (relay.owner != client) {
                    return false;
                }
                gone.add(relay);
                return true;
            });
            for (final Relay relay : gone) {
                next.add(advance(relay));
            }
        }
        for (final Runnable r : next) {
            runQuietly(r);
        }
    }

    /** A seat that never answered: the request moves on to the next seat, so the requester's queue moves too. */
    private void expireRelay(final int relayId) {
        final Runnable next;
        synchronized (this) {
            final Relay relay = relays.remove(relayId);
            if (relay == null) {
                return;
            }
            next = advance(relay);
        }
        next.run();
    }

    private void runQuietly(final Runnable action) {
        try {
            action.run();
        } catch (final RuntimeException e) {
            netLog.debug("Shared art not delivered: {}", LogSafe.forLog(e.toString()));
        }
    }

    /**
     * Sends a reply. One to a channel past its high-water mark, or gone, is
     * dropped: the asking side's own timer settles it, and a requester that
     * never reads cannot grow the host's outbound buffer.
     */
    private void deliver(final RemoteClient to, final int id, final String key, final byte[][] chunks) {
        if (to == null) {
            final ArtReplyEvent reply = new ArtReplyEvent(id, key, chunks);
            executor.execute(() -> hostSession.onReply(reply));
        } else if (to.supportsSharedArt() && to.isWritable()) {
            to.send(new ArtReplyEvent(id, key, chunks));
        }
    }

    /** The live server behind {@link Host}. */
    private static final class ServerHost implements Host {
        private final FServerManager server;

        ServerHost(final FServerManager server) {
            this.server = server;
        }

        private LobbySlot slot(final int seat) {
            final ServerGameLobby lobby = server.getLocalLobby();
            return lobby == null || seat < 0 || seat >= lobby.getNumberOfSlots() ? null : lobby.getSlot(seat);
        }

        private Deck deck(final int seat) {
            final LobbySlot slot = slot(seat);
            return slot == null ? null : slot.getDeck();
        }

        /**
         * The deck the running game was built from for this seat, or null when
         * the seat cannot be mapped to its player. The game keeps its own copy,
         * which a client's later lobby updates do not touch.
         */
        private Deck gameDeck(final int seat) {
            final ServerGameLobby lobby = server.getLocalLobby();
            final HostedMatch match = lobby == null ? null : lobby.getHostedMatch();
            final LobbySlot slot = slot(seat);
            if (match == null || match.gameControllers == null || slot == null) {
                return null;
            }
            final IGameController controller = match.gameControllers.get(slot);
            if (!(controller instanceof PlayerController pc)) {
                return null;
            }
            final Player player = pc.getPlayer();
            final RegisteredPlayer registered = player == null ? null : player.getRegisteredPlayer();
            return registered == null ? null : registered.getDeck();
        }

        @Override
        public Object currentGame() {
            final ServerGameLobby lobby = server.getLocalLobby();
            final HostedMatch match = lobby == null ? null : lobby.getHostedMatch();
            return match == null ? null : match.getGame();
        }

        @Override
        public int seatCount() {
            final ServerGameLobby lobby = server.getLocalLobby();
            return lobby == null ? 0 : lobby.getNumberOfSlots();
        }

        @Override
        public LobbySlotType seatType(final int seat) {
            final LobbySlot slot = slot(seat);
            return slot == null ? null : slot.getType();
        }

        @Override
        public Set<String> seatArt(final int seat) {
            Deck deck = null;
            try {
                deck = gameDeck(seat);
            } catch (final RuntimeException e) {
                // the match is changing hands: the lobby deck stands in
            }
            return SharedArtSource.knownArtIds(deck != null ? deck : deck(seat));
        }

        @Override
        public RemoteClient seatClient(final int seat) {
            return server.findClientByIndex(seat);
        }

        @Override
        public boolean hostShares() {
            return FModel.getPreferences().getPrefBoolean(FPref.UI_NETPLAY_SHARE_CUSTOM_ART);
        }

        @Override
        public boolean hostShows() {
            return FModel.getPreferences().getPrefBoolean(FPref.UI_NETPLAY_SHOW_SHARED_ART);
        }

        @Override
        public List<Deck> lobbyDecks() {
            final List<Deck> decks = new ArrayList<>();
            for (int i = 0; i < seatCount(); i++) {
                final Deck deck = deck(i);
                if (deck != null) {
                    decks.add(deck);
                }
            }
            return decks;
        }

        @Override
        public byte[] serveLocal(final int seat, final String key) {
            final Deck deck = deck(seat);
            return SharedArtSource.serve(key, k -> SharedArtSource.deckHolds(deck, k));
        }
    }
}
