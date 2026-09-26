package forge.gamemodes.net.server;

import forge.deck.Deck;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.event.ArtReplyEvent;
import forge.gamemodes.net.event.ArtRequestEvent;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.event.NetCapabilities;
import forge.gamemodes.net.sharedart.SharedArtPolicy;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The host's router answers untrusted peers: who owns a picture, how much one
 * requester may ask per game, and what happens when an owner refuses, stays
 * silent or leaves. Runs the relay against hand-built seats, with no sockets
 * and no live game. Shares the package of SharedArtRelay, which is
 * package-private, on purpose.
 */
public class SharedArtRelayShould {

    private static final byte[] PICTURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};

    private static String key(final int i) {
        return "c:Card " + i + "|XCUS|1";
    }

    /** A seat's connection, and what the host sent down it, decoded as the peer would. */
    private static final class Peer {
        final EmbeddedChannel channel = new EmbeddedChannel(new CompatibleObjectEncoder(null));
        final RemoteClient client = new RemoteClient(channel);

        Peer(final int seat, final boolean capable, final boolean serves) {
            client.setIndex(seat);
            client.setSharedArt(capable);
            client.setServesSharedArt(serves);
        }

        List<Object> received() {
            final List<Object> out = new ArrayList<>();
            for (ByteBuf frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
                final EmbeddedChannel decoder = new EmbeddedChannel(
                        new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)));
                decoder.writeInbound(frame);
                out.add(decoder.readInbound());
                decoder.finishAndReleaseAll();
            }
            return out;
        }

        ArtReplyEvent onlyReply() {
            final List<Object> got = received();
            Assert.assertEquals(got.size(), 1, "expected one reply, got " + got);
            return (ArtReplyEvent) got.get(0);
        }

        ArtRequestEvent onlyRequest() {
            final List<Object> got = received();
            Assert.assertEquals(got.size(), 1, "expected one request, got " + got);
            return (ArtRequestEvent) got.get(0);
        }
    }

    /** The lobby: seat types, who brought what, and the host's switches. */
    private static final class Table implements SharedArtRelay.Host {
        Object game = new Object();
        final LobbySlotType[] types;
        final Peer[] peers;
        final List<Set<String>> holdings = new ArrayList<>();
        boolean shares = true;
        final Set<String> localRefuses = new HashSet<>();
        final List<Integer> servedSeats = new ArrayList<>();
        /** Tasks the relay handed its executor and timer, run when the test says. */
        final List<Runnable> tasks = new ArrayList<>();
        final List<Runnable> timers = new ArrayList<>();

        Table(final LobbySlotType... types) {
            this.types = types;
            this.peers = new Peer[types.length];
            for (int i = 0; i < types.length; i++) {
                holdings.add(new HashSet<>());
            }
        }

        Peer remote(final int seat, final boolean capable, final boolean serves) {
            peers[seat] = new Peer(seat, capable, serves);
            return peers[seat];
        }

        void holds(final int seat, final String... keys) {
            for (final String key : keys) {
                holdings.get(seat).add(SharedArtPolicy.artIdOf(key));
            }
        }

        void runTasks() {
            while (!tasks.isEmpty()) {
                tasks.remove(0).run();
            }
        }

        SharedArtRelay relay() {
            return new SharedArtRelay(this, tasks::add, (delayMs, task) -> timers.add(task));
        }

        @Override public Object currentGame() { return game; }
        @Override public int seatCount() { return types.length; }
        @Override public LobbySlotType seatType(final int seat) { return types[seat]; }
        @Override public Set<String> seatArt(final int seat) { return new HashSet<>(holdings.get(seat)); }
        @Override public RemoteClient seatClient(final int seat) { return peers[seat] == null ? null : peers[seat].client; }
        @Override public boolean hostShares() { return shares; }
        @Override public boolean hostShows() { return true; }
        @Override public List<Deck> lobbyDecks() { return List.of(); }

        @Override
        public byte[] serveLocal(final int seat, final String key) {
            servedSeats.add(seat);
            return localRefuses.contains(key) ? null : PICTURE;
        }
    }

    private static void ask(final SharedArtRelay relay, final Peer from, final int id, final String key) {
        relay.onRequest(from.client, new ArtRequestEvent(id, key));
    }

    @Test
    public void budgetEachRequesterPerGame() {
        final Table table = new Table(LobbySlotType.LOCAL, LobbySlotType.REMOTE);
        final Peer requester = table.remote(1, true, false);
        for (int i = 0; i <= SharedArtPolicy.MAX_REQUESTS_PER_GAME + 1; i++) {
            table.holds(0, key(i));
        }
        final SharedArtRelay relay = table.relay();

        // Outside a match: refused with the id negated, so the asker may ask again in one.
        table.game = null;
        ask(relay, requester, 1, key(0));
        Assert.assertEquals(requester.onlyReply().getRequestId(), -1);
        // An unservable key is never answered, so an oversized one is never echoed.
        ask(relay, requester, 2, "c:" + "x".repeat(70_000) + "|XCUS|1");
        Assert.assertTrue(requester.received().isEmpty(), "an unservable key is never echoed");
        for (int i = 0; i <= SharedArtPolicy.MAX_REQUESTS_PER_GAME; i++) {
            ask(relay, requester, 10 + i, key(0));
        }
        Assert.assertEquals(requester.received().size(), SharedArtPolicy.MAX_REQUESTS_PER_GAME - 1,
                "outside a match the refusals are capped too");
        table.game = new Object();

        // One in flight: a second request meanwhile is refused, then the first is answered.
        ask(relay, requester, 1, key(0));
        ask(relay, requester, 2, key(1));
        final ArtReplyEvent throttled = requester.onlyReply();
        Assert.assertEquals(throttled.getRequestId(), 2);
        Assert.assertNull(throttled.getChunks());
        table.runTasks();
        final ArtReplyEvent served = requester.onlyReply();
        Assert.assertEquals(served.getRequestId(), 1);
        Assert.assertEquals(SharedArtPolicy.join(served.getChunks()), PICTURE);

        // The same picture again this game, however the key is spelled: refused.
        ask(relay, requester, 3, "c:card 0|xcus|01");
        Assert.assertNull(requester.onlyReply().getChunks());

        // Spend the rest of the game's requests; past them there is no answer at all.
        for (int i = 4; i <= SharedArtPolicy.MAX_REQUESTS_PER_GAME; i++) {
            ask(relay, requester, i, key(i));
            table.runTasks();
        }
        Assert.assertEquals(requester.received().size(), SharedArtPolicy.MAX_REQUESTS_PER_GAME - 3);
        ask(relay, requester, 999, key(SharedArtPolicy.MAX_REQUESTS_PER_GAME + 1));
        table.runTasks();
        Assert.assertTrue(requester.received().isEmpty(), "past the budget a request gets no answer");

        // A new game, a new budget.
        table.game = new Object();
        ask(relay, requester, 1_000, key(0));
        table.runTasks();
        Assert.assertNotNull(requester.onlyReply().getChunks());
    }

    @Test
    public void askTheSharingSeatsThatHoldTheCardInSeatOrder() {
        final Table table = new Table(LobbySlotType.LOCAL, LobbySlotType.REMOTE, LobbySlotType.REMOTE,
                LobbySlotType.REMOTE, LobbySlotType.REMOTE);
        final Peer requester = table.remote(1, true, false);
        final Peer unannounced = table.remote(2, false, false);
        final Peer showsOnly = table.remote(3, true, false);
        final Peer sharer = table.remote(4, true, true);
        for (int seat = 0; seat < 5; seat++) {
            table.holds(seat, key(1), key(2), key(3), key(5));
        }
        table.holds(1, key(4));
        table.localRefuses.add(key(5));
        final SharedArtRelay relay = table.relay();

        // The host does not share, and seats 2 and 3 cannot or will not: seat 4 is asked.
        table.shares = false;
        ask(relay, requester, 1, key(1));
        Assert.assertEquals(sharer.onlyRequest().getImageKey(), key(1));
        Assert.assertTrue(unannounced.received().isEmpty());
        Assert.assertTrue(showsOnly.received().isEmpty());
        Assert.assertTrue(table.servedSeats.isEmpty());
        relay.onOwnerReply(sharer.client, new ArtReplyEvent(1, key(1), SharedArtPolicy.split(PICTURE)));
        Assert.assertEquals(SharedArtPolicy.join(requester.onlyReply().getChunks()), PICTURE);

        // The host shares: its own seat comes first.
        table.shares = true;
        ask(relay, requester, 2, key(2));
        table.runTasks();
        Assert.assertEquals(table.servedSeats, List.of(0));
        Assert.assertNotNull(requester.onlyReply().getChunks());
        Assert.assertTrue(sharer.received().isEmpty());

        // The host's own seat has no picture for this one: the next seat that shares is asked.
        ask(relay, requester, 3, key(5));
        table.runTasks();
        Assert.assertEquals(sharer.onlyRequest().getImageKey(), key(5));
        Assert.assertTrue(requester.received().isEmpty(), "a seat with nothing to send is not the answer");

        // The host's own request never goes to a seat on its own machine.
        relay.hostSession().request(key(3));
        table.runTasks();
        Assert.assertEquals(sharer.onlyRequest().getImageKey(), key(3));

        // A card only the requester holds has no owner.
        final Table alone = new Table(LobbySlotType.OPEN, LobbySlotType.REMOTE);
        final Peer only = alone.remote(1, true, true);
        alone.holds(1, key(4));
        alone.relay().onRequest(only.client, new ArtRequestEvent(3, key(4)));
        Assert.assertNull(only.onlyReply().getChunks());
    }

    @Test
    public void moveOnWhenASeatRefusesStaysSilentOrLeaves() {
        final Table table = new Table(LobbySlotType.OPEN, LobbySlotType.REMOTE, LobbySlotType.REMOTE,
                LobbySlotType.REMOTE, LobbySlotType.REMOTE);
        final Peer requester = table.remote(1, true, false);
        final Peer first = table.remote(2, true, true);
        final Peer second = table.remote(3, true, true);
        final Peer third = table.remote(4, true, true);
        for (int seat = 2; seat <= 4; seat++) {
            table.holds(seat, key(1), key(2), key(3), key(4));
        }
        final SharedArtRelay relay = table.relay();

        // The first seat refuses: the next is asked, and its picture reaches the requester.
        ask(relay, requester, 1, key(1));
        final ArtRequestEvent refused = first.onlyRequest();
        relay.onOwnerReply(first.client, new ArtReplyEvent(refused.getRequestId(), key(1), null));
        Assert.assertTrue(requester.received().isEmpty(), "a refusal moves on rather than answering");
        final ArtRequestEvent retried = second.onlyRequest();
        relay.onOwnerReply(second.client, new ArtReplyEvent(retried.getRequestId(), key(1), SharedArtPolicy.split(PICTURE)));
        Assert.assertEquals(SharedArtPolicy.join(requester.onlyReply().getChunks()), PICTURE);
        table.timers.clear(); // both seats answered: their timers find nothing

        // A reply that is not a picture moves on too.
        ask(relay, requester, 2, key(2));
        final ArtRequestEvent junk = first.onlyRequest();
        relay.onOwnerReply(first.client, new ArtReplyEvent(junk.getRequestId(), key(2), new byte[][]{"not an image".getBytes()}));
        final ArtRequestEvent silent = second.onlyRequest();
        Assert.assertEquals(silent.getImageKey(), key(2));
        // A seat that was not asked cannot answer for the one that was.
        relay.onOwnerReply(third.client, new ArtReplyEvent(silent.getRequestId(), key(2), SharedArtPolicy.split(PICTURE)));
        Assert.assertTrue(requester.received().isEmpty(), "a reply from a seat that was not asked is ignored");
        // The second stays silent: its timer moves on, and past two remote seats the request is refused.
        Assert.assertEquals(table.timers.size(), 2);
        table.timers.remove(0).run(); // the first seat's timer, long since answered: nothing happens
        Assert.assertTrue(requester.received().isEmpty());
        table.timers.remove(0).run();
        final ArtReplyEvent expired = requester.onlyReply();
        Assert.assertEquals(expired.getRequestId(), 2);
        Assert.assertNull(expired.getChunks());
        Assert.assertTrue(third.received().isEmpty(), "no request waits on more than two remote seats");

        // A seat that leaves is passed over at once, not after its timeout; a late answer from it is ignored.
        ask(relay, requester, 3, key(3));
        final ArtRequestEvent orphaned = first.onlyRequest();
        relay.onClientGone(first.client);
        final ArtRequestEvent takenOver = second.onlyRequest();
        Assert.assertEquals(takenOver.getImageKey(), key(3));
        relay.onOwnerReply(first.client, new ArtReplyEvent(orphaned.getRequestId(), key(3), SharedArtPolicy.split(PICTURE)));
        Assert.assertTrue(requester.received().isEmpty());
        relay.onOwnerReply(second.client, new ArtReplyEvent(takenOver.getRequestId(), key(3), SharedArtPolicy.split(PICTURE)));
        Assert.assertNotNull(requester.onlyReply().getChunks());
    }

    @Test
    public void routeByWhatEachSeatBroughtWhenTheGameStarted() {
        final Table table = new Table(LobbySlotType.OPEN, LobbySlotType.REMOTE, LobbySlotType.REMOTE, LobbySlotType.REMOTE);
        final Peer requester = table.remote(1, true, false);
        final Peer owner = table.remote(2, true, true);
        final Peer latecomer = table.remote(3, true, true);
        table.holds(2, key(1), key(2));
        final SharedArtRelay relay = table.relay();

        ask(relay, requester, 1, key(1));
        relay.onOwnerReply(owner.client, new ArtReplyEvent(owner.onlyRequest().getRequestId(), key(1), SharedArtPolicy.split(PICTURE)));
        requester.onlyReply();

        // Mid-game, a seat's lobby deck claims another player's card: what the game started with stands.
        table.holds(3, key(2), key(3));
        ask(relay, requester, 2, key(2));
        final ArtRequestEvent second = owner.onlyRequest();
        Assert.assertEquals(second.getImageKey(), key(2));
        relay.onOwnerReply(owner.client, new ArtReplyEvent(second.getRequestId(), key(2), null));
        Assert.assertNull(requester.onlyReply().getChunks());
        Assert.assertTrue(latecomer.received().isEmpty(), "a card claimed after the game started is not routed there");
        ask(relay, requester, 3, key(3));
        Assert.assertNull(requester.onlyReply().getChunks());
        Assert.assertTrue(latecomer.received().isEmpty());

        // The next game counts what it started with.
        table.game = new Object();
        ask(relay, requester, 4, key(3));
        Assert.assertEquals(latecomer.onlyRequest().getImageKey(), key(3));
    }

    @Test
    public void neverSendArtToAPeerThatDidNotAnnounce() {
        final Table table = new Table(LobbySlotType.LOCAL, LobbySlotType.REMOTE, LobbySlotType.REMOTE);
        final Peer capable = table.remote(1, true, false);
        final Peer older = table.remote(2, false, false);
        table.holds(0, key(1));
        final SharedArtRelay relay = table.relay();

        // Both ask for a card nobody holds, which is refused: only the peer that announced hears so.
        ask(relay, capable, 1, key(2));
        Assert.assertNull(capable.onlyReply().getChunks());
        ask(relay, older, 1, key(2));
        Assert.assertTrue(older.received().isEmpty(), "a refusal never reaches a peer that did not announce");
        // Nor does a picture.
        ask(relay, older, 2, key(1));
        table.runTasks();
        Assert.assertTrue(older.received().isEmpty(), "a picture never reaches a peer that did not announce");
    }

    @Test
    public void answerAndStripOnlyALoginThatAnnounced() {
        final Peer announcing = new Peer(1, false, false);
        final LoginEvent login = new LoginEvent("Alice", 1, 2, "2.0.0-TEST", false);
        login.setCapabilities(NetCapabilities.forLogin(true));
        SharedArtRelay.acceptCapabilities(announcing.client, login);
        Assert.assertNull(login.getCapabilities(), "the handshake is taken off the login before it is passed on");
        Assert.assertTrue(announcing.client.supportsSharedArt());
        Assert.assertTrue(announcing.client.servesSharedArt());
        final List<Object> answer = announcing.received();
        Assert.assertEquals(answer.size(), 1);
        Assert.assertTrue(((NetCapabilities) answer.get(0)).has(NetCapabilities.SHARED_ART));

        final Peer older = new Peer(2, false, false);
        SharedArtRelay.acceptCapabilities(older.client, new LoginEvent("Bob", 1, 2, "2.0.0-TEST", false));
        Assert.assertFalse(older.client.supportsSharedArt());
        Assert.assertTrue(older.received().isEmpty(), "a login without the trailer is never answered with one");
    }
}
