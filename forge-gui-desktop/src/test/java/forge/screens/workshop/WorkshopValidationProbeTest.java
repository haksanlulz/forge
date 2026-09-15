package forge.screens.workshop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.lang3.exception.ExceptionUtils;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.GuiDesktop;
import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.gamesimulationtests.util.CardDatabaseHelper;
import forge.gui.GuiBase;
import forge.gui.card.CardScriptInfo;
import forge.gui.card.CardScriptProbe;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.BuildInfo;
import forge.util.Lang;
import forge.util.Localizer;

/**
 * The Workshop refuses a save when {@link CardScriptProbe#probeCard} throws. That probe builds a real
 * {@code Card} with id 0 inside a throwaway playerless {@code Game}; nothing else in the tree does
 * that, so these tests pin down that (a) a valid script passes, (b) the script mistakes that crash a
 * game at turn 0 are refused with their real messages, and (c) the probe does not false-positive on
 * the stock card pool, which would make a valid card unsaveable. They also pin the script lookup to
 * the card reader's file layout and the runtime edits CardDb.Editor makes for a save.
 */
public class WorkshopValidationProbeTest {

    private StaticData db;
    /**
     * The editor tests add cards and cannot take them out of the shared database again, so they get
     * their own: an empty CardDb over the shared editions (nothing in them needs the stock pool).
     */
    private CardDb editorDb;

    @BeforeClass
    public void loadCards() {
        GuiBase.setInterface(new GuiDesktop()); // ForgeConstants resolves the assets dir through it
        Lang.createInstance("en-US"); // the card reader needs both of these
        Localizer.getInstance().initialize("en-US", ForgeConstants.LANG_DIR);
        ImageKeys.initializeDirs( // as FModel does; PaperCard.getCardImageKey needs the set-folder map
            ForgeConstants.CACHE_CARD_PICS_DIR, ForgeConstants.CACHE_CARD_PICS_SUBDIR,
            ForgeConstants.CACHE_TOKEN_PICS_DIR, ForgeConstants.CACHE_ICON_PICS_DIR,
            ForgeConstants.CACHE_BOOSTER_PICS_DIR, ForgeConstants.CACHE_FATPACK_PICS_DIR,
            ForgeConstants.CACHE_BOOSTERBOX_PICS_DIR, ForgeConstants.CACHE_PRECON_PICS_DIR,
            ForgeConstants.CACHE_TOURNAMENTPACK_PICS_DIR);
        FModel.loadDynamicGamedata(); // creature / spell subtypes
        db = CardDatabaseHelper.getStaticDataToPopulateOtherMocks();
        editorDb = new CardDb(new HashMap<>(), db.getEditions(), new HashSet<>());
        editorDb.setCardArtPreference("Latest Art All Editions"); // as CardDatabaseHelper builds the shared one
    }

    private static final String GRIZZLY_BEARS = "Name:Grizzly Bears\nManaCost:1 G\nTypes:Creature Bear\nPT:2/2\nOracle:\n";

    @Test
    public void validScriptPasses() {
        CardRules rules = CardScriptProbe.parseRules(GRIZZLY_BEARS, "grizzly_bears");
        CardScriptProbe.probeCard(rules, "LEA", CardRarity.Common);
    }

    @Test
    public void unknownApiIsRefused() {
        CardRules rules = CardScriptProbe.parseRules(GRIZZLY_BEARS + "A:AB$ Bogus | Cost$ 1 | SpellDescription$ nope\n", "grizzly_bears");
        try {
            CardScriptProbe.probeCard(rules, "LEA", CardRarity.Common);
            Assert.fail("expected the probe to refuse an unknown ApiType");
        } catch (RuntimeException ex) {
            Assert.assertTrue(ExceptionUtils.getRootCauseMessage(ex).contains("Bogus not found in ApiType enum"), ExceptionUtils.getRootCauseMessage(ex));
        }
    }

    /** The card reader trims every line; an indented bogus ability must still be refused. */
    @Test
    public void indentedUnknownApiIsRefused() {
        CardRules rules = CardScriptProbe.parseRules(GRIZZLY_BEARS + "  A:AB$ Bogus | Cost$ 1 | SpellDescription$ nope\n", "grizzly_bears");
        try {
            CardScriptProbe.probeCard(rules, "LEA", CardRarity.Common);
            Assert.fail("expected the probe to refuse an indented unknown ApiType (the reader trims, so the game would run it)");
        } catch (RuntimeException ex) {
            Assert.assertTrue(ExceptionUtils.getRootCauseMessage(ex).contains("Bogus not found in ApiType enum"), ExceptionUtils.getRootCauseMessage(ex));
        }
    }

