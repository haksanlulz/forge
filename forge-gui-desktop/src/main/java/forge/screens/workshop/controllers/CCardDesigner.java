package forge.screens.workshop.controllers;

import forge.gui.card.CardScriptInfo;
import forge.gui.framework.ICDoc;
import forge.item.PaperCard;
import forge.screens.workshop.views.VCardDesigner;
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

    CCardDesigner() {
        VCardDesigner.SINGLETON_INSTANCE.getBtnSaveCard().setCommand((Runnable) CCardScript.SINGLETON_INSTANCE::saveChanges);
    }

    private static String msg(final String key, final Object... args) {
        return Localizer.getInstance().getMessage(key, args);
    }

    /**
     * Updates the status line for the card the script pane is showing: where its script comes from
     * and what a save does to it. Called by {@link CCardScript#refresh()} on every card change.
     */
    public void refreshFor(final PaperCard pc, final CardScriptInfo info) {
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
        final String zipProblem = CardScriptInfo.getZipProblem();
        if (zipProblem != null) {
            status.append('\n').append(msg("lblWorkshopStatusZipProblem", zipProblem));
        }
        VCardDesigner.SINGLETON_INSTANCE.getTxtStatus().setText(status.toString());
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
