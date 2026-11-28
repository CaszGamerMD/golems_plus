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
    @GameTest(maxTicks=40,skyAccess=true)
    public void waterWaitsForAdjacentDryWorkOnly(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(1,2,0,Blocks.CHEST);
        BlockPos at=helper.absolutePos(new BlockPos(0,2,0));
        BlockPos storage=helper.absolutePos(new BlockPos(1,2,0));
        if(!(level.getBlockEntity(at) instanceof WorkerStationBlockEntity table)){
            helper.fail("Physical Builder Planner missing");return;
        }
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())||!table.setCrewTarget(owner.getUUID(),2)
                ||!table.setPlacedBuilderPlan(owner.getUUID(),"water-neighborhood-gate.litematic","Water barrier",at,0,0,5,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),storage)){
            helper.fail("Could not prepare water barrier");return;
        }
        BuilderGolem a=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BuilderGolem b=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        a.setPos(at.getX()+2.5,at.getY(),at.getZ()+2.5);
        b.setPos(at.getX()+5.5,at.getY(),at.getZ()+5.5);
        level.addFreshEntity(a);level.addFreshEntity(b);
        if(!table.registerBuilderWorker(a.getUUID())||!table.registerBuilderWorker(b.getUUID())
                ||!table.startPlacedBuilder(owner.getUUID())){
            helper.fail("Unable to start crew");return;
        }
        int y=at.getY();
        List<BuilderConstructionPlan.Step> steps=List.of(
                new BuilderConstructionPlan.Step(at.getX()+2,y,at.getZ()+2,"minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(at.getX()+6,y,at.getZ()+6,"minecraft:rail[shape=north_south,waterlogged=false]","minecraft:rail"),
                new BuilderConstructionPlan.Step(at.getX()+2,y,at.getZ()+3,"minecraft:water[level=0]",BuilderWaterPass.WATER_BUCKET),
                new BuilderConstructionPlan.Step(at.getX()+3,y,at.getZ()+3,"minecraft:bubble_column[drag=false]",BuilderWaterPass.WATER_BUCKET),
                new BuilderConstructionPlan.Step(at.getX()+3,y+1,at.getZ()+3,"minecraft:stone","minecraft:stone"));
        List<Integer> az=List.of(0,2,3,4),bz=List.of(1);
        if(table.builderWaterNeighborsReady(steps,steps.get(2))
                ||!table.builderWaterNeighborsReady(steps,steps.get(3))
                ||!table.builderWaterNeighborsPresent(steps,steps.get(3))
                ||table.claimBuilderZoneStep(a.getUUID(),az,steps)!=0){
            helper.fail("Water unlocked before its own planned neighbor");return;
        }
        helper.setBlock(2,2,2,Blocks.STONE);
        if(!table.completeBuilderStep(a.getUUID(),0)
                ||!table.builderWaterNeighborsReady(steps,steps.get(2))
                ||!table.builderWaterNeighborsPresent(steps,steps.get(2))
                ||!table.builderWaterNeighborsReady(steps,steps.get(3))
                ||!table.builderWaterNeighborsPresent(steps,steps.get(3))
                ||!table.builderStepInActivePhase(steps,2)
                ||table.claimBuilderZoneStep(a.getUUID(),az,steps)!=2){
            helper.fail("Distant dry work or unfinished overhead blocked independent water");return;
        }
        helper.setBlock(2,2,2,Blocks.AIR);
        if(table.builderWaterNeighborsPresent(steps,steps.get(2))){
            helper.fail("Missing paid wall passed the live water guard");return;
        }
        helper.setBlock(2,2,2,Blocks.STONE);
        if(!table.completeBuilderStep(a.getUUID(),2)
                ||!table.builderWaterNeighborsReady(steps,steps.get(3))
                ||!table.builderWaterNeighborsPresent(steps,steps.get(3))){
            helper.fail("Unfinished overhead incorrectly blocked bubble column");return;
        }
        System.out.println("[BUILDER WATER NEIGHBORHOOD] PASS adjacent dry prerequisite, live wall check, overhead ignored");
        helper.succeed();
    }
    @GameTest(maxTicks=40,skyAccess=true)
    public void waterRequiresFloorAndSidesButNotOverhead(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        BlockPos at=helper.absolutePos(new BlockPos(3,2,3));
        int x=at.getX(),y=at.getY(),z=at.getZ();
        var water=new BuilderConstructionPlan.Step(x,y,z,"minecraft:water[level=0]",BuilderWaterPass.WATER_BUCKET);
        var floor=new BuilderConstructionPlan.Step(x,y-1,z,"minecraft:stone","minecraft:stone");
        var overhead=new BuilderConstructionPlan.Step(x,y+1,z,"minecraft:stone","minecraft:stone");
        var side=new BuilderConstructionPlan.Step(x+1,y,z,"minecraft:stone","minecraft:stone");
        var neighborhood=new BuilderWaterNeighborhood(List.of(water,floor,overhead,side));
        if(neighborhood.ready(0,i->false)
                ||neighborhood.ready(0,i->i==1)
                ||neighborhood.ready(0,i->i==3)
                ||!neighborhood.ready(0,i->i==1||i==3)
                ||!neighborhood.ready(water,i->i==1||i==3)){
            helper.fail("Water must wait for floor and side, but not overhead");return;
        }
        var rotation=BuildSite.Rotation.values()[0];
        var mirror=BuildSite.Mirror.values()[0];
        helper.setBlock(3,3,3,Blocks.AIR);
        helper.setBlock(3,1,3,Blocks.STONE);
        helper.setBlock(4,2,3,Blocks.STONE);
        if(!neighborhood.physicallyReady(level,water,rotation,mirror)){
            helper.fail("Present floor and side did not pass live check with overhead absent");return;
        }
        helper.setBlock(3,1,3,Blocks.AIR);
        if(neighborhood.physicallyReady(level,water,rotation,mirror)){
            helper.fail("Missing floor passed live water check");return;
        }
        helper.setBlock(3,1,3,Blocks.STONE);
        helper.setBlock(4,2,3,Blocks.AIR);
        if(neighborhood.physicallyReady(level,water,rotation,mirror)){
            helper.fail("Missing side passed live water check");return;
        }
        System.out.println("[BUILDER WATER FLOOR] PASS live side and floor checks, overhead ignored");
        helper.succeed();
    }
}
