package forge.screens.workshop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.ICardFace;
import forge.gamesimulationtests.util.CardDatabaseHelper;
import forge.gui.GuiBase;
import forge.gui.card.CardScriptInfo;
import forge.gui.card.CardScriptProbe;
import forge.item.IPaperCard;
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

        CardRules override = CardScriptProbe.parseRules(stockText.replace("PT:2/2", "PT:3/3").replace("ManaCost:1 G", "ManaCost:1 R"), stem);
        override.setCustom();
        override.setPath("custom/cards/w/" + stem + ".txt");
        Assert.assertSame(common.getEditor().putCard(override), live, "same name reinitializes in place");
        Assert.assertTrue(live.isCustom(), "the live rules must read custom after a Workshop save, not only after a restart");
        Assert.assertEquals(live.getPath(), "custom/cards/w/" + stem + ".txt");
        Assert.assertTrue(live.getDeckbuildingColors().hasRed() && !live.getDeckbuildingColors().hasGreen(),
                "the lazily cached deckbuilding colors must be rebuilt from the new faces");

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

    /**
     * A save that renames a DFC's back face takes the reinit path (front name unchanged). Every index
     * keyed on the old back-face name must let go of it and take the new one, or New Card... accepts
     * the new name as free while a deck naming the old one still loads.
     */
    @Test
    public void editorReinitReindexesARenamedBackFace() {
        final CardDb common = editorDb;
        final String name = "Workshop Probe Front";
        final String stem = "workshop_probe_front_workshop_probe_back";
        final String script = "Name:" + name + "\nManaCost:U\nTypes:Creature Human\nPT:1/1\nAlternateMode:DoubleFaced\nOracle:\n\nALTERNATE\n\n"
                + "Name:%BACK%\nManaCost:no cost\nColors:blue\nTypes:Creature Insect\nPT:3/2\nOracle:\n";

        CardRules first = CardScriptProbe.parseRules(script.replace("%BACK%", "Workshop Probe Back"), stem);
        first.setCustom();
        common.getEditor().putCard(first);
        Assert.assertTrue(common.contains("Workshop Probe Back"), "the back face is listed after the first put");
        Assert.assertNotNull(common.getRules("Workshop Probe Back", true));

        CardRules renamedBack = CardScriptProbe.parseRules(script.replace("%BACK%", "Workshop Probe Renamed Back"), stem);
        renamedBack.setCustom();
        Assert.assertSame(common.getEditor().putCard(renamedBack), first, "same front name reinitializes in place");
        Assert.assertFalse(common.contains("Workshop Probe Back"), "the old back-face name must be gone from the printings index");
        Assert.assertNull(common.getRules("Workshop Probe Back", true), "the old back-face name must be gone from the alt-name lookup");
        Assert.assertNull(common.getFaceByName("Workshop Probe Back"));
        Assert.assertTrue(common.contains("Workshop Probe Renamed Back"), "the new back-face name must list the printings");
        Assert.assertSame(common.getRules("Workshop Probe Renamed Back", true), first);
        Assert.assertNotNull(common.getFaceByName("Workshop Probe Renamed Back"));

        common.getEditor().removeCard(first);
        Assert.assertFalse(common.contains(name));
        Assert.assertFalse(common.contains("Workshop Probe Renamed Back"));
    }

    /**
     * The Workshop renames a card by registering the new one BEFORE it removes the original. For a
     * double-faced card renamed on its front face only, the add path's putIfAbsent leaves the shared
     * back face's alt-name entry pointing at the original and removeCard then drops it; reindexFaces
     * is what points it at the renamed card again.
     */
    @Test
    public void renamingAFrontFaceKeepsTheSharedBackFaceLookedUp() {
        final CardDb common = editorDb;
        final String script = "Name:%FRONT%\nManaCost:U\nTypes:Creature Human\nPT:1/1\nAlternateMode:DoubleFaced\nOracle:\n\nALTERNATE\n\n"
                + "Name:Workshop Probe Shared Back\nManaCost:no cost\nColors:blue\nTypes:Creature Insect\nPT:3/2\nOracle:\n";
        CardRules first = CardScriptProbe.parseRules(script.replace("%FRONT%", "Workshop Probe Old Front"), "workshop_probe_old_front_workshop_probe_shared_back");
        first.setCustom();
        common.getEditor().putCard(first);
        CardRules renamed = CardScriptProbe.parseRules(script.replace("%FRONT%", "Workshop Probe New Front"), "workshop_probe_new_front_workshop_probe_shared_back");
        renamed.setCustom();
        common.getEditor().putCard(renamed);
        common.getEditor().removeCard(first);
        Assert.assertNull(common.getCard("Workshop Probe Old Front"));
        Assert.assertNotNull(common.getFaceByName("Workshop Probe Shared Back"), "the renamed card still declares the back face");
        Assert.assertNull(common.getRules("Workshop Probe Shared Back", true), "removeCard drops the alt-name entry the two cards shared: the reason reindexFaces exists");

        common.getEditor().reindexFaces(renamed);
        Assert.assertSame(common.getRules("Workshop Probe Shared Back", true), renamed, "the shared back face must resolve to the renamed card");
        Assert.assertTrue(common.contains("Workshop Probe Shared Back"));
        common.getEditor().removeCard(renamed);
        Assert.assertNull(common.getRules("Workshop Probe Shared Back", true));
    }

    /**
     * A CopyFaceFrom script borrows the OTHER card's face object; deleting the borrower must not
     * de-index the card it borrowed from. Against the shared database, which holds the stock Liberate.
     */
    @Test
    public void removingABorrowerLeavesTheLentFaceIndexed() {
        final CardDb common = db.getCommonCards();
        final ICardFace liberate = common.getFaceByName("Liberate");
        Assert.assertNotNull(liberate, "the stock Liberate must be loaded for this test");
        final String name = "Workshop Probe Borrower";
        CardRules borrower = CardScriptProbe.parseRules("Name:" + name + "\nManaCost:1 W\nTypes:Instant\nOracle:\nAlternateMode:Split\n\nALTERNATE\n\nCopyFaceFrom:Liberate\n",
                "workshop_probe_borrower_liberate");
        borrower.setCustom();
        common.getEditor().putCard(borrower);
        Assert.assertSame(borrower.getOtherPart(), liberate, "putCard supplies the placeholder face from the database, as loadCard does");
        final String fullName = name + " // Liberate";
        Assert.assertNotNull(common.getCard(fullName));

        common.getEditor().removeCard(borrower);
        Assert.assertNull(common.getCard(fullName));
        Assert.assertNull(common.getFaceByName(name));
        Assert.assertSame(common.getFaceByName("Liberate"), liberate, "removing the borrower must leave Liberate's face indexed");
        Assert.assertNotNull(common.getCard("Liberate"));
    }

    /**
     * The in-memory half of New Card / Save / Delete / New Card again: a set-less custom card lands
     * in USER, an in-place save updates the indexed face, removeCard frees the name (contains,
     * getRules with alt names, getFaceByName) so it can be re-added.
     */
    @Test
    public void customCardRoundTripsThroughTheEditor() {
        final CardDb common = editorDb;
        final String name = "Workshop Probe Bear";
        final String stem = "workshop_probe_bear";
        Assert.assertFalse(common.contains(name));
        Assert.assertNull(common.getRules(name, true));

        // New Card
        CardRules rules = CardScriptProbe.parseRules("Name:" + name + "\nManaCost:1 G\nTypes:Creature Bear\nPT:2/2\nOracle:\n", stem);
        rules.setCustom();
        common.getEditor().putCard(rules);
        PaperCard pc = common.getCard(name);
        Assert.assertNotNull(pc, "putCard must make the card retrievable by name");
        Assert.assertEquals(pc.getEdition(), "USER", "a set-less custom card is filed under USER, as at load time");
        Assert.assertEquals(pc.getRules().getNormalizedName(), stem);
        Assert.assertNotNull(common.getFaceByName(name), "putCard must index the face");
        Assert.assertTrue(pc.getCardImageKey().startsWith("USER/"), pc.getCardImageKey());

        // Save (same name): the existing rules object is reinitialized and the face re-indexed
        CardRules edited = CardScriptProbe.parseRules("Name:" + name + "\nManaCost:1 G\nTypes:Creature Bear\nPT:3/3\nOracle:It grew.\n", stem);
        edited.setCustom();
        CardRules kept = common.getEditor().putCard(edited);
        Assert.assertSame(kept, pc.getRules(), "same name reinitializes in place");
        Assert.assertEquals(common.getFaceByName(name).getOracleText(), "It grew.");
        Assert.assertEquals(common.getCard(name).getRules().getMainPart().getPower(), "3");

        // Delete
        List<PaperCard> gone = common.getEditor().removeCard(pc.getRules());
        Assert.assertEquals(gone.size(), 1);
        Assert.assertFalse(common.contains(name));
        Assert.assertNull(common.getRules(name, true));
        Assert.assertNull(common.getFaceByName(name));
        Assert.assertNull(common.getCard(name));
        Assert.assertFalse(common.getUniqueCards().contains(pc));

        // New Card again with the same name, in the same session
        CardRules again = CardScriptProbe.parseRules("Name:" + name + "\nManaCost:2 G\nTypes:Creature Bear\nPT:1/1\nOracle:\n", stem);
        again.setCustom();
        common.getEditor().putCard(again);
        Assert.assertNotNull(common.getCard(name));
        Assert.assertSame(common.getCard(name).getRules(), again);
        common.getEditor().removeCard(again);
        Assert.assertFalse(common.contains(name));
    }

    /**
     * A Workshop Art printing (Add Art Variant's copy of a stock card with the user's picture, in edition
     * WSART) is chosen per deck slot and never by the art preference: the set-less lookup leaves WSART out
     * whenever another edition is accepted, and the unique-by-name index skips it unless it is the card's
     * only print. The edition's pre-Alpha date alone would not do this: ORIGINAL_ART_ALL_EDITIONS takes the
     * oldest date, and LATEST_ART_ALL_EDITIONS walks to the first printing WITH a picture, which the
     * Workshop one has and, here, no stock printing does. The edition is registered as the Workshop does it
     * at runtime: read from the file Add Art Variant writes and put into a collection the custom-folder
     * append has already locked.
     */
    @Test
    public void workshopArtPrintingIsNeverTheArtPreferencesPick() throws IOException {
        final Path tmp = Files.createTempDirectory("workshop-art-preference");
        try {
            final String stockBear = "Workshop Probe Stock Bear";
            final String onlyBear = "Workshop Probe Only Bear";
            final File stockDir = Files.createDirectories(tmp.resolve("editions")).toFile();
            Files.write(new File(stockDir, "Probe Stock.txt").toPath(), List.of(
                    "[metadata]", "Code=WSSTK", "Name=Workshop Probe Stock", "Date=2000-01-01", "Type=Expansion", "",
                    "[cards]", "1 C " + stockBear), StandardCharsets.UTF_8);
            final File customDir = Files.createDirectories(tmp.resolve("custom")).toFile();
            final File artFile = new File(customDir, WorkshopFiles.ART_EDITION_FILE);
            WorkshopFiles.appendArtVariant(artFile, stockBear, CardRarity.Common);
            WorkshopFiles.appendArtVariant(artFile, onlyBear, CardRarity.Common);

            // as at start-up: the stock folder, then the custom folder appended, which locks the collection
            final CardEdition.Collection editions = new CardEdition.Collection(new CardEdition.Reader(stockDir));
            editions.append(new CardEdition.Collection(new CardEdition.Reader(Files.createDirectories(tmp.resolve("empty")).toFile(), true)));
            final CardEdition workshopArt = new CardEdition.Reader(customDir, true).readFile(artFile);
            Assert.assertThrows(UnsupportedOperationException.class, () -> editions.add(workshopArt)); // locked: why addCustomEdition exists
            editions.addCustomEdition(workshopArt);
            Assert.assertSame(editions.get(CardEdition.WORKSHOP_ART_CODE), workshopArt, "registered after start-up, retrievable by code");
            Assert.assertThrows(IllegalArgumentException.class, () -> editions.addCustomEdition(editions.get("WSSTK")));

            final CardRules stockRules = CardScriptProbe.parseRules("Name:" + stockBear + "\nManaCost:1 G\nTypes:Creature Bear\nPT:2/2\nOracle:\n", "workshop_probe_stock_bear");
            final CardRules onlyRules = CardScriptProbe.parseRules("Name:" + onlyBear + "\nManaCost:1 G\nTypes:Creature Bear\nPT:2/2\nOracle:\n", "workshop_probe_only_bear");
            final HashMap<String, CardRules> rules = new HashMap<>();
            rules.put(stockBear, stockRules);
            rules.put(onlyBear, onlyRules);
            final CardDb cardDb = new CardDb(rules, editions, new HashSet<>());
            cardDb.setCardArtPreference(false, false); // ORIGINAL_ART_ALL_EDITIONS: the oldest date, and WSART is dated 1993
            // no stock scan present, and the Workshop printing has its picture: the walk to a printing with an image lands on it
            final PaperCard stock = new PaperCard(stockRules, "WSSTK", CardRarity.Common) {
                @Override
                public boolean hasImage(final boolean update) {
                    return false;
                }
            };
            final PaperCard variant = new PaperCard(stockRules, CardEdition.WORKSHOP_ART_CODE, CardRarity.Common) {
                @Override
                public boolean hasImage(final boolean update) {
                    return true;
                }
            };
            final PaperCard only = new PaperCard(onlyRules, CardEdition.WORKSHOP_ART_CODE, CardRarity.Common) {
                @Override
                public boolean hasImage(final boolean update) {
                    return true;
                }
            };
            cardDb.addCard(stock);
            cardDb.addCard(variant);
            cardDb.addCard(only);

            Assert.assertSame(cardDb.getCardFromEditions(stockBear, CardDb.CardArtPreference.ORIGINAL_ART_ALL_EDITIONS, IPaperCard.DEFAULT_ART_INDEX, null), stock,
                    "original art: the 1993 Workshop printing must not win by date");
            Assert.assertSame(cardDb.getCardFromEditions(stockBear, CardDb.CardArtPreference.LATEST_ART_ALL_EDITIONS, IPaperCard.DEFAULT_ART_INDEX, null), stock,
                    "latest art: the walk to a printing with a picture must not land on the Workshop one");
            Assert.assertSame(cardDb.getCard(stockBear), stock, "a set-less request under the default preference");
            Assert.assertSame(cardDb.getCard(stockBear, CardEdition.WORKSHOP_ART_CODE), variant, "asked for by set, it is still the printing");
            Assert.assertSame(cardDb.getCardFromEditions(onlyBear, CardDb.CardArtPreference.LATEST_ART_ALL_EDITIONS, IPaperCard.DEFAULT_ART_INDEX, null), only,
                    "a card whose only printing is the Workshop one keeps it");

            // the unique-by-name index is rebuilt by the editor's reinit (a Workshop save); it must skip WSART the same way
            cardDb.getEditor().putCard(CardScriptProbe.parseRules("Name:" + stockBear + "\nManaCost:1 G\nTypes:Creature Bear\nPT:2/2\nOracle:\n", "workshop_probe_stock_bear"));
            Assert.assertTrue(cardDb.getUniqueCards().contains(stock), "unique print: the stock printing");
            Assert.assertFalse(cardDb.getUniqueCards().contains(variant), "unique print: never the Workshop one while a stock print exists");
            Assert.assertTrue(cardDb.getUniqueCards().contains(only), "unique print: the Workshop one when it is the card's only print");
        } finally {
            try (Stream<Path> walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * Delete Custom Card restores a stock card the custom one shadowed by NAME, so the stock stem
     * for a name must follow the reader's file names, not only the Workshop's own stem: the accent
     * fold (d/dandan.txt), the joined DFC file, the reader's lazy-load transform (a/a_i_m_bot.txt),
     * the rebalanced/ hyphen (a-akki_ronin.txt), a rebalanced DFC (hyphen candidate AND the
     * rebalanced/ folder); and never a mere prefix ("Bin" is not bind.txt).
     */
    @Test
    public void stockStemForNameFollowsTheReadersFileNames() {
        Assert.assertEquals(CardScriptInfo.stockStemForName("Grizzly Bears"), "grizzly_bears");
        Assert.assertEquals(CardScriptInfo.stockStemForName("Dandân"), "dandan");
        Assert.assertEquals(CardScriptInfo.stockStemForName("Delver of Secrets"), "delver_of_secrets_insectile_aberration");
        Assert.assertEquals(CardScriptInfo.stockStemForName("A.I.M. Bot"), "a_i_m_bot");
        Assert.assertEquals(CardScriptInfo.stockStemForName("A-Akki Ronin"), "a-akki_ronin");
        Assert.assertEquals(CardScriptInfo.stockStemForName("A-Alrund, God of the Cosmos"), "a-alrund_god_of_the_cosmos_hakka_whispering_raven",
                "a rebalanced DFC: hyphen candidate + rebalanced/ folder");
        Assert.assertNull(CardScriptInfo.stockStemForName("Bin"), "'bin' must not resolve to bind.txt / binding_*.txt");
        Assert.assertNull(CardScriptInfo.stockStemForName("Workshop Probe Nothing"));
    }
}
