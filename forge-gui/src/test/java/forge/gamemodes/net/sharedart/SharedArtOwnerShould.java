package forge.gamemodes.net.sharedart;

import forge.gamemodes.net.event.ArtReplyEvent;
import forge.gamemodes.net.event.ArtRequestEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The sharing player answers the host, which may be the attacker: whatever it
 * sends, this end reads a bounded number of files at once, answers a bounded
 * number per game and per picture, sends a bounded number of bytes per game,
 * and never writes more than one reply past the connection's high-water mark.
 * Shares SharedArtOwner's package on purpose.
 */
public class SharedArtOwnerShould {

    private static final byte[] PICTURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0};

    /** Where replies go; keeps each reply's id and whether it carried a picture, not the bytes. */
    private static final class Link implements SharedArtOwner.Link {
        volatile boolean writable = true;
        final List<Integer> sent = new CopyOnWriteArrayList<>();
        final List<Boolean> carried = new CopyOnWriteArrayList<>();

        @Override
        public boolean isWritable() {
            return writable;
        }

        @Override
        public void send(final ArtReplyEvent reply) {
            sent.add(reply.getRequestId());
            carried.add(reply.getChunks() != null);
        }
    }

    private static ArtRequestEvent request(final int i) {
        return new ArtRequestEvent(i, "c:Card " + i + "|XCUS|1");
    }

    @Test(timeOut = 30_000)
    public void boundTheReadsAndRepliesOfAFloodingHost() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicInteger reading = new AtomicInteger();
            final AtomicInteger mostReading = new AtomicInteger();
            final Link link = new Link();
            final SharedArtOwner owner = new SharedArtOwner(pool, () -> true, key -> {
                mostReading.accumulateAndGet(reading.incrementAndGet(), Math::max);
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    reading.decrementAndGet();
                }
                return PICTURE;
            }, link, () -> 0);

            int taken = 0;
            for (int i = 1; i <= 1_000; i++) {
                taken += owner.offer(request(i)) ? 1 : 0;
            }
            release.countDown();
            final long deadline = System.currentTimeMillis() + 10_000;
            while (link.sent.size() < taken && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            Assert.assertTrue(mostReading.get() <= SharedArtPolicy.MAX_IN_FLIGHT,
                    mostReading.get() + " files read at once");
            Assert.assertTrue(taken <= SharedArtPolicy.MAX_OWNER_QUEUE + SharedArtPolicy.MAX_IN_FLIGHT,
                    taken + " of 1000 requests taken");
            Assert.assertEquals(link.sent.size(), taken, "every request taken is answered, nothing else is");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void capRepliesPerGameAndPerPictureAndRefuseAtOnceWithShareOff() {
        final Link link = new Link();
        final AtomicInteger game = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final SharedArtOwner owner = new SharedArtOwner(Runnable::run, () -> true,
                key -> { reads.incrementAndGet(); return PICTURE; }, link, game::get);

        for (int i = 1; i <= 1_000; i++) {
            owner.offer(request(i));
        }
        Assert.assertEquals(link.sent.size(), SharedArtPolicy.MAX_SERVES_PER_GAME);
        Assert.assertFalse(owner.offer(request(1_001)), "the game's allowance is spent");
        game.incrementAndGet();
        Assert.assertTrue(owner.offer(request(1_002)), "a new game renews it");

        // One picture once for each other player, however its key is spelled.
        for (int i = 0; i < SharedArtPolicy.MAX_SERVES_PER_KEY; i++) {
            Assert.assertTrue(owner.offer(new ArtRequestEvent(2_000 + i, i % 2 == 0 ? "c:Card 7|XCUS|1" : "c:card 7|xcus|01")));
        }
        Assert.assertFalse(owner.offer(new ArtRequestEvent(2_100, "c:CARD 7|XCUS|001")),
                "a picture is sent no more often than there are other players");
        Assert.assertTrue(owner.offer(new ArtRequestEvent(2_101, "c:Card 7|XCUS|1$alt")), "its other face is another picture");

        // SHARE off: nothing is read, and the host hears so at once instead of waiting out its timer.
        final AtomicInteger notSharedReads = new AtomicInteger();
        final SharedArtOwner notSharing = new SharedArtOwner(Runnable::run, () -> false,
                key -> { notSharedReads.incrementAndGet(); return PICTURE; }, link, game::get);
        final int sentBefore = link.sent.size();
        Assert.assertTrue(notSharing.offer(request(1_004)));
        Assert.assertEquals(link.sent.size(), sentBefore + 1, "with SHARE off a request is refused at once");
        Assert.assertEquals((int) link.sent.get(sentBefore), 1_004);
        Assert.assertFalse(link.carried.get(sentBefore));
        Assert.assertEquals(notSharedReads.get(), 0, "with SHARE off nothing is read");
    }

    @Test
    public void refusePicturesPastTheGamesByteAllowance() {
        final Link link = new Link();
        final byte[] full = new byte[SharedArtPolicy.MAX_BYTES];
        System.arraycopy(PICTURE, 0, full, 0, PICTURE.length);
        final SharedArtOwner owner = new SharedArtOwner(Runnable::run, () -> true, key -> full, link, () -> 0);
        final int fit = (int) (SharedArtPolicy.MAX_SERVED_BYTES_PER_GAME / SharedArtPolicy.MAX_BYTES);
        for (int i = 1; i <= fit + 2; i++) {
            Assert.assertTrue(owner.offer(request(i)));
        }
        Assert.assertEquals(link.sent.size(), fit + 2, "every request taken is answered");
        Assert.assertEquals(link.carried.stream().filter(c -> c).count(), fit,
                "past the game's byte allowance the answer is a refusal");
    }

    @Test(timeOut = 30_000)
    public void holdRepliesBackOnABackedUpLinkAndDropOneThatWaitsTooLong() throws Exception {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final Link link = new Link();
            link.writable = false;
            final AtomicInteger reads = new AtomicInteger();
            final SharedArtOwner owner = new SharedArtOwner(pool, () -> true,
                    key -> { reads.incrementAndGet(); return PICTURE; }, link, () -> 0);

            Assert.assertTrue(owner.offer(request(1)), "a backed-up link does not refuse a request");
            Assert.assertTrue(owner.offer(request(2)));
            awaitAtLeast(reads, 1);
            Thread.sleep(300);
            Assert.assertEquals(reads.get(), 1, "one file is read and its reply waits; the next waits unread");
            Assert.assertTrue(link.sent.isEmpty(), "nothing is written past the high-water mark");

            // Still backed up after the wait: the first reply is dropped and the reader moves on.
            awaitAtLeast(reads, 2);
            link.writable = true;
            final long deadline = System.currentTimeMillis() + 10_000;
            while (link.sent.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(100);
            Assert.assertEquals(link.sent.size(), 1, "the reply that waited too long was dropped");
            Assert.assertEquals((int) link.sent.get(0), 2, "the next reply goes out once the link drains");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void awaitAtLeast(final AtomicInteger counter, final int value) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + SharedArtOwner.WRITABLE_WAIT_MS + 10_000;
        while (counter.get() < value && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Assert.assertTrue(counter.get() >= value, "expected " + value + ", got " + counter.get());
    }
}
