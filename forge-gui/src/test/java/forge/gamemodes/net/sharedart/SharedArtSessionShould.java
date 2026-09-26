package forge.gamemodes.net.sharedart;

import forge.gamemodes.net.event.ArtReplyEvent;
import forge.gamemodes.net.event.ArtRequestEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The asking side keeps only what the cap and the format allow, stops asking
 * at the game's budgets, asks nothing with SHOW off or before the peer
 * answers, loses only its own key to one missed reply, and gives up on the
 * whole queue once the host misses two in a row.
 */
public class SharedArtSessionShould {

    private static byte[] png() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 11, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    public void refuseOverCapAndNonImageRepliesBeforeKeepingThem() throws Exception {
        final byte[] png = png();
        final List<ArtRequestEvent> sent = new ArrayList<>();
        final SharedArtSession session = new SharedArtSession(sent::add, () -> true);
        session.setPeerCapable(true);

        Assert.assertTrue(session.request("c:A|XCUS|1"));
        session.onReply(new ArtReplyEvent(sent.get(0).getRequestId(), "c:A|XCUS|1",
                SharedArtPolicy.split(Arrays.copyOf(png, SharedArtPolicy.MAX_BYTES + 1))));
        Assert.assertNull(session.receivedBytes("c:A|XCUS|1"), "an over-cap picture must be refused");

        Assert.assertTrue(session.request("c:B|XCUS|1"));
        session.onReply(new ArtReplyEvent(sent.get(1).getRequestId(), "c:B|XCUS|1",
                SharedArtPolicy.split("not an image".getBytes(StandardCharsets.UTF_8))));
        Assert.assertNull(session.receivedBytes("c:B|XCUS|1"), "a non-image must be refused");

        Assert.assertTrue(session.request("c:C|XCUS|1"));
        session.onReply(new ArtReplyEvent(sent.get(2).getRequestId(), "c:C|XCUS|1", SharedArtPolicy.split(png)));
        Assert.assertEquals(session.receivedBytes("c:C|XCUS|1"), png, "a valid picture is kept");
    }

    @Test
    public void stopAskingPastTheGamesBudgets() throws Exception {
        final List<ArtRequestEvent> sent = new ArrayList<>();
        final SharedArtSession session = new SharedArtSession(sent::add, () -> true);
        session.setPeerCapable(true);
        for (int i = 0; i < SharedArtPolicy.MAX_REQUESTS_PER_GAME; i++) {
            Assert.assertTrue(session.request("c:K" + i + "|XCUS|1"));
            final ArtRequestEvent r = sent.get(sent.size() - 1);
            session.onReply(new ArtReplyEvent(r.getRequestId(), r.getImageKey(), null)); // refused: not given back
        }
        Assert.assertFalse(session.request("c:Over|XCUS|1"), "past the request budget nothing is asked");
        Assert.assertEquals(sent.size(), SharedArtPolicy.MAX_REQUESTS_PER_GAME);

        session.endGame();
        final byte[] full = Arrays.copyOf(png(), SharedArtPolicy.MAX_BYTES);
        int granted = 0;
        while (session.request("c:B" + granted + "|XCUS|1")) {
            final ArtRequestEvent r = sent.get(sent.size() - 1);
            session.onReply(new ArtReplyEvent(r.getRequestId(), r.getImageKey(), SharedArtPolicy.split(full)));
            session.discard(r.getImageKey());
            granted++;
        }
        Assert.assertEquals(granted, (int) (SharedArtPolicy.MAX_BYTES_PER_GAME / SharedArtPolicy.MAX_BYTES),
                "the byte budget stops asking one full picture short of the cap");
    }

    @Test
    public void askNothingWithShowOffOrBeforeThePeerAnswers() {
        final List<ArtRequestEvent> sent = new ArrayList<>();

        final SharedArtSession showOff = new SharedArtSession(sent::add, () -> false);
        showOff.setPeerCapable(true);
        Assert.assertFalse(showOff.request("c:A|XCUS|1"));
        Assert.assertTrue(sent.isEmpty(), "with SHOW off nothing is ever asked for");

        final SharedArtSession unnegotiated = new SharedArtSession(sent::add, () -> true);
        Assert.assertFalse(unnegotiated.request("c:A|XCUS|1"));
        Assert.assertTrue(sent.isEmpty(), "nothing is asked of a peer that never announced the capability");

        final SharedArtSession on = new SharedArtSession(sent::add, () -> true);
        on.setPeerCapable(true);
        Assert.assertTrue(on.request("c:A|XCUS|1"));
        Assert.assertEquals(sent.size(), 1);
        Assert.assertFalse(on.request("c:A|XCUS|1"));
        Assert.assertEquals(sent.size(), 1, "a key is asked for once per game");
    }

