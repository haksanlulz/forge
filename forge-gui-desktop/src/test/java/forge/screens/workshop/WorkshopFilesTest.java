package forge.screens.workshop;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.SystemUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The file contract of {@link WorkshopFiles} over temp directories, pinned where it meets the rest
 * of Forge: the picture cache's lookup order and folder preload, and the exception type the UI
 * handles.
 */
public class WorkshopFilesTest {

    private Path tmp;

    @BeforeMethod
    public void makeTempDir() throws IOException {
        tmp = Files.createTempDirectory("workshop-files-test");
    }

    @AfterMethod
    public void removeTempDir() throws IOException {
        if (tmp == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tmp)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    static File writeImage(final Path dir, final String name, final String format) throws IOException {
        final File f = dir.resolve(name).toFile();
        final BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Assert.assertTrue(ImageIO.write(img, format, f), "test image not written");
        return f;
    }

    /** ImageKeys.findFile probes .jpg before .png, so a stale .jpg left beside a new .png keeps serving the old picture. */
    @Test
    public void installArtReplacesTheOtherExtension() throws IOException {
        final Path pics = tmp.resolve("pics");
        final File jpg = writeImage(tmp, "a.jpg", "jpg");
        final File png = writeImage(tmp, "b.png", "png");

        final File first = WorkshopFiles.installArt(jpg, pics.toFile(), "USER/My Card.full");
        Assert.assertEquals(first, new File(pics.toFile(), "USER/My Card.full.jpg"));
        Assert.assertTrue(first.isFile());

        final File second = WorkshopFiles.installArt(png, pics.toFile(), "USER/My Card.full");
        Assert.assertEquals(second, new File(pics.toFile(), "USER/My Card.full.png"));
        Assert.assertTrue(second.isFile());
        Assert.assertFalse(first.exists(), "the stale .jpg must be gone, ImageKeys probes .jpg before .png");
    }

    /**
     * ImageKeys.hasImage's folder preload files "Name.fullborder.jpg" under the same key as "Name.full.jpg",
     * last listed wins, so a fullborder twin left in place keeps serving the OLD picture after the install.
     */
    @Test
    public void installArtRemovesAFullborderTwin() throws IOException {
        final Path pics = tmp.resolve("pics");
        final File fullborder = writeImage(Files.createDirectories(pics.resolve("USER")), "My Card.fullborder.jpg", "jpg");
        final File other = writeImage(Files.createDirectories(pics.resolve("USER")), "Other Card.fullborder.jpg", "jpg");
        final File png = writeImage(tmp, "b.png", "png");

        final File dest = WorkshopFiles.installArt(png, pics.toFile(), "USER/My Card.full");
        Assert.assertEquals(dest, new File(pics.toFile(), "USER/My Card.full.png"));
        Assert.assertTrue(dest.isFile());
        Assert.assertFalse(fullborder.exists(), "the fullborder twin must be gone, the folder preload files it under the .full key");
        Assert.assertTrue(other.isFile(), "another card's fullborder picture is not this card's twin");
    }

    /**
     * The user may re-point a card at its own cached picture (the fullborder twin is exactly the file
     * this happens with). The stale-sibling sweep must never delete the SOURCE: an earlier version swept
     * before copying, deleted it, and then failed the copy - the user's only copy of the art was gone.
     */
    @Test
    public void installArtNeverDeletesTheSourceWhenItIsTheCardsOwnTwin() throws IOException {
        final Path pics = tmp.resolve("pics");
        final File twin = writeImage(Files.createDirectories(pics.resolve("USER")), "My Card.fullborder.jpg", "jpg");
        final long before = twin.length();

        final File dest = WorkshopFiles.installArt(twin, pics.toFile(), "USER/My Card.full");
        Assert.assertEquals(dest, new File(pics.toFile(), "USER/My Card.full.jpg"));
        Assert.assertTrue(dest.isFile(), "the new .full picture must exist");
        Assert.assertEquals(dest.length(), before, "the .full picture is a copy of the source");
        Assert.assertTrue(twin.isFile(), "the source must survive the stale-sibling sweep");
        Assert.assertFalse(new File(pics.toFile(), "USER/My Card.full.jpg.part").exists(), "no .part left behind");
    }

    /**
     * The prompt names the fullborder twin as a file the install removes; it must go even when the picked
     * file already IS the destination (nothing to copy), or the folder preload keeps serving it.
     */
    @Test
    public void installArtSweepsTheTwinWhenTheSourceIsTheDestination() throws IOException {
        final Path pics = tmp.resolve("pics");
        final File full = writeImage(Files.createDirectories(pics.resolve("USER")), "My Card.full.jpg", "jpg");
        final File twin = writeImage(pics.resolve("USER"), "My Card.fullborder.jpg", "jpg");
        final long before = full.length();

        Assert.assertEquals(WorkshopFiles.installArt(full, pics.toFile(), "USER/My Card.full"), full);
        Assert.assertTrue(full.isFile(), "the source, which is the destination, must survive");
        Assert.assertEquals(full.length(), before);
        Assert.assertFalse(twin.exists(), "the fullborder twin the prompt named must go even when nothing is copied");
        Assert.assertFalse(new File(pics.toFile(), "USER/My Card.full.jpg.part").exists(), "no .part left behind");
    }

    /**
     * Set Art prompts before it touches an existing picture, listing what the install would replace or
     * remove: the .full and .fullborder twins in either extension, another card's files never, and the
     * source itself never (re-pointing a card at its own twin must not announce that twin as a casualty).
     */
    @Test
    public void filesInstallWouldReplaceListsTheTwinsAndNeverTheSource() throws IOException {
        final Path pics = tmp.resolve("pics");
        final File full = writeImage(Files.createDirectories(pics.resolve("USER")), "My Card.full.jpg", "jpg");
        final File fullborder = writeImage(pics.resolve("USER"), "My Card.fullborder.png", "png");
        writeImage(pics.resolve("USER"), "Other Card.full.jpg", "jpg");
        final File src = writeImage(tmp, "new.png", "png");

        final List<File> replaced = WorkshopFiles.filesInstallWouldReplace(src, pics.toFile(), "USER/My Card.full");
        Assert.assertEqualsNoOrder(replaced.toArray(), new Object[] { full, fullborder }, replaced.toString());
        Assert.assertEquals(WorkshopFiles.filesInstallWouldReplace(fullborder, pics.toFile(), "USER/My Card.full"), List.of(full),
                "the source is never listed as a casualty");
        Assert.assertTrue(WorkshopFiles.filesInstallWouldReplace(src, pics.toFile(), "USER/Fresh Card.full").isEmpty());
    }

    /**
     * ImageUtil.toMWSFilename leaves {@code < >} in an image key, and on Windows java.io.File accepts
     * them right up to {@code toPath()}, which throws the unchecked InvalidPathException. The caller
     * handles IOException only, so that is what must come out.
     */
    @Test(expectedExceptions = IOException.class)
    public void installArtRefusesAKeyTheFileSystemCannotName() throws IOException {
        if (!SystemUtils.IS_OS_WINDOWS) {
            throw new SkipException("'<' is a legal file name character outside Windows");
        }
        final File png = writeImage(tmp, "d.png", "png");
        WorkshopFiles.installArt(png, tmp.resolve("pics").toFile(), "USER/Foo<Bar.full");
    }
}
