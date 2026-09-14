package forge.screens.workshop.controllers;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.JTextPane;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Style;
import javax.swing.text.StyledDocument;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.google.common.collect.ImmutableList;

import forge.Singletons;
import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardRules;
import forge.game.card.Card;
import forge.gamemodes.net.server.FServerManager;
import forge.gui.card.CardScriptInfo;
import forge.gui.card.CardScriptProbe;
import forge.gui.card.CardScriptInfo.Source;
import forge.gui.card.CardScriptParser;
import forge.gui.framework.FScreen;
import forge.gui.framework.ICDoc;
import forge.item.PaperCard;
import forge.itemmanager.CardManager;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.workshop.menus.WorkshopFileMenu;
import forge.screens.workshop.views.VCardDesigner;
import forge.screens.workshop.views.VCardScript;
import forge.screens.workshop.views.VWorkshopCatalog;
import forge.toolbox.FOptionPane;
import forge.util.ItemPool;
import forge.util.Localizer;

/**
 * Controls the "card script" panel in the workshop UI.
 *
 * <br><br><i>(C at beginning of class name denotes a control class.)</i>
 *
 */
public enum CCardScript implements ICDoc {
    SINGLETON_INSTANCE;

    private PaperCard currentCard;
    private CardScriptInfo currentScriptInfo;
    private boolean isTextDirty;
    private boolean switchInProgress;
    private boolean refreshing;

