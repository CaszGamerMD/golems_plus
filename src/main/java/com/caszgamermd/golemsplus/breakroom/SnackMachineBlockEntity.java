package com.caszgamermd.golemsplus.breakroom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** Persistent, player-restocked food stock for a Break Room vending machine. */
public final class SnackMachineBlockEntity extends BlockEntity implements Container {
    public static final int SLOTS = 9;
    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public SnackMachineBlockEntity(BlockPos pos, BlockState state) {
        super(BreakRoomContent.SNACK_MACHINE_ENTITY, pos, state);
    }

    public static boolean isSnack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    /** Inserts matching food first, then uses empty slots; shrinks the caller's stack. */
    public int insert(ItemStack held) {
        if (!isSnack(held)) return 0;
        int before = held.getCount();
        for (int slot = 0; slot < SLOTS && !held.isEmpty(); slot++) {
            ItemStack stored = items.get(slot);
            if (stored.isEmpty() || !ItemStack.isSameItemSameComponents(stored, held)) continue;
            int moved = Math.min(Math.max(0, stored.getMaxStackSize() - stored.getCount()),
                    held.getCount());
            if (moved > 0) { stored.grow(moved); held.shrink(moved); }
        }
        for (int slot = 0; slot < SLOTS && !held.isEmpty(); slot++) {
            if (!items.get(slot).isEmpty()) continue;
            int moved = Math.min(held.getMaxStackSize(), held.getCount());
            ItemStack stored = held.copy();
            stored.setCount(moved);
            items.set(slot, stored);
            held.shrink(moved);
        }
        int inserted = before - held.getCount();
        if (inserted > 0) sync();
        return inserted;
    }

    public boolean hasSnack() {
        for (ItemStack stack : items) if (!stack.isEmpty()) return true;
        return false;
    }

    public int totalItems() {
        int count = 0;
        for (ItemStack stack : items) count += stack.getCount();
        return count;
    }

    /** Removes exactly one real item, preserving its data components. */
    public ItemStack takeSnack() {
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stored = items.get(slot);
            if (stored.isEmpty()) continue;
            ItemStack snack = stored.split(1);
            if (stored.isEmpty()) items.set(slot, ItemStack.EMPTY);
            sync();
            return snack;
        }
        return ItemStack.EMPTY;
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide())
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override public int getContainerSize() { return SLOTS; }
    @Override public boolean isEmpty() { return !hasSnack(); }
    @Override public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SLOTS ? items.get(slot) : ItemStack.EMPTY;
    }
    @Override public ItemStack removeItem(int slot, int count) {
        if (slot < 0 || slot >= SLOTS || count <= 0) return ItemStack.EMPTY;
        ItemStack removed = ContainerHelper.removeItem(items, slot, count);
        if (!removed.isEmpty()) sync();
        return removed;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= SLOTS) return ItemStack.EMPTY;
        ItemStack removed = items.get(slot);
        items.set(slot, ItemStack.EMPTY);
        if (!removed.isEmpty()) sync();
        return removed;
    }
    @Override public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOTS || (!stack.isEmpty() && !isSnack(stack))) return;
        ItemStack stored = stack.copy();
        if (!stored.isEmpty())
            stored.setCount(Math.min(stored.getCount(), stored.getMaxStackSize()));
        items.set(slot, stored);
        sync();
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 0 && slot < SLOTS && isSnack(stack);
    }
    @Override public void clearContent() {
        for (int slot = 0; slot < SLOTS; slot++) items.set(slot, ItemStack.EMPTY);
        sync();
    }
    @Override public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player, 8.0f);
    }
    @Override protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = items.get(slot);
            if (!stack.isEmpty()) out.store("snack_slot_" + slot, ItemStack.CODEC, stack);
        }
    }
    @Override protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        for (int slot = 0; slot < SLOTS; slot++)
            items.set(slot, in.read("snack_slot_" + slot, ItemStack.CODEC)
                    .orElse(ItemStack.EMPTY));
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }
    @Override public @Nullable ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
