package forge.gui.card;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.GuiDesktop;
import forge.gui.GuiBase;

/**
 * A source checkout serves loose res/cardsfolder files, so nothing else in the suite reaches the packed
 * lookup a release install runs on (cardsfolder.zip and no loose files, the case the Workshop was dead
 * on). A zip fixture stands in for the archive.
 */
public class WorkshopScriptLookupTest {

    @BeforeClass
    public void setGui() {
        GuiBase.setInterface(new GuiDesktop()); // ForgeConstants resolves the user and assets dirs through it
    }

    private static void putScript(final ZipOutputStream out, final String entry, final String name) throws IOException {
        out.putNextEntry(new ZipEntry(entry));
        out.write(("Name:" + name + "\nManaCost:G\nTypes:Creature\nPT:1/1\nOracle:\n").getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    @Test
    public void packedStockScriptsResolveFromTheZip() throws IOException {
        final Path zip = Files.createTempFile("cardsfolder", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            putScript(out, "w/workshop_zip_probe.txt", "Workshop Zip Probe");
            putScript(out, "x/workshop_odd_probe.txt", "Workshop Odd Probe"); // filed under a folder that is not its first letter, like p/+2_mace.txt
            putScript(out, "x/deeper/workshop_nested_probe.txt", "Workshop Nested Probe"); // the reader files scripts one level deep only
        }
        try {
            CardScriptInfo.useStockZipForTests(zip.toFile());
            final CardScriptInfo info = CardScriptInfo.getScriptFor("workshop_zip_probe");
            Assert.assertNotNull(info, "a packed stock script must resolve");
            Assert.assertEquals(info.getSource(), CardScriptInfo.Source.STOCK_ZIP);
            Assert.assertTrue(info.getText().startsWith("Name:Workshop Zip Probe"), info.getText());
            Assert.assertEquals(info.getFile(), CardScriptInfo.overrideFileFor("workshop_zip_probe"), "a save goes to the custom override, never into res/");
            Assert.assertEquals(CardScriptInfo.stockZipEntryFor("workshop_odd_probe"), "x/workshop_odd_probe.txt");
            Assert.assertEquals(CardScriptInfo.getScriptFor("workshop_odd_probe").getSource(), CardScriptInfo.Source.STOCK_ZIP);
            Assert.assertNull(CardScriptInfo.stockZipEntryFor("workshop_nested_probe"));
            Assert.assertNull(CardScriptInfo.stockZipEntryFor("workshop_zip_nothing"));
            Assert.assertNull(CardScriptInfo.getScriptFor("workshop_zip_nothing"));
            Assert.assertNull(CardScriptInfo.getZipProblem());
        } finally {
            CardScriptInfo.useStockZipForTests(null);
            Files.deleteIfExists(zip);
        }
    }

    /**
     * A custom file under a stock stem is only that stock card's script when its Name: says so; a
     * hand-renamed file (grizzly_bears.txt holding Name:Grizzly Bearz) loads as a second card and the
     * stock Grizzly Bears must not show or save over it.
     */
    @Test
    public void aCustomFileIsOnlyTakenForTheCardItNames() {
        Assert.assertTrue(CardScriptInfo.scriptNamesCard("Name:Grizzly Bears\nManaCost:1 G\n", "Grizzly Bears"));
        Assert.assertTrue(CardScriptInfo.scriptNamesCard("  Name: Grizzly Bears \r\nManaCost:1 G\r\n", "Grizzly Bears"), "the reader trims");
        Assert.assertFalse(CardScriptInfo.scriptNamesCard("Name:Grizzly Bearz\nManaCost:1 G\n", "Grizzly Bears"));
        Assert.assertTrue(CardScriptInfo.scriptNamesCard("Name:Bind\nManaCost:1 G\nAlternateMode:Split\n\nALTERNATE\n\nName:Liberate\n", "Bind // Liberate"),
                "a split card's database name joins both faces; the script's first Name: is the left one");
        Assert.assertFalse(CardScriptInfo.scriptNamesCard("Name:Bind\n", "Binding Grasp"), "a prefix is not the card");
        Assert.assertTrue(CardScriptInfo.scriptNamesCard("ManaCost:1 G\n", "Grizzly Bears"), "no Name: line: nothing to contradict");
    }
}