    CCardScript() {
        VCardScript.SINGLETON_INSTANCE.getTxtScript().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void removeUpdate(final DocumentEvent arg0) {
                updateDirtyFlag();
            }
            @Override
            public void insertUpdate(final DocumentEvent arg0) {
                updateDirtyFlag();
            }
            @Override
            public void changedUpdate(final DocumentEvent arg0) {
                //Plain text components do not fire these events
            }
        });
        //No focus listener on the text pane: the one upstream had called refresh() on every permanent focus
        //loss, and a click on the catalog table moves focus synchronously BEFORE the selection changes, so
        //the edit was overwritten with the saved text (clearing the dirty flag) and the switch-away prompt
        //in showCard never fired for the mouse. The dirty flag and canSwitchAway() are the whole contract.
    }

    private void updateDirtyFlag() {
        final boolean isTextNowDirty = !refreshing && currentScriptInfo != null && !VCardScript.SINGLETON_INSTANCE.getTxtScript().getText().equals(currentScriptInfo.getText());
        if (isTextDirty == isTextNowDirty) { return; }
        isTextDirty = isTextNowDirty;
        VCardDesigner.SINGLETON_INSTANCE.getBtnSaveCard().setEnabled(isTextNowDirty);
        VCardScript.SINGLETON_INSTANCE.getTabLabel().setText((isTextNowDirty ? "*" : "") + Localizer.getInstance().getMessage("lblCardScript"));
        WorkshopFileMenu.updateSaveEnabled();
    }

    public PaperCard getCurrentCard() {
        return currentCard;
    }

    public CardScriptInfo getCurrentScriptInfo() {
        return currentScriptInfo;
    }

    public void showCard(final PaperCard card) {
        if (currentCard == card || switchInProgress) { return; }

        if (!canSwitchAway(true)) { //ensure current card saved before changing to a different card
            VWorkshopCatalog.SINGLETON_INSTANCE.getCardManager().setSelectedItem(currentCard); //return selection to current card //TODO: fix so clicking away again doesn't cause weird selection problems
            return;
        }

        final CardManager catalog = VWorkshopCatalog.SINGLETON_INSTANCE.getCardManager();
        if (card != null && catalog.getSelectedItem() != card) {
            //the Save option above renamed the previous card and moved the selection (and the picture) to it:
            //put both back on the card the user clicked, or three panels would show two different cards
            switchInProgress = true;
            try {
                catalog.setSelectedItem(card); //the selection listener re-shows the picture and returns here early
            } finally {
                switchInProgress = false;
            }
        }
        currentCard = card;
        currentScriptInfo = card != null ? resolveScript(card) : null;
        refresh();
    }

    /** Forgets the current card without offering to save: it is being deleted, so there is nothing to keep. */
    public void discardCard() {
        currentCard = null;
        currentScriptInfo = null;
        refresh();
    }

    /** Drops the cached script for the current card and reads it again from disk (after a revert or an external change). */
    public void reloadCurrent() {
        if (currentScriptInfo != null) {
            CardScriptInfo.forget(currentScriptInfo.getStem());
        }
        currentScriptInfo = currentCard != null ? resolveScript(currentCard) : null;
        refresh();
    }

    private static CardScriptInfo resolveScript(final PaperCard card) {
        String stem = card.getRules().getNormalizedName();
        if (StringUtils.isBlank(stem)) {
            stem = CardScriptInfo.toFileStem(card.getName());
        }
        if (StringUtils.isBlank(stem)) {
            return null;
        }
        return CardScriptInfo.getScriptFor(stem, card.getName());
    }

    public void refresh() {
        if (refreshing) { return; }
        refreshing = true;
        final JTextPane txtScript = VCardScript.SINGLETON_INSTANCE.getTxtScript();
        txtScript.setText(currentScriptInfo != null ? currentScriptInfo.getText() : "");
        txtScript.setEditable(currentScriptInfo != null && currentScriptInfo.canEdit());
        txtScript.setCaretPosition(0); //keep scrolled to top

        final StyledDocument doc = VCardScript.SINGLETON_INSTANCE.getDoc();
        final Style error = VCardScript.SINGLETON_INSTANCE.getErrorStyle();
        final Style empty = VCardScript.SINGLETON_INSTANCE.getEmptyStyle();
        doc.setCharacterAttributes(0, 9999, empty, true);
        if (FModel.getPreferences().getPrefBoolean(FPref.DEV_WORKSHOP_SYNTAX) && currentScriptInfo != null) {
            for (final Entry<Integer, Integer> region : new CardScriptParser(currentScriptInfo.getText()).getErrorRegions().entrySet()) {
                doc.setCharacterAttributes(region.getKey(), region.getValue(), error, true);
            }
        }
        refreshing = false;
        CCardDesigner.SINGLETON_INSTANCE.refreshFor(currentCard, currentScriptInfo);
    }

    public boolean hasChanges() {
        return currentScriptInfo != null && isTextDirty;
    }

    private static final ImmutableList<String> switchAwayOptions = ImmutableList.of(
        Localizer.getInstance().getMessage("lblSave"),
        Localizer.getInstance().getMessage("lblDontSave"),
        Localizer.getInstance().getMessage("lblCancel")
    );
    public boolean canSwitchAway(final boolean isCardChanging) {
        if (switchInProgress) { return false; }
        if (!hasChanges()) { return true; }

        switchInProgress = true;
        Singletons.getControl().ensureScreenActive(FScreen.WORKSHOP_SCREEN); //ensure Workshop is active before showing dialog
        final int choice = FOptionPane.showOptionDialog(
                Localizer.getInstance().getMessage("lblSaveChangesToDestConfirm", currentCard.toString()),
                Localizer.getInstance().getMessage("lblSaveChangesConfirm"),
                FOptionPane.QUESTION_ICON,
                switchAwayOptions);
        switchInProgress = false;

        if (choice == -1 || choice == 2) { return false; }

        if (choice == 0 && !saveChanges()) { return false; }

        if (!isCardChanging) {
            refresh(); //refresh if current card isn't changing to restore script text from file
        }
        return true;
    }

    private static String msg(final String key, final Object... args) {
        return Localizer.getInstance().getMessage(key, args);
    }

    private static String rootMessage(final Throwable ex) {
        final String root = ExceptionUtils.getRootCauseMessage(ex);
        return StringUtils.isBlank(root) ? String.valueOf(ex) : root;
    }

    /** True when either database already knows a card by this name, including filtered stock cards and alternate names. */
    public static boolean isNameTaken(final String name) {
        final StaticData db = FModel.getMagicDb();
        return db.getCommonCards().contains(name) || db.getVariantCards().contains(name)
                || db.getCommonCards().getRules(name, true) != null || db.getVariantCards().getRules(name, true) != null;
    }

    /**
     * Shows the refusal and returns true while any match is running: a hosted network match (the
     * card database is shared with every connected player, the same guard the developer-mode
     * checkbox applies) or a local one (every live Card reads the CardRules object that a save
     * reinitializes in place, so a card the game builds later - a copy, a cascade, a wish - would be
     * built from the new text while the ones already in play keep the old abilities), and while an
     * Export Set... is writing its archive (the walk over custom/cards and the picture folders runs on
     * a background thread: a file written now is packed torn, pre-edit or not at all).
     */
    public static boolean refuseIfNetworkMatchActive() {
        if (FServerManager.getInstance().isMatchActive() || !Singletons.getControl().getCurrentMatches().isEmpty()) {
            FOptionPane.showErrorDialog(msg("lblWorkshopSaveRefusedNetworkMatch"));
            return true;
        }
        if (CCardDesigner.SINGLETON_INSTANCE.isExporting()) {
            FOptionPane.showErrorDialog(msg("lblWorkshopRefusedExporting"));
            return true;
        }
        return false;
    }

    /**
     * Refreshes every printing of this name after its rules were re-read in place: the cached image
     * keys (the alt key embeds the back face's name, which the saved script may have changed) and the
     * cached UI Card, so a stale Card under an equal PaperCard key from an earlier delete/rename is gone.
     */
    static void refreshCachedCards(final CardDb cardDb, final String name) {
        for (final PaperCard printing : cardDb.getAllCards(name)) {
            printing.resetImageKeys();
            Card.updateCard(printing);
        }
    }

    public boolean saveChanges() {
        if (!hasChanges()) { return true; } //not need if text hasn't been changed
        if (refuseIfNetworkMatchActive()) { return false; }

        final String text = VCardScript.SINGLETON_INSTANCE.getTxtScript().getText();
        final CardScriptInfo info = currentScriptInfo;
        final String oldName = currentCard.getName();

        // 1. syntax highlighter regions: warn, allow saving anyway (the parser is still marked as in testing).
        //    Paint the regions first so the dialog's "highlighted" is true even with the syntax pref off.
        Map<Integer, Integer> syntaxErrors;
        try {
            syntaxErrors = new CardScriptParser(text).getErrorRegions();
        } catch (final RuntimeException ex) {
            //a parse failure in the heuristic highlighter is one warning region, never an uncaught exception on Save
            syntaxErrors = Map.of(0, Math.max(1, text.length()));
        }
        if (!syntaxErrors.isEmpty()) {
            final StyledDocument doc = VCardScript.SINGLETON_INSTANCE.getDoc();
            final Style error = VCardScript.SINGLETON_INSTANCE.getErrorStyle();
            for (final Entry<Integer, Integer> region : syntaxErrors.entrySet()) {
                doc.setCharacterAttributes(region.getKey(), region.getValue(), error, true);
            }
            if (!FOptionPane.showConfirmDialog(msg("lblWorkshopSyntaxWarn"), msg("lblSaveAndApplyCardChanges"),
                    msg("lblWorkshopSaveAnyway"), msg("lblCancel"), false)) {
                return false;
            }
        }

        // 2. the rules reader must accept it, and every face must be in the script itself
        CardRules newRules;
        try {
            newRules = CardScriptProbe.parseRules(text, info.getStem());
        } catch (final Exception ex) {
            FOptionPane.showErrorDialog(msg("lblWorkshopRulesRefused", rootMessage(ex)));
            return false;
        }
        if (CardScriptProbe.usesCopyFaceFrom(text)) {
            //the borrowed face is only supplied by the database at load time, so neither the probe nor the name is available here
            FOptionPane.showErrorDialog(msg("lblWorkshopCopyFaceRefused"));
            return false;
        }
        if (!CardScriptProbe.hasAllFaces(newRules) || StringUtils.isBlank(newRules.getName())) {
            FOptionPane.showErrorDialog(msg("lblWorkshopRulesRefused", msg("lblWorkshopFaceMissing")));
            return false;
        }

        // 3. a real Card must build from it (this is what catches unknown ApiTypes and missing SVars)
        try {
            CardScriptProbe.probeCard(newRules, currentCard.getEdition(), currentCard.getRarity());
        } catch (final Exception | AssertionError | StackOverflowError ex) {
            FOptionPane.showErrorDialog(msg("lblWorkshopCardRefused", rootMessage(ex)));
            return false;
        }

        // 4. the card must stay in the database it lives in
        if (newRules.isVariant() != currentCard.getRules().isVariant()) {
            FOptionPane.showErrorDialog(msg("lblWorkshopVariantSwitchRefused"));
            return false;
        }

        // 5. a rename creates a new custom card; the original is left as it was
        final String newName = newRules.getName();
        final boolean renamed = !newName.equals(oldName);
        CardScriptInfo target = info;
        if (!renamed && info.getSource() == Source.STOCK_ZIP
                && !FModel.getPreferences().getPrefBoolean(FPref.ALLOW_CUSTOM_CARDS_IN_DECKS_CONFORMANCE)
                && !FOptionPane.showConfirmDialog(msg("lblWorkshopOverrideConfirm", oldName), msg("lblSaveAndApplyCardChanges"))) {
            //the first override of a stock card changes how the card loads (custom => deck conformance, no art fetch): say so once
            return false;
        }
        if (renamed) {
            final String newStem = CardScriptInfo.toFileStem(newName);
            if (newStem.isEmpty() || newName.indexOf(CardDb.NameSetSeparator) >= 0) {
                FOptionPane.showErrorDialog(msg("lblWorkshopNameInvalid"));
                return false;
            }
            if (isNameTaken(newName)) {
                FOptionPane.showErrorDialog(msg("lblWorkshopNameTaken", newName));
                return false;
            }
            if (CardScriptInfo.readStockScript(newStem) != null) {
                //"Foo-Bar" and "Foo Bar" share the stem foo_bar: the file would load as an override of the stock card
                FOptionPane.showErrorDialog(msg("lblWorkshopStemTaken", newStem));
                return false;
            }
            target = CardScriptInfo.customTargetFor(newStem);
            //the reader keys loaded scripts by stem, so a same-stem file ANYWHERE under custom/cards would collide at the next start
            final File sameStem = target.getFile().exists() ? target.getFile()
                    : CardScriptInfo.findCustomFile(new File(ForgeConstants.USER_CUSTOM_CARDS_DIR), newStem + ".txt");
            if (sameStem != null) {
                FOptionPane.showErrorDialog(msg("lblWorkshopFileExists", sameStem.getPath()));
                return false;
            }
            //a custom card has nothing to leave behind: its file and database entry go with the rename
            final String renameConfirmKey = info.getSource() == Source.CUSTOM_CARD ? "lblWorkshopRenameConfirmRemoves" : "lblWorkshopRenameConfirm";
            if (!FOptionPane.showConfirmDialog(msg(renameConfirmKey, oldName, newName), msg("lblSaveAndApplyCardChanges"))) {
                return false;
            }
            newRules = CardScriptProbe.parseRules(text, newStem);
            newRules.setCustom();
            newRules.setPath(target.getFile().getPath());
        } else {
            //a loose res/cardsfolder file keeps its path and stays a stock card; everything else is a user file and loads as custom
            newRules.setPath(info.getFile().getPath());
            if (info.getSource() != Source.STOCK_FILE) {
                newRules.setCustom();
            }
        }

        // 6. write
        if (!target.trySetText(text)) {
            FOptionPane.showErrorDialog(msg("lblWorkshopWriteFailed", String.valueOf(target.getLastError())));
            return false;
        }

        // 7. apply in memory
        final CardDb cardDb = newRules.isVariant() ? FModel.getMagicDb().getVariantCards() : FModel.getMagicDb().getCommonCards();
        final CardManager catalog = VWorkshopCatalog.SINGLETON_INSTANCE.getCardManager();
        if (!renamed) {
            cardDb.getEditor().putCard(newRules); //reinitializes the existing rules object in place
            refreshCachedCards(cardDb, oldName);
        } else {
            switchInProgress = true; //catalog mutations fire selection events; state is set by hand below
            try {
                final CardRules oldRules = currentCard.getRules();
                cardDb.getEditor().putCard(newRules);
                final PaperCard renamedCard = cardDb.getCard(newName);
                if (renamedCard == null) {
                    target.deleteFile(); //nothing was registered: leave no orphan file behind, and the original card untouched
                    FOptionPane.showErrorDialog(msg("lblWorkshopRulesRefused", newName));
                    return false;
                }
                if (info.getSource() == Source.CUSTOM_CARD) {
                    //the new card is in: only now take the old one out, its file with it, and bring back a stock card
                    //it shadowed by name, exactly as Delete Custom Card does
                    final List<PaperCard> gone = cardDb.getEditor().removeCard(oldRules);
                    catalog.removeItems(ItemPool.createFrom(gone, PaperCard.class));
                    if (!info.deleteFile()) {
                        FOptionPane.showErrorDialog(msg("lblWorkshopWriteFailed", String.valueOf(info.getLastError())));
                    }
                    CardScriptInfo.forget(info.getStem());
                    CCardDesigner.SINGLETON_INSTANCE.restoreShadowedStock(oldName, info.getStem());
                    cardDb.getEditor().reindexFaces(newRules); //an unchanged back face's alt-name entry went with the original
                }
                refreshCachedCards(cardDb, newName);
                CardScriptInfo.register(target.getStem(), target);
                currentCard = renamedCard;
                catalog.addItem(renamedCard, 1);
                if (catalog.getSelectedItem() != renamedCard) {
                    catalog.resetFilters();
                    catalog.setSelectedItem(renamedCard);
                }
                catalog.scrollSelectionIntoView();
            } finally {
                switchInProgress = false;
            }
        }

        CardScriptInfo.register(target.getStem(), target);
        currentScriptInfo = target;
        updateDirtyFlag();

        catalog.repaint();
        VWorkshopCatalog.SINGLETON_INSTANCE.getCDetailPicture().showItem(currentCard);
        refresh();
        return true;
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
