package com.caszgamermd.golemsplus.world;

import com.mojang.serialization.MapCodec;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Unlimited horizontal bridging while connected to a real anchor.
 * A disconnected component falls; crouch + empty-hand click collects
 * its connected enhanced-scaffolding neighbors, preserving the clicked cell.
 */
public final class EnhancedScaffoldingBlock extends ScaffoldingBlock {
    private static final int MAX_COMPONENT=32768;
    // A bottom scaffold needs a floor even when the player is already inside
    // the climbable block. Vanilla's top-only collision can let them fall out.
    private static final VoxelShape BASE_FLOOR=Block.box(0,0,0,16,1,16);
    private final MapCodec<ScaffoldingBlock> codec=MapCodec.unit(this);

    public EnhancedScaffoldingBlock(BlockBehaviour.Properties properties){
        super(properties);
    }

    @Override public MapCodec<ScaffoldingBlock> codec(){return codec;}

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context){
        BlockPos pos=context.getClickedPos();
        return defaultBlockState().setValue(DISTANCE,0)
                .setValue(BOTTOM,!context.getLevel().getBlockState(pos.below()).is(this))
                .setValue(WATERLOGGED,context.getLevel().getFluidState(pos).isSource());
    }

    @Override protected boolean canSurvive(BlockState state,
            net.minecraft.world.level.LevelReader level,BlockPos pos){
        // Vanilla scaffolding rejects placement once its support distance
        // reaches seven. Enhanced scaffolding is allowed to extend farther;
        // its scheduled component-anchor check handles actual falling.
        return true;
    }

    @Override protected ItemStack getCloneItemStack(LevelReader level,BlockPos pos,
            BlockState state,boolean includeData){
        return new ItemStack(WorksiteContent.ENHANCED_SCAFFOLDING_ITEM);
    }

    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,
            BlockPos pos,CollisionContext context){
        VoxelShape top=super.getCollisionShape(state,level,pos,context);
        // Upper scaffold tiers remain pass-through for smooth climbing. Only
        // the bottom tier gets a thin catch floor to prevent falling through.
        return level.getBlockState(pos.below()).is(this)
                ?top:Shapes.or(top,BASE_FLOOR);
    }

    @Override protected boolean canBeReplaced(BlockState state,
            BlockPlaceContext context){
        // Vanilla scaffolding is replaceable by its special item, allowing
        // the item to find the tower/bridge extension from the clicked cell.
        return context.getItemInHand().is(WorksiteContent.ENHANCED_SCAFFOLDING_ITEM)
                ||super.canBeReplaced(state,context);
    }

    private static boolean isEnhanced(Level level,BlockPos pos){
        return level.hasChunkAt(pos)
                &&level.getBlockState(pos).getBlock() instanceof EnhancedScaffoldingBlock;
    }

    /** An anchor is a neighboring, physically supporting non-enhanced block.
     * Air, liquids, replaceable vegetation and other unsupported scaffolding
     * do not count as an anchor. */
    private static boolean anchoredNeighbor(Level level,BlockPos pos){
        for(Direction direction:Direction.values()){
            BlockPos neighbor=pos.relative(direction);
            if(!level.hasChunkAt(neighbor))continue;
            BlockState state=level.getBlockState(neighbor);
            if(state.getBlock() instanceof EnhancedScaffoldingBlock
                    ||state.isAir()||state.getFluidState().isSource()
                    &&state.getCollisionShape(level,neighbor).isEmpty())continue;
            if(!state.getCollisionShape(level,neighbor).isEmpty())return true;
        }
        return false;
    }

    private static Set<BlockPos> connected(Level level,BlockPos origin,
            boolean[] anchored){
        Set<BlockPos> seen=new HashSet<>();
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        seen.add(origin.immutable());queue.add(origin.immutable());
        while(!queue.isEmpty()&&seen.size()<MAX_COMPONENT){
            BlockPos current=queue.removeFirst();
            if(anchoredNeighbor(level,current))anchored[0]=true;
            for(Direction direction:Direction.values()){
                BlockPos neighbor=current.relative(direction);
                if(isEnhanced(level,neighbor)&&seen.add(neighbor.immutable()))
                    queue.addLast(neighbor.immutable());
            }
        }
        // A component too large to inspect safely must not be destroyed
        // based on an incomplete support search.
        if(!queue.isEmpty())anchored[0]=true;
        return seen;
    }

    @Override protected InteractionResult useWithoutItem(BlockState state,
            Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!player.isShiftKeyDown())return InteractionResult.PASS;
        if(level.isClientSide())return InteractionResult.SUCCESS;
        boolean[] anchored={false};
        Set<BlockPos> component=connected(level,pos,anchored);
        component.remove(pos);
        for(BlockPos other:component){
            BlockState existing=level.getBlockState(other);
            if(!(existing.getBlock() instanceof EnhancedScaffoldingBlock))continue;
            // Return every recovered piece to the scaffold that initiated
            // retraction, rather than scattering drops along the bridge.
            Block.popResource(level,pos,new ItemStack(existing.getBlock().asItem()));
            level.setBlock(other,existing.getFluidState().isSource()
                    ?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
        }
        return InteractionResult.SUCCESS;
    }

    @Override protected void tick(BlockState state,ServerLevel level,
            BlockPos pos,RandomSource random){
        if(!isEnhanced(level,pos))return;
        boolean bottom=!level.getBlockState(pos.below()).is(this);
        if(state.getValue(BOTTOM)!=bottom)
            level.setBlock(pos,state.setValue(BOTTOM,bottom),2);
        boolean[] anchored={false};
        Set<BlockPos> component=connected(level,pos,anchored);
        if(anchored[0])return;
        // Use vanilla falling-block physics, never silently delete inventory.
        // Every detached member becomes a falling block entity.
        for(BlockPos member:component){
            BlockState existing=level.getBlockState(member);
            if(existing.getBlock() instanceof EnhancedScaffoldingBlock)
                net.minecraft.world.entity.item.FallingBlockEntity.fall(
                        level,member,existing);
        }
    }

    @Override protected void onPlace(BlockState state,Level level,
            BlockPos pos,BlockState oldState,boolean moved){
        super.onPlace(state,level,pos,oldState,moved);
        if(level instanceof ServerLevel server)
            server.scheduleTick(pos,this,2);
    }

    @Override protected void neighborChanged(BlockState state,Level level,
            BlockPos pos,Block neighborBlock,
            net.minecraft.world.level.redstone.Orientation orientation,
            boolean moved){
        super.neighborChanged(state,level,pos,neighborBlock,orientation,moved);
        if(level instanceof ServerLevel server)
            server.scheduleTick(pos,this,2);
    }
}
