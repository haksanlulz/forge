package forge.screens.workshop.controllers;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import forge.ImageCache;
import forge.card.CardDb;
import forge.card.CardRules;
import forge.card.CardSplitType;
import forge.gui.card.CardScriptInfo;
import forge.gui.card.CardScriptInfo.Source;
import forge.gui.card.CardScriptProbe;
import forge.gui.framework.ICDoc;
import forge.item.PaperCard;
import forge.itemmanager.CardManager;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.screens.match.controllers.CDetailPicture;
import forge.screens.workshop.WorkshopFiles;
import forge.screens.workshop.views.VCardDesigner;
import forge.screens.workshop.views.VWorkshopCatalog;
import forge.toolbox.FOptionPane;
import forge.util.ItemPool;
import forge.util.Localizer;

/**
 * Controls the "card designer" panel in the workshop UI.
 *
 * <br><br><i>(C at beginning of class name denotes a control class.)</i>
 *
 */
public enum CCardDesigner implements ICDoc {
    /** */
    SINGLETON_INSTANCE;

    private File lastArtDir;
    private String artNote;
    /** The printing {@link #artNote} was written for: the note belongs to that card's status line only. */
    private PaperCard artNoteCard;

    CCardDesigner() {
        final VCardDesigner view = VCardDesigner.SINGLETON_INSTANCE;
        view.getBtnSaveCard().setCommand((Runnable) CCardScript.SINGLETON_INSTANCE::saveChanges);
        view.getBtnNewCard().setCommand((Runnable) this::newCard);
        view.getBtnSetArt().setCommand((Runnable) () -> setArt(false));
        view.getBtnSetBackArt().setCommand((Runnable) () -> setArt(true));
        view.getBtnRevert().setCommand((Runnable) this::revertToStock);
        view.getBtnDelete().setCommand((Runnable) this::deleteCustomCard);
    }

    private static String msg(final String key, final Object... args) {
        return Localizer.getInstance().getMessage(key, args);
    }

    private static String rootMessage(final Throwable ex) {
        final String root = ExceptionUtils.getRootCauseMessage(ex);
        return StringUtils.isBlank(root) ? String.valueOf(ex) : root;
    }

    private static CardManager catalog() {
        return VWorkshopCatalog.SINGLETON_INSTANCE.getCardManager();
    }

    private static CDetailPicture pictures() {
        return VWorkshopCatalog.SINGLETON_INSTANCE.getCDetailPicture();
    }

    private static CCardScript script() {
        return CCardScript.SINGLETON_INSTANCE;
    }

    private static CardDb dbFor(final CardRules rules) {
        return rules.isVariant() ? FModel.getMagicDb().getVariantCards() : FModel.getMagicDb().getCommonCards();
    }

