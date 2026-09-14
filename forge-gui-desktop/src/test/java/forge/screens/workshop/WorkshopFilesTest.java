package forge.screens.workshop;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.SystemUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.card.CardEdition;
import forge.card.CardRarity;

/**
 * The file contract of {@link WorkshopFiles} over temp directories, pinned where it meets the rest
 * of Forge: the picture cache's lookup order and folder preload, the edition reader, and the
 * exception type the UI handles.
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
     * The key is built from a card name, and toMWSFilename keeps a backslash, a path separator on Windows:
     * a name carrying {@code \..\} would resolve outside the picture folder. Forward slashes stand in for it
     * here, since they separate on every platform. Refused before anything is written or listed.
     */
    @Test
    public void installArtRefusesAKeyOutsideThePictureFolder() throws IOException {
        final File pics = tmp.resolve("pics").toFile();
        final File png = writeImage(tmp, "e.png", "png");
        Assert.assertThrows(IOException.class, () -> WorkshopFiles.installArt(png, pics, "USER/../../escaped.full"));
        Assert.assertThrows(IOException.class, () -> WorkshopFiles.filesInstallWouldReplace(png, pics, "USER/../../escaped.full"));
        Assert.assertFalse(tmp.resolve("escaped.full.png").toFile().exists(), "nothing may be written outside the picture folder");
    }

    /**
     * ImageUtil.toMWSFilename leaves {@code < >} in an image key, and on Windows java.io.File accepts
     * them right up to {@code toPath()}, which throws the unchecked InvalidPathException. The caller
     * handles IOException only, so that is what must come out.
     */
    @Test(expectedExceptions = IOException.class)
    public void installArtRefusesAKeyTheFileSystemCannotName() throws IOException {
        //'<' is refused only by toPath() on Windows, so only there does this reach installArt's InvalidPathException
        //translation; NUL is refused on every platform, but earlier, by getCanonicalFile's own IOException. The non-Windows
        //leg pins the contract (an IOException, never an unchecked exception), not the translation.
        final String key = SystemUtils.IS_OS_WINDOWS ? "USER/Foo<Bar.full" : "USER/Foo\u0000Bar.full";
        final File png = writeImage(tmp, "d.png", "png");
        WorkshopFiles.installArt(png, tmp.resolve("pics").toFile(), key);
    }

    /**
     * Add Art Variant on a card the Workshop Art edition does not know yet writes the edition file from
     * nothing, and what it writes is what the edition reader loads at the next start: the code, the name,
     * the pre-Alpha date, the custom type the folder forces, and one entry of the card numbered 1.
     */
    @Test
    public void appendArtVariantWritesTheEditionFileTheReaderLoads() throws IOException {
        final File editions = Files.createDirectories(tmp.resolve("editions")).toFile();
        final File file = new File(editions, WorkshopFiles.ART_EDITION_FILE);
        Assert.assertFalse(file.exists());

        Assert.assertEquals(WorkshopFiles.appendArtVariant(file, "Grizzly Bears", CardRarity.Common), new WorkshopFiles.ArtVariant(1, 1));

        final CardEdition edition = new CardEdition.Reader(editions, true).readFile(file);
        Assert.assertEquals(edition.getCode(), WorkshopFiles.ART_EDITION_CODE);
        Assert.assertEquals(edition.getName(), WorkshopFiles.ART_EDITION_NAME);
        Assert.assertEquals(edition.getType(), CardEdition.Type.CUSTOM_SET);
        Assert.assertEquals(new SimpleDateFormat("yyyy-MM-dd").format(edition.getDate()), WorkshopFiles.ART_EDITION_DATE);
        final List<CardEdition.EditionEntry> bears = edition.getCardInSet("Grizzly Bears");
        Assert.assertEquals(bears.size(), 1, bears.toString());
        Assert.assertEquals(bears.get(0).collectorNumber(), "1");
        Assert.assertEquals(bears.get(0).rarity(), CardRarity.Common);
        Assert.assertFalse(new File(file.getPath() + ".part").exists(), "no .part left behind");
    }

    /**
     * A later Add appends to the existing file with the next collector number and an art index one past
     * the entries of that name (CardDb numbers a name's duplicate entries in file order at load), and the
     * entry lands in [cards] even when a hand-added section follows it: an appended line joins the LAST
     * section of the file, and the reader would have filed the printing as a token. A rarity the reader's
     * pattern has no letter for is written as Special; written as is, it would be read as part of the name.
     */
    @Test
    public void appendArtVariantNumbersTheNextEntryAndKeepsItInTheCardsSection() throws IOException {
        final File editions = Files.createDirectories(tmp.resolve("editions")).toFile();
        final File file = new File(editions, WorkshopFiles.ART_EDITION_FILE);
        Files.write(file.toPath(), List.of(
                "[metadata]", "Code=WSART", "Name=Workshop Art", "Date=1993-01-01", "Type=Custom", "",
                "[cards]", "1 C Grizzly Bears", "2 R Llanowar Elves", "",
                "[tokens]", "1 Bear"), StandardCharsets.UTF_8);

        Assert.assertEquals(WorkshopFiles.appendArtVariant(file, "Grizzly Bears", CardRarity.Common), new WorkshopFiles.ArtVariant(3, 2));
        Assert.assertEquals(WorkshopFiles.appendArtVariant(file, "Llanowar Elves", CardRarity.Unknown), new WorkshopFiles.ArtVariant(4, 2));

        final CardEdition edition = new CardEdition.Reader(editions, true).readFile(file);
        Assert.assertEquals(edition.getAllCardsInSet().size(), 4, "both entries are card entries: " + edition.getAllCardsInSet());
        final List<CardEdition.EditionEntry> bears = edition.getCardInSet("Grizzly Bears");
        Assert.assertEquals(bears.size(), 2, bears.toString());
        Assert.assertEquals(bears.get(1).collectorNumber(), "3");
        Assert.assertEquals(bears.get(1).rarity(), CardRarity.Common);
        final List<CardEdition.EditionEntry> elves = edition.getCardInSet("Llanowar Elves");
        Assert.assertEquals(elves.size(), 2, elves.toString());
        Assert.assertEquals(elves.get(1).collectorNumber(), "4");
        Assert.assertEquals(elves.get(1).rarity(), CardRarity.Special, "Unknown has no letter in the reader's pattern");
    }

    /** A picture already under the new key is not the moved printing's to overwrite, and nothing moves when one is found. */
    @Test
    public void renameArtRefusesAnOccupiedKeyBeforeItMovesAnything() throws IOException {
        final Path set = Files.createDirectories(tmp.resolve("pics/WSART"));
        final File oldJpg = writeImage(set, "Bear.full.jpg", "jpg");
        final File oldPng = writeImage(set, "Bear.full.png", "png");
        final File taken = set.resolve("Bear1.full.png").toFile();
        Files.writeString(taken.toPath(), "someone else's");

        Assert.assertThrows(IOException.class, () -> WorkshopFiles.renameArt(tmp.resolve("pics").toFile(), "WSART/Bear.full", "WSART/Bear1.full"));
        Assert.assertTrue(oldJpg.isFile(), "the jpg must not have moved: its target was free, the png's was not");
        Assert.assertTrue(oldPng.isFile());
        Assert.assertFalse(set.resolve("Bear1.full.jpg").toFile().exists());
        Assert.assertEquals(Files.readString(taken.toPath()), "someone else's");

        Assert.assertTrue(taken.delete());
        final List<File> moved = WorkshopFiles.renameArt(tmp.resolve("pics").toFile(), "WSART/Bear.full", "WSART/Bear1.full");
        Assert.assertEquals(moved.size(), 2);
        Assert.assertFalse(oldJpg.exists());
        Assert.assertTrue(set.resolve("Bear1.full.jpg").toFile().isFile());
        Assert.assertTrue(set.resolve("Bear1.full.png").toFile().isFile());
    }

    @Test
    public void restorePutsBackWhatSnapshotFound() throws IOException {
        final File edition = tmp.resolve("Workshop Art.txt").toFile();
        Assert.assertNull(WorkshopFiles.snapshot(edition), "no file yet");
        Files.writeString(edition.toPath(), "first");
        final byte[] before = WorkshopFiles.snapshot(edition);

        Files.writeString(edition.toPath(), "first\nsecond");
        WorkshopFiles.restore(edition, before);
        Assert.assertEquals(Files.readString(edition.toPath()), "first");
        Assert.assertFalse(new File(edition.getPath() + ".part").exists());

        WorkshopFiles.restore(edition, null);
        Assert.assertFalse(edition.exists(), "null is 'there was no file'");
    }

    /**
     * A file that is not an image is refused before anything is written. Add Art Variant checks the
     * picture first, or the edition entry and the database printing would exist for no art; Set Art's
     * install refuses the same file the same way, and writes nothing.
     */
    @Test
    public void requireImageRefusesAFileThatIsNotAnImage() throws IOException {
        final File notAnImage = tmp.resolve("text.png").toFile();
        Files.write(notAnImage.toPath(), List.of("not a picture"), StandardCharsets.UTF_8);
        final File pics = tmp.resolve("pics").toFile();

        Assert.assertThrows(IOException.class, () -> WorkshopFiles.requireImage(notAnImage));
        Assert.assertThrows(IOException.class, () -> WorkshopFiles.requireImage(tmp.resolve("missing.png").toFile()));
        Assert.assertThrows(IOException.class, () -> WorkshopFiles.installArt(notAnImage, pics, "USER/My Card.full"));
        Assert.assertFalse(pics.exists(), "a refused picture writes nothing");
    }

    /**
     * CardEdition.Reader takes " @" as the artist separator and "$" as the parameter marker, and silently
     * skips a line it cannot parse. A custom card named that way would get a printing that exists this
     * session and is gone at the next start, its picture orphaned. Refuse before writing anything.
     */
    @Test
    public void appendArtVariantRefusesANameTheReaderWouldMisread() throws IOException {
        final File editions = Files.createDirectories(tmp.resolve("editions")).toFile();
        final File file = new File(editions, WorkshopFiles.ART_EDITION_FILE);
        Assert.assertThrows(IOException.class, () -> WorkshopFiles.appendArtVariant(file, "Bear @ Large", CardRarity.Common));
        Assert.assertThrows(IOException.class, () -> WorkshopFiles.appendArtVariant(file, "Cash $$$", CardRarity.Common));
        Assert.assertFalse(file.exists(), "a refused name writes nothing");
    }

    // ---------------------------------------------------------------------------------------
    // Export Set

    private Path customDir;
    private Path picsRoot;

    /** custom/cards/t/test_card.txt, custom/editions/TST.txt, pics USER/ + TST/ each with one image. */
    private Map<String, File> populatedRoots() throws IOException {
        customDir = tmp.resolve("custom");
        picsRoot = tmp.resolve("pics");
        Files.createDirectories(customDir.resolve("cards/t"));
        Files.createDirectories(customDir.resolve("editions"));
        Files.writeString(customDir.resolve("cards/t/test_card.txt"), "Name:Test Card\nManaCost:R\nTypes:Creature Goblin\nPT:1/1\nOracle:\n");
        Files.writeString(customDir.resolve("editions/TST.txt"), "[metadata]\nCode=TST\nName=Test Set\n[cards]\n1 C Test Card\n");
        writeImage(Files.createDirectories(picsRoot.resolve("USER")), "My Card.full.jpg", "jpg");
        writeImage(Files.createDirectories(picsRoot.resolve("TST")), "Test Card.full.png", "png");
        writeImage(Files.createDirectories(picsRoot.resolve("LEA")), "Grizzly Bears.full.jpg", "jpg"); // stock set: must NOT be packed
        Files.writeString(hiddenDir(customDir.resolve("cards"), ".git").resolve("HEAD"), "ref: refs/heads/main\n"); // ZipUtil prunes a hidden folder
        return WorkshopFiles.exportRoots(customDir.toFile(), picsRoot.toFile(), List.of("USER", "TST"));
    }

    /** A dot-folder, marked hidden on Windows too, so File.isHidden() is true for it on every platform. */
    private static Path hiddenDir(final Path parent, final String name) throws IOException {
        final Path dir = Files.createDirectories(parent.resolve(name));
        try {
            Files.setAttribute(dir, "dos:hidden", true);
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // not a DOS file system: the leading dot already hides it
        }
        return dir;
    }

    private static Set<String> entryNames(final File zip) throws IOException {
        final Set<String> names = new TreeSet<>();
        try (ZipFile zf = new ZipFile(zip)) {
            final Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                names.add(en.nextElement().getName());
            }
        }
        return names;
    }

    /**
     * The pack layout other players install from: prefixed forward-slash entries, the README, no stock-set
     * art. Left out as well: the exporter's own Workshop Art edition (the file and its pictures; installed
     * elsewhere it would replace that player's), and a dot-folder, which the card reader never loads and
     * which is not hidden by attribute on Windows. A custom script over a stock card is packed and named
     * in the README by the name the reader files it under, whatever its file is called.
     */
    @Test
    public void exportPackWritesPrefixedEntriesAndAReadme() throws IOException {
        populatedRoots();
        Files.writeString(customDir.resolve("editions/" + WorkshopFiles.ART_EDITION_FILE), "[metadata]\nCode=WSART\n[cards]\n1 C Grizzly Bears\n");
        writeImage(Files.createDirectories(picsRoot.resolve(WorkshopFiles.ART_EDITION_CODE)), "Grizzly Bears.full.jpg", "jpg");
        Files.writeString(customDir.resolve("editions/Renamed Copy.txt"), "[metadata]\nCode = wsart\nName=Renamed Copy\n[cards]\n1 C Grizzly Bears\n");
        Files.writeString(Files.createDirectories(customDir.resolve("cards/.old")).resolve("stale.txt"), "Name:Stale\n");
        Files.writeString(Files.createDirectories(customDir.resolve("cards/g")).resolve("grizzly_bears.txt"), "Name:Grizzly Bears\nManaCost:1 R\n");
        Files.writeString(Files.createDirectories(customDir.resolve("cards/m")).resolve("my_bears.txt"), "Name:Grizzly Bears\nManaCost:G\n");
        Files.writeString(Files.createDirectories(customDir.resolve("cards/f")).resolve("fire.txt"),
                "Name:Fire\nManaCost:1 R\nTypes:Instant\nAlternateMode:Split\nALTERNATE\nName:Ice\nManaCost:1 U\nTypes:Instant\n");
        final Map<String, File> roots = WorkshopFiles.exportRoots(customDir.toFile(), picsRoot.toFile(), List.of("USER", "TST", WorkshopFiles.ART_EDITION_CODE));
        final File zip = tmp.resolve("out/set.zip").toFile();
        Files.createDirectories(zip.getParentFile().toPath());

        final WorkshopFiles.ExportReport report = WorkshopFiles.exportPack(zip, roots, name -> name.equals("Grizzly Bears") || name.equals("Fire // Ice"));

        Assert.assertTrue(zip.isFile(), "zip not written");
        Assert.assertFalse(new File(zip.getPath() + ".part").exists(), ".part must be moved into place");
        final Set<String> names = entryNames(zip);
        Assert.assertTrue(names.contains("custom/cards/t/test_card.txt"), names.toString());
        Assert.assertTrue(names.contains("custom/cards/g/grizzly_bears.txt"), names.toString());
        Assert.assertTrue(names.contains("custom/editions/TST.txt"), names.toString());
        Assert.assertTrue(names.contains("Cache/pics/cards/USER/My Card.full.jpg"), names.toString());
        Assert.assertTrue(names.contains("Cache/pics/cards/TST/Test Card.full.png"), names.toString());
        Assert.assertTrue(names.contains(WorkshopFiles.README_NAME), names.toString());
        Assert.assertTrue(names.stream().noneMatch(n -> n.contains("LEA")), "stock-set art must not be packed: " + names);
        Assert.assertTrue(names.stream().noneMatch(n -> n.contains(WorkshopFiles.ART_EDITION_NAME) || n.contains(WorkshopFiles.ART_EDITION_CODE)),
                "the Workshop Art edition must not be packed: " + names);
        Assert.assertTrue(names.stream().noneMatch(n -> n.contains("Renamed Copy")), "the Workshop Art edition is known by its Code= line too: " + names);
        Assert.assertTrue(names.stream().noneMatch(n -> n.contains("\\")), "entry names must use forward slashes: " + names);
        Assert.assertTrue(names.stream().noneMatch(n -> n.contains(".git") || n.contains(".old")), "a hidden or dot-named folder is not packed: " + names);
        Assert.assertEquals(report.filesPerRoot.get("custom/cards").intValue(), 4, "what the archive leaves out is not counted");
        Assert.assertEquals(report.filesPerRoot.get("custom/editions").intValue(), 1);
        Assert.assertEquals(report.filesPerRoot.get("custom/tokens").intValue(), 0);
        Assert.assertEquals(report.totalFiles(), 7);

        try (ZipFile zf = new ZipFile(zip)) {
            final String readme = new String(zf.getInputStream(zf.getEntry(WorkshopFiles.README_NAME)).readAllBytes(), StandardCharsets.UTF_8);
            Assert.assertTrue(readme.contains("%APPDATA%\\Forge\\"), readme);
            Assert.assertTrue(readme.contains("~/.forge/"), readme);
            Assert.assertTrue(readme.contains("Grizzly Bears (custom/cards/g/grizzly_bears.txt)"), "the stock override must be named: " + readme);
            Assert.assertTrue(readme.contains("Grizzly Bears (custom/cards/m/my_bears.txt)"), "an override is known by its Name:, not its file: " + readme);
            Assert.assertTrue(readme.contains("Fire // Ice (custom/cards/f/fire.txt)"), "a split card is filed under both halves: " + readme);
            Assert.assertFalse(readme.contains("Test Card ("), "a custom card is not an override: " + readme);
        }
    }

    /** A picture folder comes from an edition's Code2, which a pack from someone else can set to anything. */
    @Test
    public void exportRootsTakesOnlyAPlainFolderName() {
        final Map<String, File> roots = WorkshopFiles.exportRoots(tmp.resolve("custom").toFile(), tmp.resolve("pics").toFile(),
                List.of("TST", ".", "..", "a/b", "a\\b", "", WorkshopFiles.ART_EDITION_CODE.toLowerCase()));
        Assert.assertEquals(roots.keySet(), Set.of(WorkshopFiles.EXPORT_CARDS, WorkshopFiles.EXPORT_EDITIONS, WorkshopFiles.EXPORT_TOKENS,
                WorkshopFiles.EXPORT_PICS + "/TST"));
    }

    @Test
    public void exportPackReplacesAnExistingZip() throws IOException {
        final Map<String, File> roots = populatedRoots();
        final File zip = tmp.resolve("set.zip").toFile();
        Files.writeString(zip.toPath(), "stale");
        WorkshopFiles.exportPack(zip, roots, stem -> false);
        Assert.assertTrue(entryNames(zip).contains(WorkshopFiles.README_NAME));
    }

    /** What the archive leaves out (a hidden folder's files, the Workshop Art edition) is not "something to export". */
    @Test
    public void exportSeesNothingUnderAHiddenFolder() throws IOException {
        customDir = tmp.resolve("custom");
        picsRoot = tmp.resolve("pics");
        Files.writeString(hiddenDir(Files.createDirectories(customDir.resolve("cards")), ".git").resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(Files.createDirectories(customDir.resolve("editions")).resolve(WorkshopFiles.ART_EDITION_FILE), "[metadata]\nCode=WSART\n");
        final Map<String, File> roots = WorkshopFiles.exportRoots(customDir.toFile(), picsRoot.toFile(), List.of());
        Assert.assertFalse(WorkshopFiles.hasAnythingToExport(roots), "roots holding only what the archive leaves out have nothing to export");
        Files.writeString(customDir.resolve("cards/visible.txt"), "Name:Visible\n");
        Assert.assertTrue(WorkshopFiles.hasAnythingToExport(roots));
    }

    /**
     * A symbolic link or a Windows junction under a packed folder is not followed: it would put files from
     * outside the custom folders into a pack meant for other players (and a link back up would loop).
     */
    @Test
    public void exportNeverFollowsALinkOutOfARoot() throws IOException {
        customDir = tmp.resolve("custom");
        picsRoot = tmp.resolve("pics");
        final Path cards = Files.createDirectories(customDir.resolve("cards"));
        final Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "not a card");
        if (!makeDirectoryLink(cards.resolve("link"), outside)) {
            throw new SkipException("this system lets the test make neither a symbolic link nor a junction");
        }
        final Map<String, File> roots = WorkshopFiles.exportRoots(customDir.toFile(), picsRoot.toFile(), List.of());
        Assert.assertFalse(WorkshopFiles.hasAnythingToExport(roots), "a file reached only through a link is not the pack's");
        Files.writeString(cards.resolve("real.txt"), "Name:Real\n");
        final File zip = tmp.resolve("set.zip").toFile();
        WorkshopFiles.exportPack(zip, roots, stem -> false);
        final Set<String> names = entryNames(zip);
        Assert.assertTrue(names.contains("custom/cards/real.txt"), names.toString());
        Assert.assertTrue(names.stream().noneMatch(n -> n.contains("secret")), "a linked folder must not be packed: " + names);
    }

    /** A symbolic link where the platform allows one without privileges, else (Windows) a junction, which needs none. */
    private static boolean makeDirectoryLink(final Path link, final Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            if (!SystemUtils.IS_OS_WINDOWS) {
                return false;
            }
        }
        try {
            final Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
            mklink.getInputStream().readAllBytes();
            return mklink.waitFor() == 0 && Files.isDirectory(link);
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A failure mid-write must throw AND leave neither a truncated zip nor a .part behind. */
    @Test
    public void exportPackFailureLeavesNoPartFile() throws IOException {
        final Map<String, File> roots = populatedRoots();
        // dest sits inside a path that is a FILE, so neither the .part nor the move can succeed
        final File blocker = tmp.resolve("blocker").toFile();
        Files.writeString(blocker.toPath(), "I am a file, not a directory");
        final File dest = new File(blocker, "set.zip");
        try {
            WorkshopFiles.exportPack(dest, roots, stem -> false);
            Assert.fail("expected the export to fail");
        } catch (IOException expected) {
            // fine
        }
        Assert.assertFalse(dest.exists());
        Assert.assertFalse(new File(dest.getPath() + ".part").exists());
    }

    /**
     * Same, with the failure arriving mid-stream after entries have already been written: two roots
     * that emit the same entry name make ZipOutputStream throw "duplicate entry" part-way through.
     */
    @Test
    public void exportPackFailureMidStreamLeavesNoPartFile() throws IOException {
        final Map<String, File> roots = populatedRoots();
        final File dest = tmp.resolve("set.zip").toFile();
        final Map<String, File> dup = new LinkedHashMap<>(roots);
        dup.put("custom/cards/t", customDir.resolve("cards/t").toFile()); // custom/cards/t/test_card.txt a second time
        boolean failed = false;
        try {
            WorkshopFiles.exportPack(dest, dup, stem -> false);
        } catch (IOException expected) {
            failed = true;
        }
        Assert.assertTrue(failed, "expected a duplicate-entry ZipException mid-stream");
        Assert.assertFalse(dest.exists(), "a failed export must not leave a zip at dest");
        Assert.assertFalse(new File(dest.getPath() + ".part").exists(), "a failed export must not leave a .part");
    }

    /**
     * A destination inside one of the packed folders is refused up front, with nothing written. The walk
     * skips its own archive and .part as well; this makes the case a clear refusal rather than a pack
     * that sits among the files it carries.
     */
    @Test
    public void exportPackRefusesADestinationInsideAPackedRoot() throws IOException {
        final Map<String, File> roots = populatedRoots();
        final File inside = picsRoot.resolve("USER/forge-custom-set.zip").toFile();
        Assert.assertEquals(WorkshopFiles.rootContaining(inside, roots), picsRoot.resolve("USER").toFile());
        Assert.assertNull(WorkshopFiles.rootContaining(tmp.resolve("elsewhere.zip").toFile(), roots));
        try {
            WorkshopFiles.exportPack(inside, roots, stem -> false);
            Assert.fail("expected the export to refuse a destination inside a packed root");
        } catch (IOException expected) {
            // fine
        }
        Assert.assertFalse(inside.exists());
        Assert.assertFalse(new File(inside.getPath() + ".part").exists());
    }
}
