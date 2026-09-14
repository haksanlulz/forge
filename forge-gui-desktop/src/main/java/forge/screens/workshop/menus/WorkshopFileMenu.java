package forge.screens.workshop.menus;

import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

import forge.localinstance.skin.FSkinProp;
import forge.menus.MenuUtil;
import forge.screens.workshop.controllers.CCardDesigner;
import forge.screens.workshop.controllers.CCardScript;
import forge.toolbox.FSkin.SkinnedMenuItem;
import forge.util.Localizer;

/**
 * Returns a JMenu containing options associated with current game.
 * <p>
 * Replicates options available in Dock tab.
 */
public final class WorkshopFileMenu {
    private WorkshopFileMenu() { }

    private static boolean showIcons;

    public static JMenu getMenu(boolean showMenuIcons) {
        showIcons = showMenuIcons;

        JMenu menu = new JMenu(Localizer.getInstance().getMessage("lblFile"));
        menu.setMnemonic(KeyEvent.VK_F);
        menu.add(getMenuItem_NewCard());
        menu.add(getMenuItem_AddArtVariant());
        menu.add(getMenuItem_SaveCard());
        menu.addSeparator();
        menu.add(getMenuItem_ExportSet());
        return menu;
    }

    private static JMenuItem getMenuItem_ExportSet() {
        SkinnedMenuItem menuItem = new SkinnedMenuItem(Localizer.getInstance().getMessage("lblWorkshopExportSet"));
        menuItem.setIcon(showIcons ? MenuUtil.getMenuIcon(FSkinProp.ICO_SAVEAS) : null);
        menuItem.addActionListener(e -> CCardDesigner.SINGLETON_INSTANCE.exportSet());
        return menuItem;
    }

    private static JMenuItem menuItem_SaveCard;
    private static JMenuItem menuItem_AddArtVariant;

    public static void updateSaveEnabled() {
        if (menuItem_SaveCard == null)
            getMenuItem_SaveCard();
        menuItem_SaveCard.setEnabled(CCardScript.SINGLETON_INSTANCE.hasChanges());
    }

    /** Add Art Variant... needs a selected printing; the Card Designer calls this whenever the selection changes. */
    public static void updateAddArtVariantEnabled() {
        if (menuItem_AddArtVariant == null)
            getMenuItem_AddArtVariant();
        menuItem_AddArtVariant.setEnabled(CCardScript.SINGLETON_INSTANCE.getCurrentCard() != null);
    }

    private static JMenuItem getMenuItem_NewCard() {
        SkinnedMenuItem menuItem = new SkinnedMenuItem(Localizer.getInstance().getMessage("lblWorkshopNewCard"));
        menuItem.setIcon(showIcons ? MenuUtil.getMenuIcon(FSkinProp.ICO_NEW) : null);
        menuItem.setAccelerator(MenuUtil.getAcceleratorKey(KeyEvent.VK_N));
        menuItem.addActionListener(e -> CCardDesigner.SINGLETON_INSTANCE.newCard());
        return menuItem;
    }

    private static JMenuItem getMenuItem_AddArtVariant() {
        SkinnedMenuItem menuItem = new SkinnedMenuItem(Localizer.getInstance().getMessage("lblWorkshopAddArtVariant"));
        menuItem.setIcon(showIcons ? MenuUtil.getMenuIcon(FSkinProp.ICO_PLUS) : null);
        menuItem.addActionListener(e -> CCardDesigner.SINGLETON_INSTANCE.addArtVariant());
        menuItem_AddArtVariant = menuItem;
        updateAddArtVariantEnabled();
        return menuItem;
    }

    private static JMenuItem getMenuItem_SaveCard() {
        SkinnedMenuItem menuItem = new SkinnedMenuItem(Localizer.getInstance().getMessage("lblSaveAndApplyCardChanges"));
        menuItem.setIcon(showIcons ? MenuUtil.getMenuIcon(FSkinProp.ICO_SAVE) : null);
        menuItem.setAccelerator(MenuUtil.getAcceleratorKey(KeyEvent.VK_S));
        menuItem.addActionListener(getSaveCardAction());
        menuItem_SaveCard = menuItem;
        updateSaveEnabled();
        return menuItem;
    }

    private static ActionListener getSaveCardAction() {
        return e -> CCardScript.SINGLETON_INSTANCE.saveChanges();
    }
}