    /** The art file Forge would read for this key: the existing one, probed in ImageKeys' order, else the jpg it would get. */
    private static File artFileFor(final String imageKey) {
        for (final String ext : new String[] { "jpg", "png" }) {
            final File candidate = new File(ForgeConstants.CACHE_CARD_PICS_DIR, imageKey + "." + ext);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return new File(ForgeConstants.CACHE_CARD_PICS_DIR, imageKey + ".jpg");
    }

    /** A flip card's back is the rotated front image: Forge never reads a back-face file for it. */
    private static boolean hasBackFaceArt(final PaperCard pc) {
        return pc != null && pc.hasBackFace() && pc.getRules().getSplitType() != CardSplitType.Flip
                && !StringUtils.isBlank(pc.getCardAltImageKey());
    }

    /**
     * Puts a printing into the catalog unless it is already listed. The catalog pool is unbounded
     * and unique-by-name, so addItem on a listed printing would only raise its pool count.
     */
    private static void addToCatalog(final PaperCard printing) {
        if (!catalog().getPool().contains(printing)) {
            catalog().addItem(printing, 1);
        }
    }

    /**
     * Puts every printing of a stock card back into the catalog after its custom shadow was removed
     * (removeItems took every printing out) and returns the first, or null when there is none.
     */
    private static PaperCard restoreToCatalog(final CardDb cardDb, final String name) {
        PaperCard first = null;
        for (final PaperCard printing : cardDb.getAllCardsNoAlt(name)) {
            addToCatalog(printing);
            if (first == null) {
                first = printing;
            }
        }
        return first;
    }

    /** Selects a card in the catalog, clearing the filters if they hide it. */
    private static void select(final PaperCard pc) {
        if (catalog().getSelectedItem() != pc) {
            catalog().resetFilters();
            catalog().setSelectedItem(pc);
        }
        catalog().scrollSelectionIntoView();
    }

    /**
     * Updates the status line and the buttons for the card the script pane is showing.
     * Called by {@link CCardScript#refresh()} so enablement follows every card change.
     */
    public void refreshFor(final PaperCard pc, final CardScriptInfo info) {
        final VCardDesigner view = VCardDesigner.SINGLETON_INSTANCE;
        final StringBuilder status = new StringBuilder();
        if (info == null) {
            status.append(msg("lblWorkshopStatusNone"));
        } else {
            //no file: the stock text shown read-only because a custom file with another card's Name: sits under this stem
            final String path = info.getFile() == null ? msg("lblWorkshopStatusReadOnly") : info.getFile().getPath();
            switch (info.getSource()) {
            case STOCK_ZIP:
                status.append(msg("lblWorkshopStatusStockZip", String.valueOf(CardScriptInfo.stockZipEntryFor(info.getStem())), path));
                status.append('\n').append(msg("lblWorkshopStatusOverrideNote"));
                break;
            case STOCK_FILE:
                status.append(msg("lblWorkshopStatusStockFile", path));
                break;
            case CUSTOM_OVERRIDE:
                status.append(msg("lblWorkshopStatusOverride", path));
                status.append('\n').append(msg("lblWorkshopStatusOverrideNote"));
                break;
            case CUSTOM_CARD:
            default:
                status.append(msg("lblWorkshopStatusCustom", path));
                break;
            }
        }
        final String imageKey = pc == null ? null : pc.getCardImageKey();
        if (!StringUtils.isBlank(imageKey)) {
            status.append('\n').append(msg("lblWorkshopStatusArtPath", artFileFor(imageKey).getPath()));
        }
        if (artNote != null && pc == artNoteCard) {
            status.append('\n').append(artNote);
        } else {
            artNote = null; //the note was about another card: it must not follow the user around the catalog
            artNoteCard = null;
        }
        final String zipProblem = CardScriptInfo.getZipProblem();
        if (zipProblem != null) {
            status.append('\n').append(msg("lblWorkshopStatusZipProblem", zipProblem));
        }
        view.getTxtStatus().setText(status.toString());

        final Source source = info == null ? null : info.getSource();
        view.getBtnSetArt().setEnabled(!StringUtils.isBlank(imageKey));
        view.getBtnSetBackArt().setVisible(hasBackFaceArt(pc));
        view.getBtnRevert().setVisible(source == Source.CUSTOM_OVERRIDE);
        view.getBtnDelete().setVisible(source == Source.CUSTOM_CARD);
    }

    /** Prompts for a name, writes a template script under the custom cards dir, registers it and selects it. */
    public void newCard() {
        if (!script().canSwitchAway(false) || CCardScript.refuseIfNetworkMatchActive()) {
            return;
        }
        String name = FOptionPane.showInputDialog(msg("lblWorkshopNewCardPrompt"), msg("lblWorkshopNewCard"));
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isEmpty()) {
            return;
        }
        final String stem = CardScriptInfo.toFileStem(name);
        if (stem.isEmpty() || name.indexOf(CardDb.NameSetSeparator) >= 0) {
            FOptionPane.showErrorDialog(msg("lblWorkshopNameInvalid"));
            return;
        }
        if (CCardScript.isNameTaken(name)) {
            FOptionPane.showErrorDialog(msg("lblWorkshopNameTaken", name));
            return;
        }
        if (CardScriptInfo.readStockScript(stem) != null) {
            //"Grizzly-Bears" would land in grizzly_bears.txt and load as an override of Grizzly Bears
            FOptionPane.showErrorDialog(msg("lblWorkshopStemTaken", stem));
            return;
        }
        final CardScriptInfo target = CardScriptInfo.customTargetFor(stem);
        //the reader keys loaded scripts by stem, so a same-stem file ANYWHERE under custom/cards would collide at the next start
        final File sameStem = target.getFile().exists() ? target.getFile()
                : CardScriptInfo.findCustomFile(new File(ForgeConstants.USER_CUSTOM_CARDS_DIR), stem + ".txt");
        if (sameStem != null) {
            FOptionPane.showErrorDialog(msg("lblWorkshopFileExists", sameStem.getPath()));
            return;
        }

        final String text = WorkshopFiles.template(name);
        if (!target.trySetText(text)) {
            FOptionPane.showErrorDialog(msg("lblWorkshopWriteFailed", String.valueOf(target.getLastError())));
            return;
        }

        final CardRules rules;
        try {
            rules = CardScriptProbe.parseRules(text, stem);
            rules.setCustom();
            rules.setPath(target.getFile().getPath());
        } catch (final Exception ex) {
            target.deleteFile();
            FOptionPane.showErrorDialog(msg("lblWorkshopRulesRefused", rootMessage(ex)));
            return;
        }
        final CardDb cardDb = dbFor(rules);
        cardDb.getEditor().putCard(rules);
        final PaperCard pc = cardDb.getCard(name);
        if (pc == null) {
            target.deleteFile();
            FOptionPane.showErrorDialog(msg("lblWorkshopRulesRefused", name));
            return;
        }
        CCardScript.refreshCachedCards(cardDb, name); //a card of this name deleted earlier this session is otherwise still what the picture panel renders
        CardScriptInfo.register(stem, target);
        artNote = null;
        artNoteCard = null;

        catalog().addItem(pc, 1); //the selection listener shows the template in the script pane
        select(pc);
        script().showCard(pc); //no-op when the listener already did it
    }

