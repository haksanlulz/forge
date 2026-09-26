package forge;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.gamemodes.net.event.ArtRequestEvent;
import forge.gamemodes.net.sharedart.SharedArtPolicy;
import forge.gamemodes.net.sharedart.SharedArtSession;
import forge.item.PaperCard;
import forge.net.TestUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The desktop's side of shared custom art takes another player's bytes, so
 * what it decodes, how it draws the result, and when it asks at all are
 * pinned here.
 */
public class SharedArtSafetyTest {

    @BeforeClass
    public void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    private static byte[] png(final int width, final int height, final int type) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, type), "png", out);
        return out.toByteArray();
    }

    @Test(timeOut = 30_000)
    public void testDecodeRefusesPixelBombHeaders() throws Exception {
        Assert.assertNotNull(SharedArtImages.decode(png(80, 110, BufferedImage.TYPE_INT_RGB), Long.MAX_VALUE),
                "a small picture decodes");

        // A flat 5000x5000 picture compresses to almost nothing, so it passes the
        // byte cap and reaches the header check, which must refuse it before any
        // pixel buffer is allocated.
        final byte[] bomb = png(5_000, 5_000, BufferedImage.TYPE_BYTE_GRAY);
        Assert.assertTrue(bomb.length <= SharedArtPolicy.MAX_BYTES, "the bomb must get past the byte cap");
        Assert.assertNull(SharedArtImages.decode(bomb, Long.MAX_VALUE), "dimensions over the cap must be refused from the header");

        // Many flat pictures each pass every per-picture check; the game's pixel budget stops the sum.
        final byte[] card = png(700, 1_000, BufferedImage.TYPE_BYTE_GRAY);
        Assert.assertNull(SharedArtImages.decode(card, 700L * 1_000 - 1), "a picture past the remaining budget is not decoded");

        // 16-bit RGBA (64 bits a pixel) decodes to a compact image, so the budget's pixel count bounds memory.
        final ColorModel deepModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),
                new int[]{16, 16, 16, 16}, true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT);
        final ByteArrayOutputStream deepPng = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(deepModel, deepModel.createCompatibleWritableRaster(64, 64), false, null), "png", deepPng);
        final BufferedImage deep = SharedArtImages.decode(deepPng.toByteArray(), Long.MAX_VALUE);
        Assert.assertNotNull(deep);
        Assert.assertTrue(deep.getColorModel().getPixelSize() <= 32, "decoded pictures are at most 32 bits a pixel");
    }

    @Test(timeOut = 30_000)
    public void testDecodeRefusesPicturesFarFromACardsShape() throws Exception {
        // Drawn into a card box, each would come out under the three pixels a side the resampler needs.
        for (final int[] size : new int[][]{{1_040, 2}, {2, 1_040}, {4_000, 4}, {4_000, 64}, {63, 63}}) {
            Assert.assertNull(SharedArtImages.decode(png(size[0], size[1], BufferedImage.TYPE_BYTE_GRAY), Long.MAX_VALUE),
                    size[0] + "x" + size[1] + " must be refused from the header");
        }
        Assert.assertNotNull(SharedArtImages.decode(png(64, 128, BufferedImage.TYPE_BYTE_GRAY), Long.MAX_VALUE));
        Assert.assertNotNull(SharedArtImages.decode(png(1_040, 745, BufferedImage.TYPE_BYTE_GRAY), Long.MAX_VALUE));
    }

    @Test
    public void testScalingNeverFailsOnAPicturesShape() {
        // An accepted picture at its widest, drawn into the smallest box, is kept to three pixels a side.
        final BufferedImage small = ImageCache.resize(new BufferedImage(128, 64, BufferedImage.TYPE_INT_RGB), 3.0 / 128);
        Assert.assertEquals(small.getWidth(), 3);
        Assert.assertEquals(small.getHeight(), 3);
        // A local picture thinner than the resampler reads is still drawn.
        final BufferedImage thin = ImageCache.resize(new BufferedImage(1_040, 2, BufferedImage.TYPE_INT_RGB), 0.05);
        Assert.assertEquals(thin.getHeight(), 3);
        Assert.assertTrue(thin.getWidth() >= 50, "the long side scales as asked: " + thin.getWidth());
    }

    @Test(timeOut = 30_000)
    public void testDecodeRefusesCostlyJpegStructure() throws Exception {
        Assert.assertTrue(SharedArtPolicy.isTameJpeg(jpeg(64, 64, false)), "a baseline JPEG is fine");
        Assert.assertTrue(SharedArtPolicy.isTameJpeg(jpeg(64, 64, true)), "a small progressive JPEG is fine");
        // Every scan walks the whole coefficient buffer, which subsampling does not shrink.
        Assert.assertNull(SharedArtImages.decode(jpeg(2_400, 2_400, true), Long.MAX_VALUE),
                "a progressive JPEG over its pixel cap is refused before decoding");
        Assert.assertFalse(SharedArtPolicy.isTameJpeg(scans(SharedArtPolicy.MAX_JPEG_SCANS + 1)),
                "a JPEG with more scans than any encoder writes is refused");
        Assert.assertTrue(SharedArtPolicy.isTameJpeg(scans(10)));
        // A sequential file with a scan per component makes the decoder hold every coefficient too.
        Assert.assertFalse(SharedArtPolicy.isTameJpeg(baselineScans(2_400, 3)), "a large non-interleaved baseline JPEG is refused");
        Assert.assertTrue(SharedArtPolicy.isTameJpeg(baselineScans(64, 3)));
        Assert.assertTrue(SharedArtPolicy.isTameJpeg(frameOf(1)));
        Assert.assertTrue(SharedArtPolicy.isTameJpeg(frameOf(3)));
        Assert.assertFalse(SharedArtPolicy.isTameJpeg(frameOf(4)), "a CMYK or YCCK JPEG is refused: it would be drawn in the wrong colours");
        Assert.assertFalse(SharedArtPolicy.isTameJpeg(frameOf(2)), "a two-component JPEG is one the reader cannot read");
    }

    @Test(timeOut = 30_000)
    public void testJpegMetadataNeverReachesTheReader() throws Exception {
        // An ICC profile, an EXIF block and a comment, as another player could send them.
        final byte[] plain = jpeg(96, 128, false);
        final ByteArrayOutputStream withMetadata = new ByteArrayOutputStream();
        withMetadata.write(plain, 0, 2);
        withMetadata.writeBytes(segment(0xE2, "ICC_PROFILE\0junk"));
        withMetadata.writeBytes(segment(0xE1, "Exif\0\0junk"));
        withMetadata.writeBytes(segment(0xFE, "a comment"));
        withMetadata.write(plain, 2, plain.length - 2);
        final byte[] received = withMetadata.toByteArray();

        final byte[] stripped = SharedArtPolicy.stripJpeg(received);
        Assert.assertNotNull(stripped);
        final String text = new String(stripped, StandardCharsets.ISO_8859_1);
        for (final String gone : new String[]{"ICC_PROFILE", "Exif", "a comment", "JFIF"}) {
            Assert.assertFalse(text.contains(gone), gone + " must not reach the reader");
        }
        final BufferedImage decoded = SharedArtImages.decode(received, Long.MAX_VALUE);
        Assert.assertNotNull(decoded, "the picture itself still decodes");
        Assert.assertEquals(decoded.getWidth(), 96);
        Assert.assertEquals(decoded.getHeight(), 128);
    }

    @Test(timeOut = 30_000)
    public void testAnAdobeSegmentNeverReachesTheReader() throws Exception {
        final BufferedImage red = new BufferedImage(96, 128, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = red.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 96, 128);
        g.dispose();
        final ByteArrayOutputStream plainOut = new ByteArrayOutputStream();
        ImageIO.write(red, "jpeg", plainOut);
        final byte[] plain = plainOut.toByteArray();
        // Drop the JFIF APP0 and declare the samples RGB with an Adobe APP14 (transform 0): read as such, red turns blue.
        int at = 2;
        if ((plain[2] & 0xFF) == 0xFF && (plain[3] & 0xFF) == 0xE0) {
            at = 4 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
        }
        final ByteArrayOutputStream hostile = new ByteArrayOutputStream();
        hostile.write(plain, 0, 2);
        hostile.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xEE, 0, 14, 'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, 0});
        hostile.write(plain, at, plain.length - at);
        final BufferedImage decoded = SharedArtImages.decode(hostile.toByteArray(), Long.MAX_VALUE);
        Assert.assertNotNull(decoded);
        final Color c = new Color(decoded.getRGB(48, 64));
        Assert.assertTrue(c.getRed() > 200 && c.getBlue() < 80, "the Adobe segment must not reach the reader: " + c);
    }

    @Test(timeOut = 30_000)
    public void testTheLobbysPicturesAreAskedForAtMatchStartAndNeverWhenACardIsDrawn() throws Exception {
        final List<ArtRequestEvent> sent = new CopyOnWriteArrayList<>();
        final SharedArtSession session = new SharedArtSession(sent::add, () -> true);
        session.setPeerCapable(true);
        final Deck deck = new Deck("lobby");
        // A name no stock edition prints, in a custom set this machine lacks.
        deck.getMain().add(new PaperCard(CardRules.fromScript(List.of(
                "Name:Shared Art Probe Lobby Card", "ManaCost:G", "Types:Creature Bear", "PT:2/2")), "XCUSA", CardRarity.Common));
        session.setLobbyDecks(() -> List.of(deck));
        SharedArtSession.setActive(session);
        try {
            SharedArtImages.beginGame();
            final long deadline = System.currentTimeMillis() + 10_000;
            while (sent.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            Assert.assertEquals(sent.size(), 1, "the lobby's custom pictures are asked for when the match view opens");
            Assert.assertEquals(sent.get(0).getImageKey(), "c:Shared Art Probe Lobby Card|XCUSA|1");

            // A card met in play, from a custom set this machine lacks, is not asked for on its own:
            // when it was asked for would say when someone drew or looked at it.
            Assert.assertNull(SharedArtImages.lookup("c:Llanowar Elves|XCUSA|1"));
            Thread.sleep(200);
            Assert.assertFalse(session.hasSeen("c:Llanowar Elves|XCUSA|1"), "nothing is asked for when a card is drawn");
            Assert.assertEquals(sent.size(), 1);
        } finally {
            SharedArtImages.endGame();
            SharedArtSession.clearActive(session);
        }
    }

    private static byte[] segment(final int marker, final String payload) {
        final byte[] body = payload.getBytes(StandardCharsets.ISO_8859_1);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        out.write((body.length + 2) >> 8);
        out.write(body.length + 2);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] jpeg(final int width, final int height, final boolean progressive) throws IOException {
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            if (progressive) {
                param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            writer.write(null, new IIOImage(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY), null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** A minimal progressive JPEG skeleton with this many scans: markers only, as a hostile peer could send. */
    private static byte[] scans(final int count) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC2, 0, 11, 8, 0, 16, 0, 16, 1, 1, 0x11, 0});
        for (int i = 0; i < count; i++) {
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 63, 0, 0x55, 0x55});
        }
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    /** A minimal baseline JPEG skeleton of this many components and one scan. */
    private static byte[] frameOf(final int components) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC0, 0, (byte) (8 + 3 * components), 8, 0, 16, 0, 16, (byte) components});
        for (int c = 1; c <= components; c++) {
            out.writeBytes(new byte[]{(byte) c, 0x11, 0});
        }
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 63, 0, 0x55, 0x55});
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    /** A minimal three-component baseline JPEG skeleton with one scan per component in turn. */
    private static byte[] baselineScans(final int side, final int count) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC0, 0, 17, 8, (byte) (side >> 8), (byte) side, (byte) (side >> 8), (byte) side, 3,
                1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0});
        for (int i = 0; i < count; i++) {
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 8, 1, (byte) (i % 3 + 1), 0, 0, 63, 0, 0x55, 0x55});
        }
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }
}
