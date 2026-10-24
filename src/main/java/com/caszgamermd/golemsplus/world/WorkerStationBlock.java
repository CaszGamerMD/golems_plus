package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.worker.WorkerPhase;
import com.caszgamermd.golemsplus.worker.WorkerSpecialty;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import com.caszgamermd.golemsplus.cooking.CookingKioskTopBlock;
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
    public static final BooleanProperty WINK=BooleanProperty.create("wink");
    public static final EnumProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    private final WorkerSpecialty specialty;
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){
        super.createBlockStateDefinition(builder);builder.add(WINK,FACING);
    }

    public WorkerStationBlock(WorkerSpecialty specialty, BlockBehaviour.Properties properties) {
        super(properties);
        this.specialty = specialty;
        registerDefaultState(defaultBlockState().setValue(WINK,false)
                .setValue(FACING,Direction.NORTH));
    }
    public WorkerSpecialty specialty() { return specialty; }
    @Override public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx){
        if(specialty==WorkerSpecialty.COOKING
                &&!ctx.getLevel().getBlockState(ctx.getClickedPos().above()).canBeReplaced(ctx))
            return null;
        return super.getStateForPlacement(ctx).setValue(FACING,
                ctx.getHorizontalDirection().getOpposite());
    }
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,
            @Nullable LivingEntity placer,ItemStack stack){
        super.setPlacedBy(level,pos,state,placer,stack);
        if(specialty==WorkerSpecialty.COOKING
                &&level.getBlockState(pos.above()).canBeReplaced())
            level.setBlockAndUpdate(pos.above(),
                    WorksiteContent.COOKING_KIOSK_TOP.defaultBlockState()
                            .setValue(CookingKioskTopBlock.FACING,state.getValue(FACING)));
    }
    @Override protected MapCodec<? extends Block> codec() { return codec; }

    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,
            BlockPos pos,CollisionContext context){
        if(specialty!=WorkerSpecialty.COOKING)
            return super.getShape(state,level,pos,context);
        // Back half of each cell only: a genuine wall-screen kiosk.
        return switch(state.getValue(FACING)){
            case NORTH -> Block.box(0,0,8,16,16,16);
            case SOUTH -> Block.box(0,0,0,16,16,8);
            case EAST -> Block.box(0,0,0,8,16,16);
            case WEST -> Block.box(8,0,0,16,16,16);
            default -> Block.box(0,0,8,16,16,16);
        };
    }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WorkerStationBlockEntity(pos, state);
    }

    @Override public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level,BlockState state,BlockEntityType<T> type){
        if(level.isClientSide())return null;
        return (l,p,s,be)->{
            if(be instanceof WorkerStationBlockEntity station && l instanceof ServerLevel server){
                if(station.isBeekeeper()&&server.getGameTime()%22==0
                        &&server.getRandom().nextInt(15)==0){
                    server.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,
                            p.getX()+.89,p.getY()+1.02,p.getZ()+.48,
                            3,.06,.13,.08,.012);
                }
                if(station.isFishing()){
                    // Each cooler fish winks for only a few ticks roughly
                    // once per minute, with per-position desynchronization.
                    long time=Math.floorMod(server.getGameTime()+
                            (long)p.getX()*37+(long)p.getZ()*23,1600);
                    boolean wink=time<7;
                    if(s.getValue(WINK)!=wink)
                        server.setBlock(p,s.setValue(WINK,wink),2);
                }
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
        // Let the crafted worker item place an entity instead of opening
        // the workstation GUI when a player uses it directly on the block.
        if(held.getItem() instanceof com.caszgamermd.golemsplus.worker.WorkerGolemItem)
            return InteractionResult.PASS;
        if(specialty != WorkerSpecialty.BUILDER
                || !BuilderScannerLink.isWarehouseScanner(held)) {
            // PASS skips useWithoutItem in modern Minecraft. Returning this
            // sentinel forwards the click to the claim/inventory handler below.
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if(level.isClientSide())return InteractionResult.SUCCESS;
        if(level instanceof ServerLevel server
                &&level.getBlockEntity(pos) instanceof WorkerStationBlockEntity station)
            BuilderScannerLink.link(server,pos,player,station,held);
        return InteractionResult.SUCCESS;
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){
        if(!level.isClientSide()&&level.getBlockEntity(pos) instanceof WorkerStationBlockEntity station){
            if(station.isCooking()&&station.chefBusy()){
                for(var entry:station.clearChefOrder().entrySet()){
                    var id=net.minecraft.resources.Identifier.tryParse(entry.getKey());
                    if(id!=null&&net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)){
                        var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
                        int left=entry.getValue();
                        while(left>0){
                            int size=Math.min(left,new ItemStack(item).getMaxStackSize());
                            Containers.dropItemStack(level,pos.getX()+.5,pos.getY()+.5,
                                    pos.getZ()+.5,new ItemStack(item,size));
                            left-=size;
                        }
                    }
                }
            }
            Containers.dropContents(level,pos,station);
        }
        if(!level.isClientSide()&&specialty==WorkerSpecialty.COOKING
                &&level.getBlockState(pos.above()).is(WorksiteContent.COOKING_KIOSK_TOP))
            level.removeBlock(pos.above(),false);
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