    @Test
    public void countNoMissedReplyFromTheGameBefore() {
        final List<ArtRequestEvent> sent = new ArrayList<>();
        final List<Runnable> timers = new ArrayList<>();
        final List<String> settled = new ArrayList<>();
        final SharedArtSession session = new SharedArtSession(sent::add, () -> true, (delayMs, task) -> timers.add(task));
        session.setPeerCapable(true);
        SharedArtSession.setListener((s, key, arrived) -> {
            if (s == session && !arrived) {
                settled.add(key);
            }
        });
        try {
            Assert.assertTrue(session.request("c:A|XCUS|1"));
            timers.remove(0).run(); // the last request of one game goes unanswered
            Assert.assertEquals(settled, List.of("c:A|XCUS|1"));

            session.endGame();
            Assert.assertTrue(session.request("c:X|XCUS|1"));
            Assert.assertTrue(session.request("c:Y|XCUS|1"));
            Assert.assertEquals(sent.size(), 2, "X is in flight, Y queued");
            timers.remove(0).run(); // and so does the first of the next
            Assert.assertEquals(settled, List.of("c:A|XCUS|1", "c:X|XCUS|1"), "the miss in the game before is not the first of two in a row");
            Assert.assertEquals(sent.size(), 3, "so Y is still asked for");
        } finally {
            SharedArtSession.setListener(null);
        }
    }

    @Test
    public void loseOneKeyToAMissedReplyAndTheWholeQueueToTwoInARow() throws Exception {
        final List<ArtRequestEvent> sent = new ArrayList<>();
        final List<Runnable> timers = new ArrayList<>();
        final List<String> settled = new ArrayList<>();
        final SharedArtSession session = new SharedArtSession(sent::add, () -> true, (delayMs, task) -> timers.add(task));
        session.setPeerCapable(true);
        SharedArtSession.setListener((s, key, arrived) -> {
            if (s == session && !arrived) {
                settled.add(key);
            }
        });
        try {
            for (final String key : List.of("c:A|XCUS|1", "c:B|XCUS|1", "c:C|XCUS|1", "c:D|XCUS|1", "c:E|XCUS|1")) {
                Assert.assertTrue(session.request(key));
            }
            Assert.assertEquals(sent.size(), 1, "one request in flight, the rest queued");

            timers.remove(0).run(); // A: no answer, not even a refusal, within the timeout
            Assert.assertEquals(settled, List.of("c:A|XCUS|1"), "one missed reply costs its own key only");
            Assert.assertEquals(sent.size(), 2, "and the next key is asked for");
            Assert.assertEquals(sent.get(1).getImageKey(), "c:B|XCUS|1");

            session.onReply(new ArtReplyEvent(sent.get(1).getRequestId(), "c:B|XCUS|1", SharedArtPolicy.split(png())));
            Assert.assertEquals(sent.size(), 3, "B arrived, so C is asked for");
            timers.remove(0).run(); // B's timer, already answered
            timers.remove(0).run(); // C: no answer, but B's reply came in between
            Assert.assertEquals(settled, List.of("c:A|XCUS|1", "c:C|XCUS|1"), "a reply in between restarts the count");
            Assert.assertEquals(sent.size(), 4, "so D is still asked for");

            timers.remove(0).run(); // D: a second miss in a row
            Assert.assertEquals(settled, List.of("c:A|XCUS|1", "c:C|XCUS|1", "c:D|XCUS|1", "c:E|XCUS|1"),
                    "a host that missed two in a row settles every queued key at once");
            Assert.assertEquals(sent.size(), 4, "nothing more is sent to a host that stopped answering");
            Assert.assertFalse(session.isPending("c:E|XCUS|1"));
        } finally {
            SharedArtSession.setListener(null);
        }
    }
}
