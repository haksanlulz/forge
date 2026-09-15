package forge.screens.workshop.views;

import javax.swing.JPanel;

import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.gui.framework.IVDoc;
import forge.localinstance.skin.FSkinProp;
import forge.screens.workshop.controllers.CCardDesigner;
import forge.toolbox.FLabel;
import forge.toolbox.FSkin;
import forge.toolbox.FTextArea;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

/**
 * Assembles Swing components of workshop card designer tab.
 *
 * <br><br><i>(V at beginning of class name denotes a view class.)</i>
 */
public enum VCardDesigner implements IVDoc<CCardDesigner> {
    /** */
    SINGLETON_INSTANCE;

    // Fields used with interface IVDoc
    private DragCell parentCell;
    private final DragTab tab = new DragTab(Localizer.getInstance().getMessage("lblCardDesigner"));

    private final FTextArea txtStatus = new FTextArea();

    private final FLabel btnNewCard = button("lblWorkshopNewCard", FSkinProp.ICO_NEW);
    private final FLabel btnSetArt = button("lblWorkshopSetArt", FSkinProp.ICO_OPEN);
    private final FLabel btnSetBackArt = button("lblWorkshopSetBackArt", FSkinProp.ICO_OPEN);

    private final FLabel btnSaveCard = new FLabel.Builder()
            .opaque(true).hoverable(true)
            .text(Localizer.getInstance().getMessage("lblSaveAndApplyCardChanges"))
            .icon(FSkin.getIcon(FSkinProp.ICO_SAVE))
            .enabled(false) //disabled by default until card changes made
            .build();

    private static FLabel button(final String key, final FSkinProp icon) {
        return new FLabel.Builder()
                .opaque(true).hoverable(true)
                .text(Localizer.getInstance().getMessage(key))
                .icon(FSkin.getIcon(icon))
                .build();
    }

    //========== Constructor
    VCardDesigner() {
        txtStatus.setRows(8); //a wrapped text area otherwise reports a one-line preferred height
        btnSetArt.setEnabled(false);
        btnSetBackArt.setVisible(false);
    }

    public FLabel getBtnSaveCard() {
        return btnSaveCard;
    }

    /** Where the current script comes from and what a save does to it. */
    public FTextArea getTxtStatus() {
        return txtStatus;
    }

    public FLabel getBtnNewCard() {
        return btnNewCard;
    }

    public FLabel getBtnSetArt() {
        return btnSetArt;
    }

    public FLabel getBtnSetBackArt() {
        return btnSetBackArt;
    }

    //========== Overridden methods

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#getDocumentID()
     */
    @Override
    public EDocID getDocumentID() {
        return EDocID.WORKSHOP_CARDDESIGNER;
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#getTabLabel()
     */
    @Override
    public DragTab getTabLabel() {
        return tab;
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#getLayoutControl()
     */
    @Override
    public CCardDesigner getLayoutControl() {
        return CCardDesigner.SINGLETON_INSTANCE;
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#setParentCell(forge.gui.framework.DragCell)
     */
    @Override
    public void setParentCell(final DragCell cell0) {
        this.parentCell = cell0;
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#getParentCell()
     */
    @Override
    public DragCell getParentCell() {
        return this.parentCell;
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#populate()
     */
    @Override
    public void populate() {
        final JPanel body = parentCell.getBody();
        body.setLayout(new MigLayout("insets 6, gap 4, wrap 1, fillx, hidemode 3"));
        body.add(txtStatus, "growx, wmin 10"); //wmin: a long path must wrap, not widen the cell
        body.add(btnNewCard, "growx, h 30!");
        body.add(btnSetArt, "growx, h 30!");
        body.add(btnSetBackArt, "growx, h 30!");
        body.add(btnSaveCard, "growx, h 30!, pushy, aligny bottom");
    }
}
