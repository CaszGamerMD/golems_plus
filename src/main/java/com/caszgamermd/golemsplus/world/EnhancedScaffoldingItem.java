package com.caszgamermd.golemsplus.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla scaffolding placement controls, without vanilla's seven-block
 * horizontal extension limit:
 *
 * - Click the side of an existing enhanced scaffold: extend its tower up.
 * - Click the top: extend the bridge in the player's facing direction.
 * - Click another block: place normally at the clicked face.
 *
 * The clicked scaffold is never replaced; the first open cell along the
 * requested direction is used. Shift-empty-hand retraction remains a block
 * interaction and is not triggered while holding this item.
 */
public final class EnhancedScaffoldingItem extends BlockItem {
    private static final int MAX_SEARCH=32768;

    public EnhancedScaffoldingItem(Block block,Item.Properties properties){
        super(block,properties);
    }

    @Override
    protected @Nullable BlockPlaceContext updatePlacementContext(
            BlockPlaceContext context){
        BlockPos clicked=context.getClickedPos();
        if(!(context.getLevel().getBlockState(clicked).getBlock()
                instanceof EnhancedScaffoldingBlock))
            return context;

        Direction extension=context.getClickedFace()==Direction.UP
                ?context.getHorizontalDirection():Direction.UP;
        BlockPos candidate=clicked.relative(extension);
        for(int i=0;i<MAX_SEARCH;i++,candidate=candidate.relative(extension)){
            if(context.getLevel().isOutsideBuildHeight(candidate))return null;
            BlockState existing=context.getLevel().getBlockState(candidate);
            if(existing.getBlock() instanceof EnhancedScaffoldingBlock)continue;
            if(!existing.canBeReplaced(context))return null;
            return BlockPlaceContext.at(context,candidate,extension);
        }
        return null;
    }
}
