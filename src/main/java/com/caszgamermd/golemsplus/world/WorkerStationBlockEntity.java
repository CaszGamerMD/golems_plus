package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.worker.WorkerPhase;
import com.caszgamermd.golemsplus.warehouse.WarehouseAssignment;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Persistent owner, pause state, order identity and optional warehouse
 * destination. A dimension+position link is NOT an access grant; a later
 * adapter must independently authorize each material reservation.
 */
public final class WorkerStationBlockEntity extends BlockEntity {
    private UUID owner;
    private UUID activeOrder;
    private WorkerPhase phase = WorkerPhase.IDLE;
    private WarehouseAssignment warehouse;

    public WorkerStationBlockEntity(BlockPos pos, BlockState state) {
        super(WorksiteContent.STATION_ENTITY, pos, state);
    }

    public UUID owner() { return owner; }
    public UUID activeOrder() { return activeOrder; }
    public WorkerPhase phase() { return phase; }
    public WarehouseAssignment warehouse() { return warehouse; }
    public boolean canConfigure(UUID actor) { return actor != null && actor.equals(owner); }
    public boolean claimIfUnowned(UUID actor) {
        if (owner != null) return owner.equals(actor);
        if (actor == null) return false;
        owner = actor;
        sync();
        return true;
    }
    public boolean setWarehouse(UUID actor, WarehouseAssignment selection) {
        if (!canConfigure(actor)) return false;
        warehouse = selection;
        sync();
        return true;
    }
    public boolean beginOrder(UUID actor, UUID newOrder) {
        if (!canConfigure(actor) || newOrder == null || activeOrder != null
                || phase == WorkerPhase.PAUSED) return false;
        activeOrder = newOrder;
        phase = WorkerPhase.PLANNING;
        sync();
        return true;
    }
    public void setPhase(WorkerPhase next) {
        if (next == null) throw new IllegalArgumentException("Missing phase");
        phase = next;
        sync();
    }
    public void clearOrder() {
        activeOrder = null;
        phase = WorkerPhase.IDLE;
        sync();
    }
    private void sync() {
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }
    @Override protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (owner != null) out.putString("owner", owner.toString());
        if (activeOrder != null) out.putString("active_order", activeOrder.toString());
        out.putString("phase", phase.name());
        if (warehouse != null) {
            out.putString("warehouse_dimension", warehouse.dimension());
            out.putInt("warehouse_x", warehouse.x());
            out.putInt("warehouse_y", warehouse.y());
            out.putInt("warehouse_z", warehouse.z());
        }
    }
    private static UUID readUuid(ValueInput in, String field) {
        try { return UUID.fromString(in.getStringOr(field, "")); }
        catch (IllegalArgumentException exception) { return null; }
    }
    @Override protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        owner = readUuid(in, "owner");
        activeOrder = readUuid(in, "active_order");
        try { phase = WorkerPhase.valueOf(in.getStringOr("phase", "IDLE")); }
        catch (IllegalArgumentException exception) { phase = WorkerPhase.BLOCKED; }
        String dimension = in.getStringOr("warehouse_dimension", "");
        warehouse = dimension.isBlank() ? null : new WarehouseAssignment(dimension,
                in.getIntOr("warehouse_x", 0), in.getIntOr("warehouse_y", 0),
                in.getIntOr("warehouse_z", 0));
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
