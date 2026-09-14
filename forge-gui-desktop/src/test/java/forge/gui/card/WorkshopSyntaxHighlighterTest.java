package forge.gui.card;

import java.io.File;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;

/**
 * The Workshop's syntax highlighter is heuristic: it must accept what the game reads and light up
 * what the game would choke on. Pinned against two stock scripts read straight from the loose
 * res/cardsfolder tree, so no card database is loaded here.
 */
public class WorkshopSyntaxHighlighterTest {

    private static final String GRIZZLY_BEARS = "Name:Grizzly Bears\nManaCost:1 G\nTypes:Creature Bear\nPT:2/2\nOracle:\n";

    @BeforeClass
    public void loadTypes() {
        GuiBase.setInterface(new GuiDesktop()); // ForgeConstants resolves the assets dir through it
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", ForgeConstants.LANG_DIR);
        FModel.loadDynamicGamedata(); // creature / spell subtypes, which the highlighter checks Types: against
    }

    private static String stockScript(final String folder, final String stem) {
        final File file = new File(ForgeConstants.CARD_DATA_DIR + folder, stem + ".txt");
        Assert.assertTrue(file.isFile(), "the source tree serves loose stock scripts: " + file);
        return FileUtil.readFileToString(file);
    }

    /**
     * The save-time syntax warning must not fire on a stock script: the highlighter used to reject
     * {@code ValidTgts$ Any}, a spell with no {@code Cost$}, and every parameter key it did not know,
     * so a byte-identical Lightning Bolt got the "syntax errors, save anyway?" dialog. A real mistake
     * must still light up, and a half-typed one must be a region, not an exception.
     */
    @Test
    public void acceptsStockScriptsAndFlagsRealMistakes() {
        final String bolt = stockScript("l", "lightning_bolt");
        Assert.assertTrue(bolt.contains("ValidTgts$ Any"), bolt);
        Assert.assertTrue(new CardScriptParser(bolt).getErrorRegions().isEmpty(), "stock Lightning Bolt must have no error regions");
        final String sorcerer = stockScript("p", "prodigal_sorcerer");
        Assert.assertTrue(new CardScriptParser(sorcerer).getErrorRegions().isEmpty(), "a Cost$ T activated ability must have no error regions");

        Assert.assertFalse(new CardScriptParser(bolt.replace("DealDamage", "Bogus")).getErrorRegions().isEmpty(), "an unknown ApiType must be flagged");
        Assert.assertFalse(new CardScriptParser(bolt.replace("ValidTgts$ Any", "ValidTgts$ Anyy")).getErrorRegions().isEmpty(), "an unknown target word must be flagged");
        Assert.assertFalse(new CardScriptParser(sorcerer.replace("Cost$ T", "Costt$ T")).getErrorRegions().isEmpty(), "an activated ability without a Cost must be flagged");
        Assert.assertFalse(new CardScriptParser(bolt + "\nA:\n").getErrorRegions().isEmpty(), "an ability line with nothing after the colon must be flagged, not thrown");
        Assert.assertFalse(new CardScriptParser(GRIZZLY_BEARS
                + "T:Mode$ ChangesZone | Destination$ Battlefield | ValidCard$ Card.Self | Execute$ TrigEmpty | TriggerDescription$ nope\nSVar:TrigEmpty:\n")
                .getErrorRegions().isEmpty(), "an SVar ability left empty must be flagged, not thrown");
    }

    /**
     * CardProperty reads {@code powerGE3} with a fixed slice: the two letters after the word are the
     * comparator, the rest the operand. {@code Creature.power} is therefore a StringIndexOutOfBoundsException
     * the first time the filter runs in a game and {@code Creature.powerful} a filter that never matches;
     * neither is caught by the save-time card probe, which does not evaluate targets.
     */
    @Test
    public void flagsAComparisonPropertyWithoutItsComparator() {
        final String bolt = stockScript("l", "lightning_bolt");
        Assert.assertFalse(new CardScriptParser(bolt.replace("ValidTgts$ Any", "ValidTgts$ Creature.power")).getErrorRegions().isEmpty(), "a comparison property without its comparator must be flagged");
        Assert.assertFalse(new CardScriptParser(bolt.replace("ValidTgts$ Any", "ValidTgts$ Creature.powerful")).getErrorRegions().isEmpty(), "a comparison property with a garbage comparator must be flagged");
        Assert.assertFalse(new CardScriptParser(bolt.replace("ValidTgts$ Any", "ValidTgts$ Creature.cmcGE")).getErrorRegions().isEmpty(), "a comparison property with no operand must be flagged");
        Assert.assertTrue(new CardScriptParser(bolt.replace("ValidTgts$ Any", "ValidTgts$ Creature.powerGE3")).getErrorRegions().isEmpty());
        Assert.assertTrue(new CardScriptParser(bolt.replace("ValidTgts$ Any", "ValidTgts$ Creature.cmcLEX+basePowerNE2")).getErrorRegions().isEmpty(), "an SVar operand and a base stat both read the same way");
        Assert.assertTrue(new CardScriptParser(bolt.replace("ValidTgts$ Any", "ValidTgts$ Creature.powerGTtoughness")).getErrorRegions().isEmpty(), "an exact property that starts with a comparison word is not a comparison");
    }
}
