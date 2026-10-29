package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Target-independent escape contract for a 1-, 2- or 3-target work package.
 * Tests an ACTUAL Minecraft world, and uses a real Builder's collision box.
 * No entity spawn/tickets required: this is a geometry/proof test, not a
 * movement-speed test. The main demolition GameTest drives movement.
 */
public final class BuilderSharedEscapeBatchGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=350,skyAccess=true)
    public void commonStanceAndUnchangedEscapeForThreeBreaks(GameTestHelper h){
        ServerLevel world=h.getLevel();
        for(int x=7;x<=24;x++)for(int z=7;z<=24;z++){
            h.setBlock(x,1,z,Blocks.GRASS_BLOCK);
            for(int y=2;y<=6;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos stance=h.absolutePos(new BlockPos(11,2,11));
        mob.setPos(stance.getX()+.5,stance.getY(),stance.getZ()+.5);
        BlockPos one=h.absolutePos(new BlockPos(12,2,11));
        BlockPos two=h.absolutePos(new BlockPos(13,2,12));
        BlockPos three=h.absolutePos(new BlockPos(12,2,12));
        var targets=List.of(one,two,three);
        var states=new LinkedHashMap<BlockPos,BlockState>();
        for(BlockPos target:targets){
            world.setBlock(target,Blocks.STONE.defaultBlockState(),3);
            states.put(target,world.getBlockState(target));
        }
        require(h,BuilderSharedDemolitionPlan.pairwiseWithinTwo(targets),
                "all three targets must be within 2 blocks of each other");
        var direct=BuilderEscapeRoute.previewBreakGroup(
                world,mob,stance,targets,false);
        require(h,direct!=null&&direct.size()>=2,
                "full group must prove a complete 2+ step common escape");
        var proposed=BuilderSharedDemolitionPlan.select(
                world,mob,stance,one,states,targets,
                p->true,p->true,false);
        require(h,proposed!=null&&proposed.targets().size()==3,
                "select should preserve all three when route is safe");
        List<BlockPos> unchanged=new ArrayList<>(proposed.escape());
        BlockPos safePoint=proposed.safePoint();
        require(h,BuilderEscapeRoute.groupPathStillValid(
                world,mob,stance,targets,unchanged),
                "initial reserved route must be traversable");

        // Actually remove each real world block while NEVER recomputing or
        // substituting an escape path. Original exact route stays valid.
        for(BlockPos target:targets){
            require(h,world.destroyBlock(target,false,mob),
                    "world refused physical removal "+target);
            states.remove(target);
            require(h,BuilderEscapeRoute.groupPathStillValid(
                    world,mob,stance,targets,unchanged),
                    "original escape changed after breaking "+target);
        }
        require(h,safePoint.equals(unchanged.get(unchanged.size()-1)),
                "shared safe point changed after a block was removed");

        // Someone targeting a footing under the shared stance must be
        // rejected, even when the other two targets are nearby.
        BlockPos footing=stance.below();
        var unsafe=List.of(footing,footing.east(),footing.south());
        require(h,BuilderSharedDemolitionPlan.pairwiseWithinTwo(unsafe),
                "unsafe group itself must meet the <=2-block spacing");
        require(h,BuilderEscapeRoute.previewBreakGroup(
                world,mob,stance,unsafe,false)==null,
                "escape plan cannot rely on a soon-removed footing");

        // A chain where only adjacent pairs are close does NOT qualify:
        // every target must be close to every OTHER target.
        var spaced=List.of(one,one.east(),one.east(4));
        require(h,!BuilderSharedDemolitionPlan.pairwiseWithinTwo(spaced),
                "far endpoints must reject loosely chained groups");
        require(h,BuilderEscapeRoute.previewBreakGroup(
                world,mob,stance,spaced,false)==null,
                "route planner must reject invalid group geometry");

        // A cached path does not silently switch to a different refuge
        // if unrelated new construction blocks the original corridor.
        BlockPos blocked=unchanged.get(unchanged.size()-1);
        world.setBlock(blocked,Blocks.STONE.defaultBlockState(),3);
        require(h,!BuilderEscapeRoute.groupPathStillValid(
                world,mob,stance,targets,unchanged),
                "changed corridor must abort the shared work package");
        System.out.println("[SHARED ESCAPE BATCH] PASS targets=3"
                +" pairwise<=2 reach<=3 commonStance="+stance
                +" reservedRefuge="+safePoint+" routeSteps="+unchanged.size()
                +" exactRoutePreservedAfterAllBreaks=true"
                +" underfootDenied=true corridorChangeDenied=true");
        h.succeed();
    }
    private static void require(GameTestHelper h,boolean condition,String why){
        if(!condition)h.fail("Shared escape experiment: "+why);
    }
}
