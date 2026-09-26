package forge.gamemodes.net.sharedart;

import forge.StaticData;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.net.TestUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

/**
 * Only a custom card's own art is ever shared. A custom script named like a
 * stock card replaces that card's rules, so its rules alone say nothing about
 * whether the picture in a custom set's folder is a stock scan the image
 * fetcher downloaded there. Needs the real card database, so it lives here
 * rather than in forge-gui, and shares SharedArtSource's package on purpose.
 */
public class SharedArtSourceTest {

    @BeforeClass
    public void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    private static void addCustomCard(final String name) {
        final CardRules rules = CardRules.fromScript(List.of(
                "Name:" + name, "ManaCost:R R R", "Types:Creature Efreet", "PT:3/6"));
        rules.setCustom();
        StaticData.instance().getCommonCards().addCard(new PaperCard(rules, SharedArtPolicy.USER_SET, CardRarity.Special));
    }

    @Test
    public void testACustomScriptNamedLikeAStockCardIsNeverServedOrAskedFor() {
        // A stock name no other test uses: its printing in the custom bucket stays in this JVM's card database.
        addCustomCard("Ydwen Efreet");
        final String stockNamed = "c:Ydwen Efreet|USER|1";
        Assert.assertNull(SharedArtSource.resolve(stockNamed), "a stock-named custom card's picture may be a stock scan");
        Assert.assertFalse(SharedArtSource.needsRemote(stockNamed), "and no player serves one, so none is asked for");

        addCustomCard("Shared Art Probe Efreet");
        final String customOnly = "c:Shared Art Probe Efreet|USER|1";
        Assert.assertNotNull(SharedArtSource.resolve(customOnly), "a name only custom sets print is shared");
        Assert.assertTrue(SharedArtSource.needsRemote(customOnly), "and asked for when this machine lacks its picture");
    }

    @Test
    public void testNothingIsAskedForUnderAStockSet() {
        // A lobby deck arrives from another machine and can name any card under any set.
        Assert.assertFalse(SharedArtSource.needsRemote("c:Shared Art Probe Nobody|LEA|1"), "a stock set's pictures are never another player's");
    }

    @Test
    public void testInAnEditionThisMachineLacksOnlyANameNoStockEditionPrintsIsAskedFor() {
        Assert.assertNull(StaticData.instance().getEditions().get("XNOPE"));
        Assert.assertFalse(SharedArtSource.needsRemote("c:Grizzly Bears|XNOPE|1"), "no player serves a stock card's picture");
        Assert.assertTrue(SharedArtSource.needsRemote("c:Shared Art Probe Nobody Else|XNOPE|1"));
        // A custom set this machine holds, without the card: the same rule.
        Assert.assertNotNull(StaticData.instance().getEditions().get(SharedArtPolicy.USER_SET));
        Assert.assertFalse(SharedArtSource.needsRemote("c:Grizzly Bears|" + SharedArtPolicy.USER_SET + "|1"));
        // A name only the variant database prints (a scheme, a plane, a vanguard) counts as stock too.
        final String variant = StaticData.instance().getVariantCards().getAllCards().iterator().next().getName();
        Assert.assertNull(StaticData.instance().getCommonCards().getCard(variant));
        Assert.assertFalse(SharedArtSource.needsRemote("c:" + variant + "|XNOPE|1"), variant);
    }

    @Test
    public void testOnlyCardsThisHostKnowsInThatCustomEditionCountAsBrought() {
        final CardRules rules = CardRules.fromScript(List.of(
                "Name:Shared Art Probe Djinn", "ManaCost:U U", "Types:Creature Djinn", "PT:2/2"));
        rules.setCustom();
        final PaperCard known = new PaperCard(rules, SharedArtPolicy.USER_SET, CardRarity.Special);
        StaticData.instance().getCommonCards().addCard(known);
        final Deck fromTheWire = new Deck("wire");
        fromTheWire.getMain().add(known);
        // A stock card claimed in the custom bucket: this host holds no such printing, so it is not brought.
        fromTheWire.getMain().add(new PaperCard(StaticData.instance().getCommonCards().getCard("Grizzly Bears").getRules(),
                SharedArtPolicy.USER_SET, CardRarity.Common));
        Assert.assertEquals(SharedArtSource.knownArtIds(fromTheWire), Set.of(SharedArtSource.artIdOf(known)));
    }
}
