package com.caszgamermd.golemsplus.warehouse;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Optional Caszual Additions scanner interoperability using its existing
 * saved CUSTOM_DATA keys. There is no compile-time or server-side class
 * dependency on Caszual Additions.
 *
 * Only a Builder's Planning Table processes a scanner. The scanner itself
 * is never modified, consumed, unlinked or sent on a pallet operation.
 */
public final class BuilderScannerLink {
    private static final String SCANNER_ID = "caszual_additions:storage_scanner";
    private static final String COMPUTER_ID = "caszual_additions:rack_computer";

    private BuilderScannerLink() {}

    public static boolean isWarehouseScanner(ItemStack held) {
        return !held.isEmpty()
                && SCANNER_ID.equals(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
    }

    /**
     * Called on the server after the block has consumed the interaction.
     * Invalid scanners cannot erase or replace an existing warehouse link.
     */
    public static void link(ServerLevel level, BlockPos tablePos, Player player,
            WorkerStationBlockEntity table, ItemStack held) {
        if (!table.isBuilder()) return;

        CustomData data = held.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
        boolean coordinatesPresent = tag.contains("warehouse_x")
                && tag.contains("warehouse_y") && tag.contains("warehouse_z");
        String savedDimension = tag.getStringOr("warehouse_dimension", "");
        int x = tag.getIntOr("warehouse_x", 0);
        int y = tag.getIntOr("warehouse_y", 0);
        int z = tag.getIntOr("warehouse_z", 0);

        BlockPos computer = new BlockPos(x, y, z);
        boolean loaded = coordinatesPresent && level.hasChunkAt(computer);
        boolean validComputer = loaded && COMPUTER_ID.equals(
                BuiltInRegistries.BLOCK.getKey(
                        level.getBlockState(computer).getBlock()).toString());
        var evaluation = WarehouseLinkValidator.validate(
                savedDimension,
                level.dimension().toString(),
                level.dimension().identifier().toString(),
                coordinatesPresent, x, y, z, loaded, validComputer);

        switch (evaluation.status()) {
            case NOT_LINKED -> {
                tell(player, "Scanner is not linked. Sneak-right-click a Caszual "
                        + "Rack Computer with the scanner first.");
                return;
            }
            case WRONG_DIMENSION -> {
                tell(player, "Scanner links to a warehouse in another dimension. "
                        + "Link the scanner to a Rack Computer in this dimension.");
                return;
            }
            case COMPUTER_MISSING -> {
                tell(player, "The scanner's linked location is loaded, but "
                        + "there is no Rack Computer there. Relink the scanner.");
                return;
            }
            case LINKED -> { /* Continue after validation. */ }
        }

        // A scanner must never override somebody else's station settings.
        // First valid click may claim an unowned Builder station, matching
        // its ordinary empty-hand ownership workflow.
        if (!table.claimIfUnowned(player.getUUID())) {
            tell(player, "This Builder's Planning Table belongs to another player.");
            return;
        }
        if (!table.setWarehouse(player.getUUID(), evaluation.assignment())) {
            tell(player, "Unable to assign this warehouse to the Builder.");
            return;
        }
        tell(player, "Builder warehouse linked from scanner: "
                + x + ", " + y + ", " + z
                + (evaluation.computerVerified()
                    ? " (Rack Computer verified)."
                    : " (computer chunk unloaded; verification deferred)."));
        tell(player,"Warehouse auto-supply enabled. Start Build to request"
                +" materials; warehouse Golems pack shulkers at an output PTP,"
                +" then a Builder Golem carries them back to the Planning Table.");
        tell(player,"Warehouse needs Storage Golems, racks with stock,"
                +" an OUTPUT PTP, and an attached Shulker Station with empty boxes."
                +" Keep the warehouse and travel route loaded.");
    }

    private static void tell(Player player, String message) {
        player.sendSystemMessage(Component.literal("[Golems Plus] " + message));
    }
}
