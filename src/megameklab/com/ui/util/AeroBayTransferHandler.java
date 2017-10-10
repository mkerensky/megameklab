/*
 * MegaMekLab - Copyright (C) 2017 - The MegaMek Team
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 */
package megameklab.com.ui.util;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.TransferHandler;

import megamek.common.AmmoType;
import megamek.common.Entity;
import megamek.common.LocationFullException;
import megamek.common.Mounted;
import megamek.common.WeaponType;
import megamek.common.weapons.bayweapons.BayWeapon;
import megameklab.com.MegaMekLab;
import megameklab.com.ui.EntitySource;
import megameklab.com.util.CriticalTableModel;
import megameklab.com.util.UnitUtil;

/**
 * Handles drag-and-drop for aerospace units that use weapon bays. Most of the work of adding, removing,
 * and changing equipment locations is done by the JTree for the weapon arc.
 * 
 * @author Neoancient
 *
 */
public class AeroBayTransferHandler extends TransferHandler {
    
    /**
     * 
     */
    private static final long serialVersionUID = 2534394664060762469L;
    
    private EntitySource eSource;
    
    /* Aliases for local usage.
     * When moving ammo, the default is to move a single ton (or whatever the atomic value is) at a time.
     * Holding the ctrl key will move all ammo of that type in that location.
     */
    public static final int AMMO_SINGLE = MOVE;
    public static final int AMMO_ALL    = COPY;
    
    public AeroBayTransferHandler(EntitySource eSource) {
        this.eSource = eSource;
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!support.isDrop()) {
            return false;
        }
        
        // Fields are equipmentNum, node child index, bay child index
        String[] source = null;
        int eqNum = -1;
        
        try {
            source = ((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor)).split(",");
            eqNum = Integer.parseInt(source[0]);
        } catch (Exception ex) {
            MegaMekLab.getLogger().log(AeroBayTransferHandler.class,
                    "importData(TransferSupport)", //$NON-NLS-1$
                    ex);
            return false;
        }
        if (eqNum < 0) {
            return false;
        }
        final Mounted mount = eSource.getEntity().getEquipment(eqNum);
        if (null == mount) {
            return false;
        }

        if ((support.getComponent() instanceof BayWeaponCriticalTree)) {
            final BayWeaponCriticalTree tree = (BayWeaponCriticalTree) support.getComponent();
            // If it's a bay we move it and its entire contents. Otherwise we find the bay that was
            // dropped on and add it there. A weapon dropped on an illegal bay will create a new one
            // and non-bay equipment will be added at the top level regardless of the drop location.
            // Non-weapon bay equipment cannot be dropped on an illegal bay.
            if (mount.getType() instanceof BayWeapon) {
                tree.addBay(mount);
            } else if ((mount.getType() instanceof AmmoType) && (support.getUserDropAction() == AMMO_SINGLE)) {
                // Default action for ammo is to move a single slot. Holding the ctrl key when dropping
                // will create a AMMO_ALL command, which adds all the ammo of the type.
                tree.addAmmoSlot(mount, ((JTree.DropLocation) support.getDropLocation()).getPath());
            } else {
                tree.addToArc(mount, ((JTree.DropLocation) support.getDropLocation()).getPath());
            }
        } else {
            // Target is unallocated bay table.
            if (mount.getType() instanceof AmmoType) {
                AmmoType at = (AmmoType)mount.getType();
                // Check whether we are moving one of multiple slots. 
                if ((support.getUserDropAction() == AMMO_SINGLE) && (mount.getUsableShotsLeft() > at.getShots())) {
                    mount.setShotsLeft(mount.getUsableShotsLeft() - at.getShots());
                }
                Mounted addMount = UnitUtil.findUnallocatedAmmo(eSource.getEntity(), at);
                if (null != addMount) {
                    if (support.getUserDropAction() == AMMO_SINGLE) {
                        addMount.setShotsLeft(addMount.getUsableShotsLeft() + at.getShots());
                    } else {
                        addMount.setShotsLeft(addMount.getUsableShotsLeft() + mount.getUsableShotsLeft());
                    }
                } else {
                    try {
                        Mounted m = eSource.getEntity().addEquipment(at, Entity.LOC_NONE);
                        if (support.getUserDropAction() == AMMO_ALL) {
                            m.setShotsLeft(mount.getUsableShotsLeft());
                        }
                    } catch (LocationFullException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            } else {
                UnitUtil.removeCriticals(eSource.getEntity(), mount);
                UnitUtil.changeMountStatus(eSource.getEntity(), mount, Entity.LOC_NONE, Entity.LOC_NONE, false);
                if ((mount.getType() instanceof WeaponType) && (mount.getLinkedBy() != null)) {
                    UnitUtil.removeCriticals(eSource.getEntity(), mount.getLinkedBy());
                    UnitUtil.changeMountStatus(eSource.getEntity(), mount.getLinkedBy(),
                            Entity.LOC_NONE, Entity.LOC_NONE, false);
                    mount.getLinkedBy().setLinked(null);
                    mount.setLinkedBy(null);
                }
                UnitUtil.compactCriticals(eSource.getEntity());
            }
        }
        return true;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        // Check for String flavor
        if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return false;
        }
        // check if the dragged mounted should be transferrable
        Mounted mounted = null;
        try {
            String str = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
            if (str.contains(",")) {
                str = str.substring(0, str.indexOf(","));
            }
            mounted = eSource.getEntity().getEquipment(Integer.parseInt(str));
        } catch (NumberFormatException e) {
            e.printStackTrace();
        } catch (UnsupportedFlavorException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // not actually dragged a Mounted? not transferable
        if (mounted == null) {
            return false;
        }
        
        // If allocating to an arc, make sure the bay can receive it
        if (support.getComponent() instanceof BayWeaponCriticalTree) {
            return ((BayWeaponCriticalTree)support.getComponent())
                    .isValidDropLocation((JTree.DropLocation)support.getDropLocation(), mounted);
        }
        return true;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        if (c instanceof BayWeaponCriticalTree) {
            return new StringSelection(((BayWeaponCriticalTree)c).encodeSelection());
        } else {
            JTable table = (JTable) c;
            Mounted mount = (Mounted) ((CriticalTableModel) table.getModel()).getValueAt(table.getSelectedRow(), CriticalTableModel.EQUIPMENT);
            return new StringSelection(Integer.toString(eSource.getEntity().getEquipmentNum(mount)));
        }
    }

    @Override
    public int getSourceActions(JComponent c) {
        return COPY_OR_MOVE;
    }

    @Override
    protected void exportDone(JComponent source, Transferable data, int action) {
        if (((action == MOVE) || (action == COPY)) && (source instanceof BayWeaponCriticalTree)) {
            try {
                ((BayWeaponCriticalTree)source).removeExported((String)data.getTransferData(DataFlavor.stringFlavor),
                        action);
            } catch (Exception ex) {
                MegaMekLab.getLogger().log(AeroBayTransferHandler.class,
                        "exportDone(JComponent,Transferable,action", //$NON-NLS-1$
                        ex);
            }
        }
    }
    
    

}