    @Test
    public void missingTriggerSVarIsRefused() {
        // A trigger resolves its Execute SVar lazily, the first time it fires - that is the turn-0 crash.
        CardRules rules = CardScriptProbe.parseRules(GRIZZLY_BEARS
                + "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Card.Self | Execute$ TrigMissing | TriggerDescription$ nope\n",
                "grizzly_bears");
        try {
            CardScriptProbe.probeCard(rules, "LEA", CardRarity.Common);
            Assert.fail("expected the probe to refuse a trigger whose Execute SVar is missing");
        } catch (RuntimeException ex) {
            Assert.assertTrue(ExceptionUtils.getRootCauseMessage(ex).contains("has no SVar: TrigMissing"), ExceptionUtils.getRootCauseMessage(ex));
        }
    }

    @Test
    public void missingReplacementSVarIsRefused() {
        CardRules rules = CardScriptProbe.parseRules(GRIZZLY_BEARS
                + "R:Event$ Moved | ValidCard$ Card.Self | Destination$ Battlefield | ReplaceWith$ DBMissing | Description$ nope\n",
                "grizzly_bears");
        try {
            CardScriptProbe.probeCard(rules, "LEA", CardRarity.Common);
            Assert.fail("expected the probe to refuse a replacement whose ReplaceWith SVar is missing");
        } catch (RuntimeException ex) {
            Assert.assertTrue(ExceptionUtils.getRootCauseMessage(ex).contains("has no SVar: DBMissing"), ExceptionUtils.getRootCauseMessage(ex));
        }
    }

    @Test
    public void facelessRulesAreDetectedBeforeGetName() {
        Assert.assertTrue(CardScriptProbe.hasAllFaces(CardScriptProbe.parseRules(GRIZZLY_BEARS, "grizzly_bears")));
        // a split script with no second face: getName() would NPE on otherPart
        CardRules halfSplit = CardScriptProbe.parseRules("Name:Half\nManaCost:R\nTypes:Instant\nOracle:\nAlternateMode:Split\n", "half");
        Assert.assertFalse(CardScriptProbe.hasAllFaces(halfSplit));
        Assert.assertTrue(CardScriptProbe.usesCopyFaceFrom("CopyFaceFrom:Bind\nAlternateMode:Split\n\nALTERNATE\n\n  CopyFaceFrom:Liberate\n"));
        Assert.assertFalse(CardScriptProbe.usesCopyFaceFrom(GRIZZLY_BEARS));
    }

    /**
     * Every unique stock card must pass the probe, otherwise the Workshop would refuse to save an
     * unedited script. Runs over both databases; a failure lists every card that tripped it.
     */
    @Test(timeOut = 600000)
    public void stockPoolHasNoFalsePositives() {
        List<String> failures = new ArrayList<>();
        int probed = 0;
        for (CardDb cardDb : new CardDb[] { db.getCommonCards(), db.getVariantCards() }) {
            for (PaperCard pc : cardDb.getUniqueCards()) {
                probed++;
                try {
                    CardScriptProbe.probeCard(pc.getRules(), pc.getEdition(), pc.getRarity());
                } catch (Exception | AssertionError | StackOverflowError ex) {
                    // keep the top of the trace: a null-Game NPE is only diagnosable by its frame
                    String[] trace = ExceptionUtils.getStackFrames(ExceptionUtils.getRootCause(ex) != null ? ExceptionUtils.getRootCause(ex) : ex);
                    failures.add(pc.getName() + " [" + pc.getEdition() + "]: " + ExceptionUtils.getRootCauseMessage(ex)
                            + "\n    " + String.join("\n    ", Arrays.copyOfRange(trace, 0, Math.min(trace.length, 8))));
                }
            }
        }
        System.out.println("Workshop probe swept " + probed + " unique stock cards, " + failures.size() + " refused");
        Assert.assertTrue(probed > 20000, "expected the full card pool to be loaded, probed only " + probed);
        Assert.assertTrue(failures.isEmpty(), "the probe refused valid stock cards:\n" + String.join("\n", failures));
    }

    /**
     * The script lookup must follow the card reader's file layout, or a card the catalog lists reads
     * "No script found": the rebalanced/ folder, and the handful of stock scripts filed under a folder
     * that is not their first character (p/+2_mace.txt).
     */
    @Test
    public void stockLookupFollowsTheReadersFolders() {
        for (String stem : new String[] { "a-acererak_the_archlich", "+2_mace" }) {
            final CardScriptInfo info = CardScriptInfo.getScriptFor(stem);
            Assert.assertNotNull(info, stem + " must resolve to a stock script");
            Assert.assertTrue(info.getSource() == CardScriptInfo.Source.STOCK_FILE || info.getSource() == CardScriptInfo.Source.STOCK_ZIP, String.valueOf(info.getSource()));
        }
        Assert.assertTrue(CardScriptInfo.getScriptFor("+2_mace").getText().startsWith("Name:+2 Mace"));
        Assert.assertNull(CardScriptInfo.getScriptFor("workshop_probe_nothing"));
    }

