package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Regressions for the actual Froglights and Farm Demos source states. */
public final class BuilderAdvancedFarmGameTest {
    @GameTest(maxTicks=60,skyAccess=true)
    public void openGateKeepsFacingRegardlessOfOwnerView(GameTestHelper h){
        var level=h.getLevel();
        var actor=h.makeMockServerPlayerInLevel();
        for(int i=0;i<4;i++){
            Direction facing=Direction.from2DDataValue(i);
            BlockPos p=h.absolutePos(new BlockPos(2+i*2,2,3));
            h.setBlock(2+i*2,1,3,Blocks.STONE);
            var wanted=Blocks.ACACIA_FENCE_GATE.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING,facing)
                    .setValue(BlockStateProperties.OPEN,true);
            actor.setYRot(facing.getOpposite().toYRot());
            float yaw=actor.getYRot();
            level.setBlock(p,BuilderBlockInteractions.placementState(wanted),3);
            if(!BuilderBlockInteractions.interact(level,p,wanted,actor)
                    ||!BuilderPlacementState.matches(level.getBlockState(p),wanted)
                    ||actor.getYRot()!=yaw){
                h.fail("Gate changed facing or leaked owner yaw: "+facing+" actual="+level.getBlockState(p));return;
            }
        }
        System.out.println("[NN FARM GATE PASS] four opposing owner directions, native gate clicks");
        h.succeed();
    }

    @GameTest(maxTicks=60,skyAccess=true)
    public void farmBlocksResolveToRealItemsAndEmptyEntities(GameTestHelper h){
        var level=h.getLevel();
        String[] states={"minecraft:barrel[facing=east,open=false]",
                "minecraft:crafter[crafting=false,orientation=north_up,triggered=false]",
                "minecraft:soul_campfire[facing=west,lit=true,signal_fire=false,waterlogged=false]",
                "minecraft:chain[axis=y,waterlogged=false]"};
        for(int i=0;i<states.length;i++){
            var parsed=BuilderBlockResolver.resolve(states[i],BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(parsed==null||parsed.item()==net.minecraft.world.item.Items.AIR){
                h.fail("Unresolved farm block "+states[i]);return;
            }
            BlockPos p=h.absolutePos(new BlockPos(2+i*2,2,3));
            h.setBlock(2+i*2,1,3,Blocks.STONE);
            var neutral=BuilderBlockInteractions.placementState(parsed.state());
            if(!level.setBlock(p,neutral,3)||!BuilderPlacementState.matches(level.getBlockState(p),parsed.state())){
                h.fail("Wrong actual farm block "+states[i]+" got "+level.getBlockState(p));return;
            }
            if(i<2&&(!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)||!c.isEmpty())){
                h.fail("Expected real empty inventory "+states[i]);return;
            }
        }
        if(BuilderBlockResolver.resolve("minecraft:spawner",BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)!=null
                ||BuilderBlockResolver.resolve("minecraft:moving_piston[facing=down,type=normal]",BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)!=null){
            h.fail("Unobtainable or transient state accepted");return;
        }
        System.out.println("[NN FARM BLOCK PASS] barrel, crafter, soul campfire, legacy chain");
        h.succeed();
    }
    @GameTest(structure="golems_plus_gametest:matrix_arena",maxTicks=2400,skyAccess=true)
    public void distantExactWorkStandUsesMultipleRealLegs(GameTestHelper h){
        var level=h.getLevel();
        var start=new BlockPos(1024,-55,1024);
        var target=start.offset(72,0,0);
        var center=start.offset(36,0,0);
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(center.getX()>>4,center.getZ()>>4),4);
        for(int x=63;x<=70;x++)for(int z=63;z<=65;z++)level.getChunk(x,z);
        for(int x=-4;x<=76;x++)for(int z=-4;z<=4;z++){
            level.setBlock(start.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=0;y<=3;y++)level.setBlock(start.offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
        }
        var worker=new BuilderGolem(com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        final boolean[] accepted={false};
        h.onEachTick(()->{
            if(h.getTick()<10)return;
            if(!accepted[0]){
                var selected=com.caszgamermd.golemsplus.worker.WorkerNavigation.selectExecutableStand(
                        level,worker,java.util.List.of(target));
                if(!target.equals(selected)){
                    h.fail("Distant standing destination was rejected: "+selected+" "
                        +com.caszgamermd.golemsplus.worker.WorkerNavigation.diagnoseExecutableStand(
                                level,worker,java.util.List.of(target)));return;
                }
                accepted[0]=true;
            }
            if(worker.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)<.05*.05){
                System.out.println("[NN DISTANT STAND PASS] 72-block exact arrival tick="+h.getTick());
                h.succeed();return;
            }
            com.caszgamermd.golemsplus.worker.WorkerNavigation.routeToExact(level,worker,target,1);
            if(h.getTick()==2300)h.fail("Distant exact movement stalled at "+worker.position());
        });
    }
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=2200,skyAccess=true)
    public void ceilingAttachmentBuildsOnlyItsRealUpperSupport(GameTestHelper h){
        var level=h.getLevel();
        for(int x=2;x<=23;x++)for(int z=2;z<=20;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=7;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(4,2,4,com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,4,com.caszgamermd.golemsplus.world.WorksiteContent.CREATIVE_CRATE);
        BlockPos station=h.absolutePos(new BlockPos(4,2,4));
        BlockPos depot=h.absolutePos(new BlockPos(5,2,4));
        BlockPos start=h.absolutePos(new BlockPos(7,2,4));
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        if(!(level.getBlockEntity(station) instanceof com.caszgamermd.golemsplus.world.WorkerStationBlockEntity table)){
            h.fail("Missing Planner");return;
        }
        BlockPos lever=h.absolutePos(new BlockPos(12,2,12));
        BlockPos support=lever.above(),unrelated=lever.offset(2,1,0);
        String leverState="minecraft:lever[face=ceiling,facing=south,powered=false]";
        var cells=java.util.List.of(new LitematicFileReader.Block(lever.getX(),lever.getY(),lever.getZ(),leverState),
                new LitematicFileReader.Block(support.getX(),support.getY(),support.getZ(),"minecraft:stone"),
                new LitematicFileReader.Block(unrelated.getX(),unrelated.getY(),unrelated.getZ(),"minecraft:stone"));
        var owner=h.makeMockServerPlayerInLevel();var id=owner.getUUID();
        try{
            var snapshot=BuilderPlacementSnapshot.encode("Ceiling dependency",cells,java.util.List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),BuilderPlacementSnapshot.worldSpaceSite());
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            String file="placed-gametest-ceiling-dependency.gpb";java.nio.file.Files.write(folder.resolve(file),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                    ||!table.setPlacedBuilderPlan(id,file,"Ceiling dependency",station,0,0,plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,depot)){h.fail("Planner refused dependency job");return;}
            table.setBuilderMaterialsForFile(file,plan.required());
            var worker=new BuilderGolem(com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,level);
            worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);worker.assign(station,id);level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)){h.fail("Cannot start dependency");return;}
            int li=-1,si=-1,ui=-1;
            for(int i=0;i<plan.steps().size();i++){
                var s=plan.steps().get(i);var p=new BlockPos(s.x(),s.y(),s.z());
                if(p.equals(lever))li=i;else if(p.equals(support))si=i;else if(p.equals(unrelated))ui=i;
            }
            if(li<0||si<0||ui<0||!table.builderStepInActivePhase(plan.steps(),si)
                    ||table.builderStepInActivePhase(plan.steps(),ui)){
                h.fail("Only the actual ceiling support may bypass the Y gate");return;
            }
            h.onEachTick(()->{
                if(level.getBlockState(lever).isAir()&&!level.getBlockState(unrelated).isAir()){
                    h.fail("Unrelated higher layer was placed before the attachment");return;
                }
                if(table.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.COMPLETE){
                    var want=BuilderBlockResolver.expectedState(leverState,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                    if(!level.getBlockState(lever).equals(want)||!level.getBlockState(support).is(Blocks.STONE)
                            ||!level.getBlockState(unrelated).is(Blocks.STONE)){
                        h.fail("Wrong completed dependency states");return;
                    }
                    System.out.println("[NN CEILING DEPENDENCY PASS] real upper support before lever, unrelated higher cell after");
                    h.succeed();
                }else if(h.getTick()==2100)h.fail("Ceiling dependency stalled "+table.problem());
            });
        }catch(Exception e){h.fail("Ceiling dependency setup failed "+e);}
    }
}
