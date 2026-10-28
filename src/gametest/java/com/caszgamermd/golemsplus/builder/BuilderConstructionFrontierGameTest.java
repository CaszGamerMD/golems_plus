package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Tests REAL planner state and collision/pathfinding preflight, not a
 * mocked array sorter. Builders cannot fly, teleport, ignore support,
 * or start a later Y layer to escape a blocked earlier one.
 */
public final class BuilderConstructionFrontierGameTest {
    private static void floor(GameTestHelper h){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        h.setBlock(1,2,0,Blocks.CHEST);
    }
    private static BuilderGolem worker(GameTestHelper h,BlockPos at){
        var w=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,h.getLevel());
        w.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        h.getLevel().addFreshEntity(w);
        return w;
    }
    private static WorkerStationBlockEntity planner(GameTestHelper h,
            BuilderGolem worker,int taskCount){
        var level=h.getLevel();
        BlockPos at=h.absolutePos(new BlockPos(0,2,0));
        BlockPos depot=h.absolutePos(new BlockPos(1,2,0));
        var table=(WorkerStationBlockEntity)level.getBlockEntity(at);
        var owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                    "frontier-fixture.litematic","Frontier",at,0,0,
                    taskCount,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),depot)
                ||!table.registerBuilderWorker(worker.getUUID())
                ||!table.startPlacedBuilder(owner.getUUID())){
            h.fail("Could not initialize real persistent Builder planner");
        }
        return table;
    }

    @GameTest(maxTicks=100,skyAccess=true)
    public void picksSupportedFrontierBeforeUnsupportedEarlierCell(
            GameTestHelper h){
        floor(h);
        ServerLevel world=h.getLevel();
        var mob=worker(h,h.absolutePos(new BlockPos(4,2,5)));
        var table=planner(h,mob,3);
        BlockPos unsupported=h.absolutePos(new BlockPos(3,2,3));
        BlockPos supported=h.absolutePos(new BlockPos(5,2,3));
        BlockPos upper=supported.above();
        var steps=List.of(
                new BuilderConstructionPlan.Step(
                        unsupported.getX(),unsupported.getY(),unsupported.getZ(),
                        "minecraft:ladder[facing=north]","minecraft:ladder"),
                new BuilderConstructionPlan.Step(
                        supported.getX(),supported.getY(),supported.getZ(),
                        "minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(
                        upper.getX(),upper.getY(),upper.getZ(),
                        "minecraft:stone","minecraft:stone"));
        var zone=List.of(0,1,2);
        var choice=BuilderConstructionPlanner.choose(world,mob,table,steps,zone);
        if(choice==null||choice.index()!=1||choice.stance()==null){
            h.fail("Planner should choose REAL reachable stone, not unsupported "
                    +"ladder or upper Y: "+choice);return;
        }
        if(table.claimPreferredBuilderZoneStep(mob.getUUID(),
                zone,steps,choice.index())!=1){
            h.fail("Persistent Planner rejected safe frontier index 1");return;
        }
        world.setBlock(supported,Blocks.STONE.defaultBlockState(),3);
        if(!table.completeBuilderStep(mob.getUUID(),1)
                ||table.builderCursor()!=0||table.builderActiveLayer(steps)
                    !=unsupported.getY()){
            h.fail("Completing one same-Y cell incorrectly advanced Y");return;
        }
        if(BuilderConstructionPlanner.choose(world,mob,table,steps,zone)!=null){
            h.fail("Unsupported lower task must NOT unlock upper Y");return;
        }
        // This support turns the previously inaccessible first layer
        // cell into a physically placeable ladder.
        world.setBlock(unsupported.north(),Blocks.STONE.defaultBlockState(),3);
        choice=BuilderConstructionPlanner.choose(world,mob,table,steps,zone);
        if(choice==null||choice.index()!=0){
            h.fail("Missing support did not cause previously blocked cell "
                    +"to be retried: "+choice);return;
        }
        if(table.claimPreferredBuilderZoneStep(mob.getUUID(),
                zone,steps,0)!=0){
            h.fail("Failed to reclaim lower Y after support appeared");return;
        }
        world.setBlock(unsupported,Blocks.LADDER.defaultBlockState(),3);
        if(!table.completeBuilderStep(mob.getUUID(),0)
                ||table.builderCursor()!=2||table.builderActiveLayer(steps)
                    !=upper.getY()){
            h.fail("All ground work did not unlock next shared Y");return;
        }
        System.out.println("[FRONTIER] PASS skipped unsupported same-Y first "
                +"cell, built reachable second, held Y, retried first after "
                +"support appeared, then unlocked Y+1");
        h.succeed();
    }

    @GameTest(maxTicks=130,skyAccess=true)
    public void rejectsThroughWallWorkStanceRatherThanPlaceThroughWall(
            GameTestHelper h){
        floor(h);
        ServerLevel world=h.getLevel();
        var mob=worker(h,h.absolutePos(new BlockPos(3,2,6)));
        var table=planner(h,mob,2);
        // An entire two-high wall divides the grounded game arena.
        for(int x=0;x<8;x++){
            h.setBlock(x,2,4,Blocks.STONE);
            h.setBlock(x,3,4,Blocks.STONE);
        }
        BlockPos sealed=h.absolutePos(new BlockPos(3,2,3));
        BlockPos open=h.absolutePos(new BlockPos(4,2,6));
        var steps=List.of(
                new BuilderConstructionPlan.Step(
                        sealed.getX(),sealed.getY(),sealed.getZ(),
                        "minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(
                        open.getX(),open.getY(),open.getZ(),
                        "minecraft:stone","minecraft:stone"));
        var zone=List.of(0,1);
        var chosen=BuilderConstructionPlanner.choose(world,mob,table,steps,zone);
        if(chosen==null||chosen.index()!=1
                ||chosen.stance()==null){
            h.fail("Planner chose through-wall task or no safe frontier "
                    +chosen);return;
        }
        if(table.claimPreferredBuilderZoneStep(mob.getUUID(),
                    zone,steps,chosen.index())!=1){
            h.fail("Chosen reachable task was not claimable");return;
        }
        world.setBlock(open,Blocks.STONE.defaultBlockState(),3);
        table.completeBuilderStep(mob.getUUID(),1);
        var blocked=BuilderConstructionPlanner.choose(world,mob,table,steps,zone);
        if(blocked!=null){
            h.fail("Planner must report no feasible task behind wall, not "
                    +"fabricate route "+blocked);return;
        }
        if(table.builderActiveLayer(steps)!=sealed.getY()){
            h.fail("Inaccessible lower Y wrongly unlocked higher Y");return;
        }
        System.out.println("[FRONTIER] PASS blocked line of sight rejected; "
                +"safe same-Y block claimed; no fake route through wall");
        h.succeed();
    }

    @GameTest(maxTicks=80,skyAccess=true)
    public void futurePlacementCannotSealOnlyExit(
            GameTestHelper h){
        floor(h);
        var world=h.getLevel();
        var mob=worker(h,h.absolutePos(new BlockPos(3,2,3)));
        BlockPos current=mob.blockPosition();
        BlockPos target=current.east(2);
        // A work stance with three solid walls has only its future
        // target-side corridor is too close to the future block (under
        // 0.8m margin). It is NOT an exit once the block is placed.
        h.setBlock(2,2,3,Blocks.STONE);
        h.setBlock(3,2,2,Blocks.STONE);
        h.setBlock(3,2,4,Blocks.STONE);
        if(BuilderConstructionPlanner.exitAfterPlacement(
                world,mob,current,target)){
            h.fail("Planner sealed the last physical escape corridor");return;
        }
        h.setBlock(3,2,2,Blocks.AIR);
        if(!BuilderConstructionPlanner.exitAfterPlacement(
                world,mob,current,target)){
            h.fail("Opening a real exit did not make pose usable");return;
        }
        System.out.println("[FRONTIER] PASS last available exit is protected "
                +"before target placement");
        h.succeed();
    }
}
