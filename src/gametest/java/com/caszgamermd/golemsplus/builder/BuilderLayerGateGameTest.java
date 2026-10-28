package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** The shared Y-layer barrier belongs to the real Planner, not to any
 * single Builder's A* route. Deferred lower work cannot unlock upper
 * construction while other X/Z zone workers finish their own sections.
 */
public final class BuilderLayerGateGameTest {
    @GameTest(maxTicks=130,skyAccess=true)
    public void crewFinishesEveryLowerYBeforeAnyUpperY(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(1,2,0,Blocks.CHEST);
        BlockPos at=helper.absolutePos(new BlockPos(0,2,0));
        BlockPos storage=helper.absolutePos(new BlockPos(1,2,0));
        if(!(level.getBlockEntity(at) instanceof WorkerStationBlockEntity table)){
            helper.fail("Physical Builder Planner missing");return;
        }
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),2)
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                    "placed-layer-gate-gametest.litematic","Two Y layers",
                    at,0,0,4,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),storage)){
            helper.fail("Could not prepare two-layer Planner");return;
        }
        BuilderGolem a=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BuilderGolem b=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        a.setPos(at.getX()+3.5,at.getY(),at.getZ()+3.5);
        b.setPos(at.getX()+4.5,at.getY(),at.getZ()+3.5);
        level.addFreshEntity(a);level.addFreshEntity(b);
        if(!table.registerBuilderWorker(a.getUUID())
                ||!table.registerBuilderWorker(b.getUUID())
                ||!table.startPlacedBuilder(owner.getUUID())){
            helper.fail("Unable to start real two-golem Planner");return;
        }
        int groundY=at.getY();
        List<BuilderConstructionPlan.Step> steps=List.of(
                new BuilderConstructionPlan.Step(at.getX()+3,groundY,at.getZ()+3,
                        "minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(at.getX()+4,groundY,at.getZ()+3,
                        "minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(at.getX()+3,groundY+1,at.getZ()+3,
                        "minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(at.getX()+4,groundY+1,at.getZ()+3,
                        "minecraft:stone","minecraft:stone"));
        List<Integer> az=List.of(0,2),bz=List.of(1,3);
        if(table.builderActiveLayer(steps)!=groundY
                ||table.claimBuilderZoneStep(a.getUUID(),az,steps)!=0
                ||table.claimBuilderZoneStep(b.getUUID(),bz,steps)!=1){
            helper.fail("Did not assign BOTH Builders to same first Y layer");
            return;
        }
        if(!table.completeBuilderStep(a.getUUID(),0)
                ||table.builderCursor()!=1
                ||table.builderActiveLayer(steps)!=groundY
                ||table.claimBuilderZoneStep(a.getUUID(),az,steps)!=-1){
            helper.fail("Worker A incorrectly advanced to high Y before B finished");
            return;
        }
        // Worker B cannot access its lower step for 60 ticks. The shared
        // layer must remain locked throughout the pause, for both workers.
        table.waitForBuilderAccess(b.getUUID(),1,level.getGameTime());
        if(table.claimBuilderZoneStep(b.getUUID(),bz,steps)!=-1
                ||table.claimBuilderZoneStep(a.getUUID(),az,steps)!=-1
                ||table.builderActiveLayer(steps)!=groundY){
            helper.fail("Deferred ground work incorrectly unlocked upper layer");
            return;
        }
        final boolean[] checked={false};
        helper.onEachTick(()->{
            if(checked[0]||helper.getTick()<72)return;
            checked[0]=true;
            if(table.claimBuilderZoneStep(b.getUUID(),bz,steps)!=1
                    ||!table.completeBuilderStep(b.getUUID(),1)
                    ||table.builderCursor()!=2
                    ||table.builderActiveLayer(steps)!=groundY+1){
                helper.fail("Did not unlock higher layer after ALL lower cells");
                return;
            }
            if(table.claimBuilderZoneStep(a.getUUID(),az,steps)!=2
                    ||table.claimBuilderZoneStep(b.getUUID(),bz,steps)!=3){
                helper.fail("Both Builders not independently assigned upper Y");
                return;
            }
            if(!table.completeBuilderStep(a.getUUID(),2)
                    ||!table.completeBuilderStep(b.getUUID(),3)
                    ||table.builderCursor()!=4){
                helper.fail("Crew did not finish the upper layer");
                return;
            }
            System.out.println("[BUILDER Y GATE] PASS lower Y complete "
                    +"across both physical workers before advancing, "
                    +"deferred task retained, upper Y unlocked after retry");
            helper.succeed();
        });
    }
}
