package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Physical world regression for THREE-high horizontal region scheduling.
 * Upper TWO layers, while retaining the bottom walking floor, then only
 * bottom, then exactly THREE Y-levels down to the next band.
 */
public final class DomeThreeLayerRegionGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=300,skyAccess=true)
    public void clearUpperTwoThenFloorBeforeNextBand(GameTestHelper h){
        ServerLevel world=h.getLevel();
        for(int x=8;x<=24;x++)for(int z=8;z<=18;z++){
            h.setBlock(x,1,z,Blocks.GRASS_BLOCK);
            for(int y=2;y<=15;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        var remaining=new LinkedHashMap<BlockPos,BlockState>();
        // Highest band: top=11, middle=10, floor=9. The preserved
        // work floor is a REAL horizontal roof strip.
        BlockPos top=put(h,world,remaining,11,11,11);
        BlockPos middleA=put(h,world,remaining,12,10,11);
        BlockPos middleB=put(h,world,remaining,13,10,11);
        BlockPos floorA=put(h,world,remaining,11,9,11);
        BlockPos floorB=put(h,world,remaining,12,9,11);
        BlockPos floorC=put(h,world,remaining,13,9,11);
        // Next band: top=8, middle=7, floor=6.
        BlockPos nextTop=put(h,world,remaining,11,8,13);
        BlockPos nextMiddle=put(h,world,remaining,12,7,13);
        BlockPos nextFloor=put(h,world,remaining,12,6,13);
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos feet=h.absolutePos(new BlockPos(12,10,11));
        mob.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);

        var stage=DomeThreeLayerRegion.current(remaining,11+h.absolutePos(
                new BlockPos(0,0,0)).getY());
        // Absolute max is the block we actually placed; don't infer the
        // GameTest's variable global origin.
        int initialTop=top.getY();
        stage=DomeThreeLayerRegion.current(remaining,initialTop);
        require(h,stage.number()==0&&stage.upperPhase()
                &&stage.floorY()==floorA.getY()
                &&stage.upperRemaining()==3
                &&stage.floorRemaining()==3,
                "first band must retain three real floor cells");
        var first=DomeThreeLayerRegion.candidates(
                remaining,stage,mob.blockPosition());
        require(h,first.size()==3&&first.contains(top)
                &&first.contains(middleA)&&first.contains(middleB)
                &&!first.contains(floorA)&&!first.contains(nextTop),
                "first stage must select ONLY upper two layers");
        require(h,DomeThreeLayerRegion.isWorkingFloorPose(stage,feet),
                "the supported Y=10 standing pose is on region floor");
        require(h,world.getBlockState(feet.below()).is(Blocks.STONE),
                "the worker's actual floor block must remain intact");

        for(BlockPos p:first)breakReal(h,world,remaining,p);
        stage=DomeThreeLayerRegion.current(remaining,initialTop);
        require(h,stage.floorPhase()&&stage.number()==0,
                "must clear floor only after BOTH upper levels are empty");
        var second=DomeThreeLayerRegion.candidates(
                remaining,stage,mob.blockPosition());
        require(h,second.size()==3&&second.contains(floorA)
                &&second.contains(floorB)&&second.contains(floorC)
                &&!second.contains(nextTop),
                "floor stage must not prematurely enter next section");
        require(h,!DomeThreeLayerRegion.isFloorClearancePose(
                stage,feet,floorB),
                "must not break the block directly supporting its feet");
        require(h,DomeThreeLayerRegion.isFloorClearancePose(
                stage,feet,floorA),
                "sideways supported pose is possible when not underfoot");

        for(BlockPos p:second)breakReal(h,world,remaining,p);
        stage=DomeThreeLayerRegion.current(remaining,initialTop);
        require(h,stage.number()==1&&stage.upperPhase()
                &&stage.topY()==initialTop-3
                &&stage.floorY()==initialTop-5,
                "next section must begin EXACTLY three blocks lower");
        var third=DomeThreeLayerRegion.candidates(
                remaining,stage,mob.blockPosition());
        require(h,third.size()==2&&third.contains(nextTop)
                &&third.contains(nextMiddle)&&!third.contains(nextFloor),
                "next section still preserves its own bottom floor");
        breakReal(h,world,remaining,nextTop);
        breakReal(h,world,remaining,nextMiddle);
        stage=DomeThreeLayerRegion.current(remaining,initialTop);
        require(h,stage.floorPhase()
                &&DomeThreeLayerRegion.candidates(remaining,stage,
                        mob.blockPosition()).equals(
                        java.util.List.of(nextFloor)),
                "next band switches to bottom clearance after upper 2");
        breakReal(h,world,remaining,nextFloor);
        require(h,DomeThreeLayerRegion.current(remaining,initialTop)
                .phase()==DomeThreeLayerRegion.Phase.COMPLETE,
                "complete world must have no current region");
        System.out.println("[DOME THREE-HIGH] PASS phase order upper "
                +"two then retained floor, 3-Y descent, two regions,"
                +" real block removals and no premature floor clearance");
        h.succeed();
    }
    static BlockPos put(GameTestHelper h,ServerLevel world,
            Map<BlockPos,BlockState> map,int x,int y,int z){
        h.setBlock(x,y,z,Blocks.STONE);
        BlockPos p=h.absolutePos(new BlockPos(x,y,z));
        map.put(p,world.getBlockState(p));
        return p;
    }
    static void breakReal(GameTestHelper h,ServerLevel world,
            Map<BlockPos,BlockState> map,BlockPos p){
        require(h,world.destroyBlock(p,false),"real world rejected "+p);
        map.remove(p);
    }
    static void require(GameTestHelper h,boolean passed,String reason){
        if(!passed)h.fail("Dome 3-high sections: "+reason);
    }
}
