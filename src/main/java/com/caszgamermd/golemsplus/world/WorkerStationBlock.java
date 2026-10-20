package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.worker.WorkerPhase;
import com.caszgamermd.golemsplus.worker.WorkerSpecialty;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Prototype specialist workstation. Empty-hand click inspects its saved
 * status; crouch-click pauses/resumes. No automatic work is run from here.
 */
public final class WorkerStationBlock extends Block implements EntityBlock {
    private final MapCodec<WorkerStationBlock> codec = MapCodec.unit(this);
    private final WorkerSpecialty specialty;

    public WorkerStationBlock(WorkerSpecialty specialty, BlockBehaviour.Properties properties) {
        super(properties);
        this.specialty = specialty;
    }
    public WorkerSpecialty specialty() { return specialty; }
    @Override protected MapCodec<? extends Block> codec() { return codec; }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WorkerStationBlockEntity(pos, state);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level,
            BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof WorkerStationBlockEntity station))
            return InteractionResult.PASS;
        if (!station.claimIfUnowned(player.getUUID())
                && !station.canConfigure(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Station belongs to another player."));
            return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown())
            station.setPhase(station.phase() == WorkerPhase.PAUSED
                    ? WorkerPhase.IDLE : WorkerPhase.PAUSED);
        player.sendSystemMessage(Component.literal(
                specialty.name() + " station: " + station.phase().name()
                        + " (worker automation not installed yet)"));
        return InteractionResult.SUCCESS;
    }
}
