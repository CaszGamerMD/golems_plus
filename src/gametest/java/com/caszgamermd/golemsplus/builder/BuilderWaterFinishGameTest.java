package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A finishing water pass changes an already-paid root; it cannot be
 * mistaken for a new cube whose placement would close an exit. Real
 * footing, line-of-sight and hand reach still apply. */
public final class BuilderWaterFinishGameTest {
    @GameTest(maxTicks=90,skyAccess=true)
    public void alreadyPlacedRootsCanBeWaterloggedFromSafeSide(GameTestHelper h){
        var level=h.getLevel();
        BlockPos target=h.absolutePos(new BlockPos(4,2,4));
        BlockPos stance=h.absolutePos(new BlockPos(5,2,4));
        for(int x=2;x<=7;x++)for(int z=2;z<=6;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(4,2,4,Blocks.MANGROVE_ROOTS);
        var builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        builder.setPos(stance.getX()+.5,stance.getY(),stance.getZ()+.5);
        level.addFreshEntity(builder);
        String wet="minecraft:mangrove_roots[waterlogged=true]";
        String phase=BuilderWaterPass.waterlog(wet);
        var resolved=BuilderBlockResolver.resolve(phase,BuildSite.Rotation.NONE,
                BuildSite.Mirror.NONE);
        if(resolved==null||!BuilderConstructionPlanner.safeStandForStep(
                level,builder,target,stance,phase)){
            h.fail("Already placed roots cannot be waterlogged from grounded "
                    +"visible, three-block-reach position");return;
        }
        if(!level.setBlock(target,resolved.state(),3)
                ||!BuilderPlacementState.matches(level.getBlockState(target),
                        resolved.state())){
            h.fail("Water finish did not achieve wet roots");return;
        }
        // A waterlogged cell is now a NO-OP, not a second physical item.
        h.onEachTick(()->{
            if(h.getTick()!=20)return;
            if(!BuilderPlacementState.matches(level.getBlockState(target),
                    resolved.state())){
                h.fail("Water finish changed after world ticks");return;
            }
            System.out.println("[BUILDER WATER FINISH PASS] existing paid roots "
                    +"finished without requiring a fictional new-solid exit");
            h.succeed();
        });
    }

    @GameTest(maxTicks=60,skyAccess=true)
    public void bubbleColumnRepairsWrongBaseAndWaitsForNaturalFlow(GameTestHelper h){
        var level=h.getLevel();
        BlockPos column=h.absolutePos(new BlockPos(4,3,4));
        BlockPos base=column.below();
        var step=new BuilderConstructionPlan.Step(column.getX(),column.getY(),column.getZ(),
                "minecraft:bubble_column[drag=false]",BuilderWaterPass.WATER_BUCKET);
        var planned=java.util.Map.of(base,Blocks.SOUL_SAND.defaultBlockState());
        level.setBlock(base,Blocks.MAGMA_BLOCK.defaultBlockState(),3);
        var repair=BuilderWaterPass.bubbleAction(level,column,step,planned);
        if(repair==null||repair.y()!=base.getY()||!"minecraft:soul_sand".equals(repair.requiredItem())){
            h.fail("Upward column did not request a paid soul sand repair");return;
        }
        if(BuilderWaterPass.bubbleAction(level,column,step,java.util.Map.of(
                base,Blocks.STONE.defaultBlockState()))!=null){
            h.fail("Bubble column attempted to replace a conflicting schematic block");return;
        }
        level.setBlock(base,Blocks.SOUL_SAND.defaultBlockState(),3);
        if(BuilderWaterPass.bubbleAction(level,column,step,planned)!=step){
            h.fail("Correct base did not release the source water action");return;
        }
        level.setBlock(column,Blocks.WATER.defaultBlockState(),3);
        level.scheduleTick(base,Blocks.SOUL_SAND,1);
        h.onEachTick(()->{
            var actual=level.getBlockState(column);
            if(actual.is(Blocks.BUBBLE_COLUMN)&&!actual.getValue(
                    net.minecraft.world.level.block.BubbleColumnBlock.DRAG_DOWN))h.succeed();
        });
    }
}