    /**
     * CardStorageReader.collectCardFiles walks every sub-folder of custom/cards, so a script kept in
     * its own set folder is a loaded card; the Workshop's lookup (and its same-stem collision check)
     * must reach it the same way and skip exactly what the reader skips.
     */
    @Test
    public void findCustomFileWalksSubFoldersLikeTheReader() throws IOException {
        final Path root = Files.createTempDirectory("workshop-custom-tree");
        try {
            final Path inSet = Files.createDirectories(root.resolve("myset/deeper"));
            Files.writeString(inSet.resolve("foo.txt"), "Name:Foo\n");
            Files.writeString(Files.createDirectories(root.resolve(".hidden")).resolve("bar.txt"), "Name:Bar\n");
            Files.writeString(Files.createDirectories(root.resolve("upcoming")).resolve("baz.txt"), "Name:Baz\n");

            Assert.assertEquals(CardScriptInfo.findCustomFile(root.toFile(), "foo.txt"), inSet.resolve("foo.txt").toFile());
            Assert.assertNull(CardScriptInfo.findCustomFile(root.toFile(), "missing.txt"));
            Assert.assertNull(CardScriptInfo.findCustomFile(root.toFile(), "bar.txt"), "a dot-folder is not loaded by the reader");
            Assert.assertEquals(CardScriptInfo.findCustomFile(root.toFile(), "baz.txt") != null, BuildInfo.isDevelopmentVersion(),
                    "upcoming/ is loaded by the reader on development builds only");
            Assert.assertNull(CardScriptInfo.findCustomFile(root.resolve("nowhere").toFile(), "foo.txt"), "a missing directory is not an error");
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * Save of a stock card: the Workshop sets custom + path on the NEW rules and putCard reinitialises
     * the LIVE object in place, so both must travel with the reinit (deck conformance, the image
     * fetcher and advanced search all read the live object). Revert to Stock is the reverse trip.
     */
    @Test
    public void editorReinitCarriesCustomAndPath() {
        final CardDb common = editorDb;
        final String name = "Workshop Probe Override";
        final String stem = "workshop_probe_override";
        final String stockText = "Name:" + name + "\nManaCost:1 G\nTypes:Creature Bear\nPT:2/2\nOracle:\n";
        final String stockPath = "w/" + stem + ".txt";

        CardRules stock = CardScriptProbe.parseRules(stockText, stem);
        stock.setPath(stockPath);
        common.getEditor().putCard(stock);
        final CardRules live = common.getCard(name).getRules();
        Assert.assertFalse(live.isCustom());
        Assert.assertEquals(live.getPath(), stockPath);

        CardRules override = CardScriptProbe.parseRules(stockText.replace("PT:2/2", "PT:3/3"), stem);
        override.setCustom();
        override.setPath("custom/cards/w/" + stem + ".txt");
        Assert.assertSame(common.getEditor().putCard(override), live, "same name reinitializes in place");
        Assert.assertTrue(live.isCustom(), "the live rules must read custom after a Workshop save, not only after a restart");
        Assert.assertEquals(live.getPath(), "custom/cards/w/" + stem + ".txt");

        CardRules reverted = CardScriptProbe.parseRules(stockText, stem);
        reverted.setPath(stockPath);
        common.getEditor().putCard(reverted);
        Assert.assertFalse(live.isCustom(), "Revert to Stock must clear custom on the live rules");
        Assert.assertEquals(live.getPath(), stockPath);
    }

    /**
     * A rename goes through the editor's add path, which used to index the new card by name only and
     * file a set-less card under the unknown edition: its face was not retrievable and its image key
     * read "???/" until the next start, when the reader filed it under USER.
     */
    @Test
    public void editorAddIndexesTheFaceAndFilesASetlessCustomCardUnderUser() {
        final CardDb common = editorDb;
        final String name = "Workshop Probe Setless";
        CardRules rules = CardScriptProbe.parseRules("Name:" + name + "\nManaCost:1 G\nTypes:Creature Bear\nPT:2/2\nOracle:\n", "workshop_probe_setless");
        rules.setCustom();
        common.getEditor().putCard(rules);
        final PaperCard pc = common.getCard(name);
        Assert.assertNotNull(pc, "putCard must make the card retrievable by name");
        Assert.assertNotNull(common.getFaceByName(name), "putCard must index the face");
        Assert.assertEquals(pc.getEdition(), "USER", "a set-less custom card is filed under USER, as at load time");
        Assert.assertTrue(pc.getCardImageKey().startsWith("USER/"), pc.getCardImageKey());
    }
}
