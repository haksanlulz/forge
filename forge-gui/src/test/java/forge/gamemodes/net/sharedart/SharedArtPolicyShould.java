package forge.gamemodes.net.sharedart;

import forge.card.CardEdition;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * What a sharing player may read and send: only a custom set's own picture,
 * from inside that set's folder, as a JPEG or PNG within the cap, for a key
 * of the one shape an honest asker sends.
 */
public class SharedArtPolicyShould {

    private static byte[] png(final int width, final int height) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static void deleteTree(final Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (final Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    public void serveOnlyImagesInsideACustomSetFolder() throws Exception {
        final Path root = Files.createTempDirectory("sharedart");
        final Path secret = root.resolveSibling(root.getFileName() + "-secret.png");
        try {
            final File pics = root.toFile();
            final File xcus = Files.createDirectory(root.resolve("XCUS")).toFile();
            final File m19 = Files.createDirectory(root.resolve("M19")).toFile();
            final byte[] png = png(8, 11);
            final File custom = new File(xcus, "Grizzly Bears.full.png");
            final File stock = new File(m19, "Grizzly Bears.full.png");
            final File fake = new File(xcus, "Fake.full.png");
            final File big = new File(xcus, "Big.full.png");
            Files.write(custom.toPath(), png);
            Files.write(stock.toPath(), png);
            Files.write(fake.toPath(), "not an image".getBytes(StandardCharsets.UTF_8));
            Files.write(big.toPath(), Arrays.copyOf(png, SharedArtPolicy.MAX_BYTES + 1));
            Files.write(secret, png);

            final String key = "c:Grizzly Bears|XCUS|1";
            final SharedArtPolicy.Resolution ok =
                    new SharedArtPolicy.Resolution("XCUS", CardEdition.Type.CUSTOM_SET, "XCUS", custom);
            Assert.assertNotNull(SharedArtPolicy.servableBytes(key, ok, pics), "a custom set's own picture is servable");

            Assert.assertNull(SharedArtPolicy.servableBytes("c:Grizzly Bears|M19|1",
                    new SharedArtPolicy.Resolution("M19", CardEdition.Type.EXPANSION, "M19", stock), pics),
                    "a stock set's picture is never sent");
            Assert.assertNull(SharedArtPolicy.servableBytes(key,
                    new SharedArtPolicy.Resolution("XCUS", CardEdition.Type.CUSTOM_SET, "XCUS",
                            new File(xcus, "../M19/Grizzly Bears.full.png")), pics),
                    "a file resolving outside the custom set's folder is never sent");
            Assert.assertNull(SharedArtPolicy.servableBytes(key,
                    new SharedArtPolicy.Resolution("XCUS", CardEdition.Type.CUSTOM_SET, "..", secret.toFile()), pics),
                    "a folder outside the pictures tree is never read");
            Assert.assertNull(SharedArtPolicy.servableBytes(key,
                    new SharedArtPolicy.Resolution("XCUS", CardEdition.Type.CUSTOM_SET, "XCUS", fake), pics),
                    "a .png name on a non-image is not enough");
            Assert.assertNull(SharedArtPolicy.servableBytes(key,
                    new SharedArtPolicy.Resolution("XCUS", CardEdition.Type.CUSTOM_SET, "XCUS", big), pics),
                    "a picture over the cap is never sent");

            final String[] hostile = {
                    "c:../../secret|XCUS|1",
                    "c:C:\\Windows\\win.ini|XCUS|1",
                    "c:\\\\server\\share\\x|XCUS|1",
                    "c://server/share/x|XCUS|1",
                    "c:/etc/passwd|XCUS|1",
                    "c:Grizzly Bears|../XCUS|1",
                    "t:Grizzly Bears|XCUS|1",
                    // Only a name, a set and a numeric art index: nothing trails the key, nothing is missing.
                    "c:Grizzly Bears|XCUS",
                    "c:Grizzly Bears|XCUS|1|x",
                    "c:Grizzly Bears|XCUS|one",
                    "c:Grizzly Bears|XCUS|12345",
                    // Characters that rewrite a log line or its reading order.
                    "c:Grizzly\u0085Bears|XCUS|1",
                    "c:Grizzly\u2028Bears|XCUS|1",
                    "c:Grizzly\u2029Bears|XCUS|1",
                    "c:Grizzly\u202EBears|XCUS|1",
            };
            for (final String bad : hostile) {
                Assert.assertNull(SharedArtPolicy.servableBytes(bad, ok, pics), bad + " must be refused");
            }
        } finally {
            deleteTree(root);
            Files.deleteIfExists(secret);
        }
    }

    @Test
    public void refuseAPictureReachedThroughALinkOutOfTheSetFolder() throws Exception {
        final Path root = Files.createTempDirectory("sharedart");
        final Path outside = Files.createTempDirectory("sharedart-outside");
        try {
            Files.write(outside.resolve("secret.png"), png(8, 11));
            final Path xcus = Files.createDirectory(root.resolve("XCUS"));
            final Path link = xcus.resolve("elsewhere");
            if (!makeDirectoryLink(link, outside)) {
                throw new SkipException("this platform lets the test make no directory link");
            }
            final File reached = link.resolve("secret.png").toFile();
            Assert.assertTrue(reached.isFile(), "the link must reach the file");
            Assert.assertNull(SharedArtPolicy.servableBytes("c:Grizzly Bears|XCUS|1",
                    new SharedArtPolicy.Resolution("XCUS", CardEdition.Type.CUSTOM_SET, "XCUS", reached), root.toFile()),
                    "a picture that really lies outside the set's folder is never sent, however it is reached");
        } finally {
            deleteTree(root);
            deleteTree(outside);
        }
    }

    /** A symbolic link where the platform allows one, else on Windows a junction, which needs no privilege. */
    private static boolean makeDirectoryLink(final Path link, final Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (final IOException | UnsupportedOperationException | SecurityException e) {
            // try a junction
        }
        if (File.separatorChar != '\\') {
            return false;
        }
        try {
            final Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.isDirectory(link);
        } catch (final IOException e) {
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
