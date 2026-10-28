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
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                    "placed-frontier-fixture.litematic","Frontier",at,0,0,
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
        // A real Builder must finish its first grounded entity tick
        // before exact A* probes can be trusted.
        h.onEachTick(()->{
        if(h.getTick()!=16)return;
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
        world.setBlock(unsupported.south(),Blocks.STONE.defaultBlockState(),3);
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
        });
    }

    @GameTest(maxTicks=130,skyAccess=true)
    public void rejectsThroughWallWorkStanceRatherThanPlaceThroughWall(
            GameTestHelper h){
        floor(h);
        ServerLevel world=h.getLevel();
        var mob=worker(h,h.absolutePos(new BlockPos(6,2,6)));
        var table=planner(h,mob,2);
        // Seal the first target in a TWO-high 3x3 ring. It has no
        // reachable position with a clear sightline, regardless of
        // whether the GameTest world has ground beyond its arena.
        for(int x=2;x<=4;x++)for(int z=2;z<=4;z++){
            if(x==3&&z==3)continue;
            h.setBlock(x,2,z,Blocks.STONE);
            h.setBlock(x,3,z,Blocks.STONE);
        }
        BlockPos sealed=h.absolutePos(new BlockPos(3,2,3));
        BlockPos open=h.absolutePos(new BlockPos(5,2,6));
        var steps=List.of(
                new BuilderConstructionPlan.Step(
                        sealed.getX(),sealed.getY(),sealed.getZ(),
                        "minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(
                        open.getX(),open.getY(),open.getZ(),
                        "minecraft:stone","minecraft:stone"));
        var zone=List.of(0,1);
        h.onEachTick(()->{
        if(h.getTick()!=16)return;
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
        String diagnosis=BuilderConstructionPlanner.blockedSummary(
                world,table,steps,zone);
        if(!diagnosis.contains("#0 XYZ=3, 0, 3")
                ||!diagnosis.contains("no confirmed reachable work stance")){
            h.fail("Missing schematic-local blocked target diagnostics: "
                    +diagnosis);return;
        }
        System.out.println("[FRONTIER] PASS blocked line of sight rejected; "
                +"safe same-Y block claimed; no fake route through wall");
        h.succeed();
        });
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
        // Block jumping ON TOP of the three walls. Without the upper
        // halves a real 1-block jump is an entirely legal alternate exit.
        h.setBlock(2,3,3,Blocks.STONE);
        h.setBlock(3,3,2,Blocks.STONE);
        h.setBlock(3,3,4,Blocks.STONE);
        if(BuilderConstructionPlanner.exitAfterPlacement(
                world,mob,current,target)){
            h.fail("Planner sealed the last physical escape corridor");return;
        }
        h.setBlock(3,2,2,Blocks.AIR);
        h.setBlock(3,3,2,Blocks.AIR);
        if(!BuilderConstructionPlanner.exitAfterPlacement(
                world,mob,current,target)){
            h.fail("Opening a real exit did not make pose usable");return;
        }
        System.out.println("[FRONTIER] PASS last available exit is protected "
                +"before target placement");
        h.succeed();
    }

    @GameTest(maxTicks=120,skyAccess=true)
    public void blockedNearStripCannotStarveDistantSameLayerWork(
            GameTestHelper h){
        floor(h);
        var world=h.getLevel();
        var mob=worker(h,h.absolutePos(new BlockPos(3,2,3)));
        BlockPos realStone=h.absolutePos(new BlockPos(7,2,7));
        var tasks=new java.util.ArrayList<BuilderConstructionPlan.Step>();
        // 35 genuinely separate interior ladder cells, none adjacent
        // to the arena's edge walls or the OnSite station/chest. This
        // prevents incidental test-world support from making a ladder
        // legal when the planner should skip it.
        for(int x=1;x<=6;x++)for(int z=1;z<=6;z++){
            if(x==1&&z==1)continue; // nearest station/chest
            BlockPos p=h.absolutePos(new BlockPos(x,2,z));
            // Every ladder lacks the needed WALL on its back: these
            // nearer cells must be held, but they cannot prevent the
            // one distant supported stone from eventually being found.
            tasks.add(new BuilderConstructionPlan.Step(
                    p.getX(),p.getY(),p.getZ(),
                    "minecraft:ladder[facing=north]","minecraft:ladder"));
        }
        tasks.add(new BuilderConstructionPlan.Step(
                realStone.getX(),realStone.getY(),realStone.getZ(),
                "minecraft:stone","minecraft:stone"));
        var table=planner(h,mob,tasks.size());
        var zone=new java.util.ArrayList<Integer>();
        for(int i=0;i<tasks.size();i++)zone.add(i);
        h.onEachTick(()->{
        if(h.getTick()!=16)return;
        BuilderConstructionPlanner.Choice found=null;
        int trials=0;
        for(;trials<4;trials++){
            var choice=BuilderConstructionPlanner.choose(world,mob,table,
                    tasks,zone);
            if(choice!=null){found=choice;break;}
        }
        if(found==null||found.index()!=tasks.size()-1){
            h.fail("Repeated nearest blocked candidates starved supported "
                    +"frontier after "+trials+" passes: "+found);return;
        }
        if(table.claimPreferredBuilderZoneStep(
                mob.getUUID(),zone,tasks,found.index())!=tasks.size()-1){
            h.fail("Planned distant frontier was not claimable");return;
        }
        System.out.println("[FRONTIER] PASS rotated physically impossible "
                +"nearest 32 and found reachable distant same-Y block "
                +"after "+(trials+1)+" scans");
        h.succeed();
        });
    }

    @GameTest(maxTicks=90,skyAccess=true)
    public void ignoredAzaleaNeverRequiresSupportOrWorkPosition(GameTestHelper h){
        floor(h);
        ServerLevel world=h.getLevel();
        var mob=worker(h,h.absolutePos(new BlockPos(5,2,5)));
        var table=planner(h,mob,37);
        var blocked=h.absolutePos(new BlockPos(3,2,3));
        var azalea=h.absolutePos(new BlockPos(4,2,4));
        var grass=h.absolutePos(new BlockPos(4,2,5));
        var steps=new java.util.ArrayList<BuilderConstructionPlan.Step>();
        for(int i=0;i<35;i++)
            steps.add(new BuilderConstructionPlan.Step(
                    blocked.getX(),blocked.getY(),blocked.getZ(),
                    "minecraft:ladder[facing=north]","minecraft:ladder"));
        steps.add(new BuilderConstructionPlan.Step(
                azalea.getX(),azalea.getY(),azalea.getZ(),
                "minecraft:azalea",null,true));
        steps.add(new BuilderConstructionPlan.Step(
                grass.getX(),grass.getY(),grass.getZ(),
                "minecraft:short_grass",null,true));
        var zone=new java.util.ArrayList<Integer>();
        for(int i=0;i<steps.size();i++)zone.add(i);
        // Remove a plant's footing explicitly: no hidden physical support.
        world.setBlock(azalea.below(),Blocks.AIR.defaultBlockState(),3);
        world.setBlock(grass.below(),Blocks.AIR.defaultBlockState(),3);
        h.onEachTick(()->{
        if(h.getTick()!=16)return;
        for(int index:new int[]{35,36}){
            var choice=BuilderConstructionPlanner.choose(world,mob,table,steps,zone);
            if(choice==null||choice.index()!=index||!choice.alreadyBuilt()){
                h.fail("Ignored vegetation should be unconditional no-op: "+choice);
                return;
            }
            if(table.claimPreferredBuilderZoneStep(mob.getUUID(),
                    zone,steps,index)!=index
                    ||!table.completeBuilderStep(mob.getUUID(),index)){
                h.fail("Unable to finish ignored vegetation step "+index);
                return;
            }
        }
        if(BuilderConstructionPlanner.blockedSummary(world,table,steps,zone)
                .contains("azalea reason=missing physical support")){
            h.fail("Ignored azalea still diagnosed as missing support");
            return;
        }
        if(!world.getBlockState(azalea).isAir()
                ||!world.getBlockState(grass).isAir()){
            h.fail("Ignored vegetation must not create blocks");
            return;
        }
        System.out.println("[FRONTIER] PASS 35 blocked cells cannot starve "
                +"unsupported ignored azalea/grass; no item used");
        h.succeed();
        });
    }

    @GameTest(maxTicks=90,skyAccess=true)
    public void exitCanUseCollisionSafeAdjacentTileWithoutPlacementMargin(
            GameTestHelper h){
        floor(h);
        var world=h.getLevel();
        var mob=worker(h,h.absolutePos(new BlockPos(3,2,3)));
        var stance=h.absolutePos(new BlockPos(3,2,3));
        var target=h.absolutePos(new BlockPos(3,2,5));
        for(int x=2;x<=4;x++)for(int z=2;z<=4;z++){
            if(x==3&&(z==3||z==4))continue;
            h.setBlock(x,2,z,Blocks.STONE);
            h.setBlock(x,3,z,Blocks.STONE);
        }
        h.onEachTick(()->{
        if(h.getTick()!=16)return;
        if(!BuilderConstructionPlanner.exitAfterPlacement(
                world,mob,stance,target)){
            h.fail("Safe adjacent exit incorrectly required 0.8m "
                    +"placement margin AFTER block exists");return;
        }
        h.setBlock(3,2,4,Blocks.STONE);
        h.setBlock(3,3,4,Blocks.STONE);
        if(BuilderConstructionPlanner.exitAfterPlacement(
                world,mob,stance,target)){
            h.fail("Physically walled-off exit was accepted");return;
        }
        System.out.println("[FRONTIER] PASS real swept adjacent exit "
                +"without misapplying placement arm clearance");
        h.succeed();
        });
    }
}