    /** Copies a picked image into the picture cache under this printing's image key and repaints. */
    public void setArt(final boolean backFace) {
        if (script().getCurrentCard() == null || !script().canSwitchAway(false)) {
            return;
        }
        final PaperCard pc = script().getCurrentCard(); //read after the gate: its Save option can rename the current card
        if (pc == null) {
            return;
        }
        if (backFace && !hasBackFaceArt(pc)) {
            return; //a flip card's back file would be written and never read
        }
        final String imageKey = backFace ? pc.getCardAltImageKey() : pc.getCardImageKey();
        if (StringUtils.isBlank(imageKey)) {
            return;
        }
        final JFileChooser chooser = lastArtDir == null ? new JFileChooser() : new JFileChooser(lastArtDir);
        chooser.setFileFilter(new FileNameExtensionFilter(msg("lblWorkshopImageFiles"), "jpg", "jpeg", "png"));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setMultiSelectionEnabled(false);
        if (chooser.showOpenDialog(JOptionPane.getRootFrame()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final File src = chooser.getSelectedFile();
        lastArtDir = src.getParentFile();

        final File picsRoot = new File(ForgeConstants.CACHE_CARD_PICS_DIR);
        final File dest;
        try {
            //the install sweeps the stale .full/.fullborder twins the folder preload would keep serving, and
            //a hand-placed Name.fullborder.jpg is exactly such a twin: name every file that goes, and ask first
            final List<File> replaced = WorkshopFiles.filesInstallWouldReplace(src, picsRoot, imageKey);
            if (!replaced.isEmpty()) {
                final StringBuilder names = new StringBuilder();
                for (final File f : replaced) {
                    names.append('\n').append(f.getPath());
                }
                if (!FOptionPane.showConfirmDialog(msg("lblWorkshopArtReplaceConfirm", names.toString()), msg("lblWorkshopSetArt"), false)) {
                    return;
                }
            }
            dest = WorkshopFiles.installArt(src, picsRoot, imageKey);
        } catch (final IOException ex) {
            FOptionPane.showErrorDialog(msg("lblWorkshopArtRefused", String.valueOf(ex.getMessage())));
            return;
        }

        ImageCache.invalidate(pc);
        artNote = msg("lblWorkshopArtInstalled", dest.getPath());
        artNoteCard = pc;
        pictures().showItem(pc);
        catalog().repaint();
        refreshFor(pc, script().getCurrentScriptInfo());
    }

    /** Deletes the custom override of a stock card and puts the stock script back, in memory and in the pane. */
    public void revertToStock() {
        if (script().getCurrentScriptInfo() == null || script().getCurrentScriptInfo().getSource() != Source.CUSTOM_OVERRIDE
                || !script().canSwitchAway(false) || CCardScript.refuseIfNetworkMatchActive()) {
            return; //an unsaved edit gets the Save / Don't Save / Cancel prompt here too, never a silent discard
        }
        //read after the gate: its Save option can rename the current card
        final PaperCard pc = script().getCurrentCard();
        final CardScriptInfo info = script().getCurrentScriptInfo();
        if (pc == null || info == null || info.getSource() != Source.CUSTOM_OVERRIDE) {
            return;
        }
        final String stem = info.getStem();
        final String stock = CardScriptInfo.readStockScript(stem);
        if (stock == null) {
            FOptionPane.showErrorDialog(msg("lblWorkshopStockUnreachable", String.valueOf(CardScriptInfo.getZipProblem())));
            return;
        }
        final CardRules stockRules;
        try {
            stockRules = CardScriptProbe.parseRules(stock, stem); //the stock script must parse before the override is destroyed
        } catch (final Exception ex) {
            FOptionPane.showErrorDialog(msg("lblWorkshopStockUnreachable", rootMessage(ex)));
            return;
        }
        if (!CardScriptProbe.hasAllFaces(stockRules)) {
            //a CopyFaceFrom stock script gets its face from the database at load time; it cannot be rebuilt here
            FOptionPane.showErrorDialog(msg("lblWorkshopStockUnreachable", msg("lblWorkshopCopyFaceRefused")));
            return;
        }
        stockRules.setPath(CardScriptInfo.stockScriptPathFor(stem)); //custom stays false: the reinit copies both onto the live rules
        if (!FOptionPane.showConfirmDialog(msg("lblWorkshopRevertConfirm", pc.getName(), String.valueOf(info.getFile())), msg("lblWorkshopRevertToStock"))) {
            return;
        }
        if (!info.deleteFile()) {
            FOptionPane.showErrorDialog(msg("lblWorkshopWriteFailed", String.valueOf(info.getLastError())));
            return;
        }
        artNote = null;
        artNoteCard = null;

        if (stockRules.getName().equals(pc.getName()) && stockRules.isVariant() == pc.getRules().isVariant()) {
            final CardDb cardDb = dbFor(stockRules);
            cardDb.getEditor().putCard(stockRules); //same name: the existing rules object is reinitialized in place
            CCardScript.refreshCachedCards(cardDb, pc.getName());
            script().reloadCurrent(); //re-resolves to the stock source
            pictures().showItem(pc);
        } else {
            //the override was renamed by hand: swap the custom entry for the stock one
            script().discardCard();
            final List<PaperCard> gone = dbFor(pc.getRules()).getEditor().removeCard(pc.getRules());
            catalog().removeItems(ItemPool.createFrom(gone, PaperCard.class));
            CardScriptInfo.forget(stem);
            final CardDb cardDb = dbFor(stockRules);
            cardDb.getEditor().putCard(stockRules);
            CCardScript.refreshCachedCards(cardDb, stockRules.getName());
            //a hand-renamed override never replaced the stock card at load (a different Name:), so the stock
            //printings may still be listed: put back only what is missing, every printing of it
            final PaperCard back = restoreToCatalog(cardDb, stockRules.getName());
            if (back != null) {
                select(back);
                script().showCard(back);
            }
        }
        catalog().repaint();
    }

    /** Deletes a card that exists only as a custom file, from disk, the database and the catalog. */
    public void deleteCustomCard() {
        final PaperCard pc = script().getCurrentCard();
        final CardScriptInfo info = script().getCurrentScriptInfo();
        if (pc == null || info == null || info.getSource() != Source.CUSTOM_CARD || CCardScript.refuseIfNetworkMatchActive()) {
            return;
        }
        if (!FOptionPane.showConfirmDialog(msg("lblWorkshopDeleteConfirm", pc.getName(), String.valueOf(info.getFile())), msg("lblWorkshopDeleteCustomCard"), false)) {
            return;
        }
        if (!info.deleteFile()) {
            FOptionPane.showErrorDialog(msg("lblWorkshopWriteFailed", String.valueOf(info.getLastError())));
            return;
        }
        artNote = null;
        artNoteCard = null;
        script().discardCard(); //nothing to save: the card is going away
        final CardDb cardDb = dbFor(pc.getRules());
        final List<PaperCard> gone = cardDb.getEditor().removeCard(pc.getRules());
        catalog().removeItems(ItemPool.createFrom(gone, PaperCard.class)); //the list view re-selects a neighbour
        CardScriptInfo.forget(info.getStem());
        restoreShadowedStock(pc.getName(), info.getStem());
        if (catalog().getSelectedItem() == null) {
            script().showCard(null);
        } else {
            script().showCard(catalog().getSelectedItem());
        }
        catalog().repaint();
    }

    /**
     * Puts back a stock card that a custom card of the same NAME shadowed from a different file (a DFC's
     * stem joins both faces, so the exact stem misses it), as the next start would. For Delete Custom
     * Card and for the rename of a custom card, which removes the original the same way.
     */
    void restoreShadowedStock(final String name, final String customStem) {
        final String stockStem = CardScriptInfo.stockStemForName(name);
        final String stock = stockStem == null || stockStem.equals(customStem) ? null : CardScriptInfo.readStockScript(stockStem);
        if (stock == null) {
            return;
        }
        try {
            final CardRules stockRules = CardScriptProbe.parseRules(stock, stockStem);
            if (CardScriptProbe.hasAllFaces(stockRules) && stockRules.getName().equals(name)) {
                stockRules.setPath(CardScriptInfo.stockScriptPathFor(stockStem));
                final CardDb stockDb = dbFor(stockRules);
                stockDb.getEditor().putCard(stockRules);
                CCardScript.refreshCachedCards(stockDb, name); //the custom card's printings were equal keys in Card's UI cache
                restoreToCatalog(stockDb, name); //removeItems(gone) took every stock-edition printing out with the shadow
            }
        } catch (final Exception ex) {
            System.err.println("Workshop: could not restore the stock " + name + " after removing the custom card: " + ex);
        }
    }

    //========== Overridden methods

    @Override
    public void register() {
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.ICDoc#initialize()
     */
    @Override
    public void initialize() {
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.ICDoc#update()
     */
    @Override
    public void update() {
    }
}
