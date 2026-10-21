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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.Containers;
import com.caszgamermd.golemsplus.trader.TraderWorkService;
import com.caszgamermd.golemsplus.builder.BuilderBuildService;
import com.caszgamermd.golemsplus.beekeeper.BeekeeperWorkService;
import com.caszgamermd.golemsplus.cooking.ChefWorkService;
import com.caszgamermd.golemsplus.warehouse.BuilderScannerLink;
import net.minecraft.world.InteractionHand;
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

    @Override public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level,BlockState state,BlockEntityType<T> type){
        if(level.isClientSide())return null;
        return (l,p,s,be)->{
            if(be instanceof WorkerStationBlockEntity station && l instanceof ServerLevel server){
                if(station.isTrader())TraderWorkService.tick(server,station);
                else if(station.isBuilder())BuilderBuildService.dispatch(server,station);
                else if(station.isBeekeeper())BeekeeperWorkService.dispatch(server,station);
                else if(station.isCooking())ChefWorkService.tick(server,station);
            }
        };
    }
    @Override protected InteractionResult useItemOn(ItemStack held,BlockState state,Level level,
            BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        // The warehouse scanner has its own useOn implementation. Consume
        // this block interaction first so it cannot dispatch pallet crews,
        // toggle modes or change its saved link when used on our table.
        if(specialty != WorkerSpecialty.BUILDER
                || !BuilderScannerLink.isWarehouseScanner(held))
            return InteractionResult.PASS;
        if(level.isClientSide())return InteractionResult.SUCCESS;
        if(level instanceof ServerLevel server
                &&level.getBlockEntity(pos) instanceof WorkerStationBlockEntity station)
            BuilderScannerLink.link(server,pos,player,station,held);
        return InteractionResult.SUCCESS;
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){
        if(!level.isClientSide()&&level.getBlockEntity(pos) instanceof WorkerStationBlockEntity station)
            Containers.dropContents(level,pos,station);
        return super.playerWillDestroy(level,pos,state,player);
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
        if (player.isShiftKeyDown()) {
            station.setPhase(station.phase()==WorkerPhase.PAUSED
                    ?WorkerPhase.IDLE:WorkerPhase.PAUSED);
            player.sendSystemMessage(Component.literal("Station: "+station.phase()));
        } else if(player instanceof ServerPlayer server) {
            server.openMenu(station);
        }
        return InteractionResult.SUCCESS;
    }
}
