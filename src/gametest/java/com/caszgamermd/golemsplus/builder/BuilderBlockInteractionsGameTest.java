package com.caszgamermd.golemsplus.builder;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The real server must execute native Minecraft right-click behavior:
 * this is NOT a test that fakes the requested state with setBlock. */
public final class BuilderBlockInteractionsGameTest {
    @GameTest(maxTicks=90,skyAccess=true)
    public void lampOutputComesFromRealRedstoneNeighbor(GameTestHelper h){
        var level=h.getLevel();
        BlockPos pos=h.absolutePos(new BlockPos(4,2,4));
        var wanted=BuilderBlockResolver.resolve(
                "minecraft:redstone_lamp[lit=true]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(wanted==null){h.fail("Redstone lamp unresolved");return;}
        var neutral=BuilderBlockInteractions.placementState(wanted.state());
        if(!level.setBlock(pos,neutral,3)
                ||level.getBlockState(pos).getValue(
                        net.minecraft.world.level.block.state.properties
                            .BlockStateProperties.LIT)){
            h.fail("Builder incorrectly placed a pre-lit redstone lamp");
            return;
        }
        h.onEachTick(()->{
            if(h.getTick()==4)
                h.setBlock(3,2,4,Blocks.REDSTONE_BLOCK);
            if(h.getTick()==9){
                if(!level.getBlockState(pos).getValue(
                        net.minecraft.world.level.block.state.properties
                            .BlockStateProperties.LIT)){
                    h.fail("Real redstone power did not activate lamp");
                    return;
                }
                h.setBlock(3,2,4,Blocks.AIR);
            }
            if(h.getTick()==25){
                if(level.getBlockState(pos).getValue(
                        net.minecraft.world.level.block.state.properties
                            .BlockStateProperties.LIT)
                        ||!BuilderPlacementState.matches(
                                level.getBlockState(pos),wanted.state())){
                    h.fail("Lamp output was not controlled by world updates");
                    return;
                }
                System.out.println("[BUILDER PHYSICS PASS] physical redstone "
                        +"source switched lamp on/off without fake output states");
                h.succeed();
            }
        });
    }

    @GameTest(maxTicks=90,skyAccess=true)
    public void realNoteTuningAndDaylightInversionSurviveNeighborTicks(
            GameTestHelper h){
        var level=h.getLevel();
        var player=h.makeMockServerPlayerInLevel();
        String[] targets={
            "minecraft:note_block[instrument=harp,note=24,powered=false]",
            "minecraft:daylight_detector[inverted=true,power=15]"
        };
        java.util.Map<BlockPos,BlockState> desired=new java.util.LinkedHashMap<>();
        for(int i=0;i<targets.length;i++){
            int x=3+i*4;
            h.setBlock(x,1,4,Blocks.STONE);
            BlockPos pos=h.absolutePos(new BlockPos(x,2,4));
            var resolved=BuilderBlockResolver.resolve(targets[i],
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(resolved==null){
                h.fail("Cannot resolve real interactive target "+targets[i]);
                return;
            }
            var neutral=BuilderBlockInteractions.placementState(resolved.state());
            if(!level.setBlock(pos,neutral,3)
                    ||!BuilderBlockInteractions.needsInteraction(
                            level.getBlockState(pos),resolved.state())
                    ||!BuilderBlockInteractions.interact(
                            level,pos,resolved.state(),player)
                    ||!BuilderPlacementState.matches(
                            level.getBlockState(pos),resolved.state())){
                h.fail("Real native clicks failed for "+targets[i]
                        +" current="+level.getBlockState(pos));
                return;
            }
            desired.put(pos,resolved.state());
        }
        h.onEachTick(()->{
            if(h.getTick()!=20)return;
            for(var cell:desired.entrySet()){
                if(!BuilderPlacementState.matches(
                        level.getBlockState(cell.getKey()),cell.getValue())){
                    h.fail("Manual tuning not stable after neighbor ticks at "
                            +cell.getKey()+" actual="
                            +level.getBlockState(cell.getKey()));
                    return;
                }
            }
            System.out.println("[BUILDER ADVANCED INTERACTIONS PASS] 24"
                    +" actual note-block tunes and daylight inversion");
            h.succeed();
        });
    }

    @GameTest(maxTicks=80,skyAccess=true)
    public void nativeClicksProduceExactSchematicControlStates(GameTestHelper h){
        var level=h.getLevel();
        var player=h.makeMockServerPlayerInLevel();
        Map<BlockPos,BlockState> desired=new LinkedHashMap<>();
        String[] states={
            "minecraft:lever[face=floor,facing=north,powered=true]",
            "minecraft:repeater[delay=4,facing=north,locked=false,powered=false]",
            "minecraft:comparator[facing=north,mode=subtract,powered=false]",
            "minecraft:oak_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]",
            "minecraft:oak_fence_gate[facing=east,in_wall=false,open=true,powered=false]"
        };
        for(int i=0;i<states.length;i++){
            int x=2+i*2;
            h.setBlock(x,1,3,Blocks.STONE);
            BlockPos pos=h.absolutePos(new BlockPos(x,2,3));
            var parsed=BuilderBlockResolver.resolve(states[i],
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(parsed==null){h.fail("Unrecognized manual state: "+states[i]);return;}
            var neutral=BuilderBlockInteractions.placementState(parsed.state());
            if(!level.setBlock(pos,neutral,3)
                    ||!BuilderBlockInteractions.needsInteraction(
                            level.getBlockState(pos),parsed.state())
                    ||!BuilderBlockInteractions.interact(
                            level,pos,parsed.state(),player)
                    ||!BuilderPlacementState.matches(
                            level.getBlockState(pos),parsed.state())){
                h.fail("Minecraft native click did not reach "+states[i]
                        +" actual="+level.getBlockState(pos));return;
            }
            desired.put(pos,parsed.state());
        }
        h.onEachTick(()->{
            if(h.getTick()<15)return;
            for(var entry:desired.entrySet())
                if(!BuilderPlacementState.matches(
                        level.getBlockState(entry.getKey()),entry.getValue())){
                    h.fail("Native clicked state changed after neighbor ticks: "
                            +entry.getKey()+" actual="
                            +level.getBlockState(entry.getKey())
                            +" expected="+entry.getValue());return;
                }
            System.out.println("[BUILDER INTERACTIONS PASS] real server clicked "
                    +desired.size()+" blocks; stable settings after ticks");
            h.succeed();
        });
    }
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=1800,skyAccess=true)
    public void buildersPayForTwoChestHalvesAndDirectedMachines(GameTestHelper h){
        var level=h.getLevel();
        for(int x=2;x<=23;x++)for(int z=2;z<=20;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=7;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(4,2,4,com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,4,Blocks.CHEST);
        BlockPos station=h.absolutePos(new BlockPos(4,2,4));
        BlockPos depot=h.absolutePos(new BlockPos(5,2,4));
        BlockPos start=h.absolutePos(new BlockPos(7,2,4));
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        if(!(level.getBlockEntity(station) instanceof com.caszgamermd.golemsplus.world.WorkerStationBlockEntity table)
                ||!(level.getBlockEntity(depot) instanceof net.minecraft.world.Container stock)){
            h.fail("Missing Planner or stock");return;
        }
        String[] names={"minecraft:chest[facing=south,type=right,waterlogged=false]",
                "minecraft:chest[facing=south,type=left,waterlogged=false]",
                "minecraft:hopper[enabled=true,facing=east]",
                "minecraft:dispenser[facing=north,triggered=false]",
                "minecraft:dropper[facing=up,triggered=false]"};
        var cells=new java.util.ArrayList<LitematicFileReader.Block>();
        var expected=new java.util.LinkedHashMap<BlockPos,BlockState>();
        for(int j=0;j<names.length;j++){
            BlockPos p=h.absolutePos(new BlockPos(12+j,2,12));
            var resolved=BuilderBlockResolver.resolve(names[j],BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(resolved==null){h.fail("Unresolved "+names[j]);return;}
            cells.add(new LitematicFileReader.Block(p.getX(),p.getY(),p.getZ(),names[j]));
            expected.put(p,resolved.state());
        }
        stock.setItem(0,new net.minecraft.world.item.ItemStack(Blocks.CHEST,2));
        stock.setItem(1,new net.minecraft.world.item.ItemStack(Blocks.HOPPER,1));
        stock.setItem(2,new net.minecraft.world.item.ItemStack(Blocks.DISPENSER,1));
        stock.setItem(3,new net.minecraft.world.item.ItemStack(Blocks.DROPPER,1));
        var owner=h.makeMockServerPlayerInLevel();var id=owner.getUUID();
        try{
            var snapshot=BuilderPlacementSnapshot.encode("Container construction",cells,java.util.List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),BuilderPlacementSnapshot.worldSpaceSite());
            if(plan.required().getOrDefault("minecraft:chest",0L)!=2L||plan.steps().size()!=5||!plan.unsupported().isEmpty()){
                h.fail("Container plan did not require two actual chest items and three machines");return;
            }
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            String file="placed-gametest-containers.gpb";java.nio.file.Files.write(folder.resolve(file),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                    ||!table.setPlacedBuilderPlan(id,file,"Container construction",station,0,0,plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,depot)){h.fail("Planner refused containers");return;}
            table.setBuilderMaterialsForFile(file,plan.required());
            var worker=new BuilderGolem(com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,level);
            worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);worker.assign(station,id);level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)){h.fail("Cannot start containers");return;}
            h.onEachTick(()->{
                if(table.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.COMPLETE){
                    for(var e:expected.entrySet()){
                        if(!level.getBlockState(e.getKey()).equals(e.getValue())){
                            h.fail("Wrong container state "+e.getKey()+" actual="+level.getBlockState(e.getKey())+" wanted="+e.getValue());return;
                        }
                        if(!(level.getBlockEntity(e.getKey()) instanceof net.minecraft.world.Container inventory)||!inventory.isEmpty()){
                            h.fail("Missing empty real container at "+e.getKey());return;
                        }
                    }
                    for(int j=0;j<4;j++)if(!stock.getItem(j).isEmpty()){h.fail("Unpaid container material");return;}
                    System.out.println("[BUILDER CONTAINERS PASS] two paid chest halves and three directed machines tick="+h.getTick());
                    h.succeed();
                }else if(h.getTick()==1700)h.fail("Container job stalled "+table.problem()+" worker="+worker.position());
            });
        }catch(Exception e){h.fail("Container test setup failed "+e);}
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=100,skyAccess=true)
    public void flowingWaterRequiresNaturalPhysicsAndNoExtraBucket(GameTestHelper h){
        var level=h.getLevel();
        for(int x=8;x<=16;x++)for(int z=8;z<=16;z++){
            h.setBlock(x,2,z,Blocks.STONE);
            h.setBlock(x,3,z,Blocks.AIR);h.setBlock(x,4,z,Blocks.AIR);
        }
        String source="minecraft:water[level=0]",flow="minecraft:water[level=1]";
        var plan=BuilderConstructionPlan.create(new LitematicFileReader.Schematic("Natural flow",java.util.List.of(
                new LitematicFileReader.Block(0,0,0,source),new LitematicFileReader.Block(1,0,0,flow)),Map.of()),
                new BuildSite(0,64,0,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE));
        if(plan.steps().size()!=2||!plan.unsupported().isEmpty()||!BuilderDeferredState.flowVerification(plan.steps().get(1).encodedState())
                ||plan.steps().get(1).requiredItem()!=null||plan.required().getOrDefault(BuilderWaterPass.WATER_BUCKET,0L)!=1L
                ||BuilderBlockResolver.resolve(flow,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)!=null){
            h.fail("Flowing water must be a pending physics result without an item");return;
        }
        BlockPos target=h.absolutePos(new BlockPos(13,3,12));
        String verify=plan.steps().get(1).encodedState();
        if(BuilderDeferredState.matches(level,target,verify,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)){
            h.fail("Empty cell falsely verified as flowing water");return;
        }
        h.setBlock(12,3,12,Blocks.WATER);
        h.onEachTick(()->{
            if(h.getTick()>5&&BuilderDeferredState.matches(level,target,verify,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)){
                System.out.println("[BUILDER FLOW PASS] real source produced exact flowing level tick="+h.getTick());h.succeed();
            }else if(h.getTick()==90)h.fail("Natural source did not yield desired water level: "+level.getBlockState(target));
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=80,skyAccess=true)
    public void soulFireUsesRealBuilderFlintAndSteelOnSoulSand(GameTestHelper h){
        var level=h.getLevel();
        for(int x=8;x<=16;x++)for(int z=8;z<=16;z++){
            h.setBlock(x,2,z,Blocks.STONE);
            for(int y=3;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(12,2,12,Blocks.SOUL_SAND);
        BlockPos pos=h.absolutePos(new BlockPos(12,3,12));
        BlockPos feet=h.absolutePos(new BlockPos(10,3,12));
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(feet.getX()>>4,feet.getZ()>>4),3);
        var owner=h.makeMockServerPlayerInLevel();
        var worker=new BuilderGolem(com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);level.addFreshEntity(worker);
        var desired=BuilderBlockResolver.resolve("minecraft:soul_fire",BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(desired==null||desired.item()!=net.minecraft.world.item.Items.FLINT_AND_STEEL){
            h.fail("Soul fire needs an actual ignition tool");return;
        }
        h.onEachTick(()->{
            if(h.getTick()!=5)return;
            if(BuilderIgnition.igniteSoulFire(level,worker,owner,pos)){
                h.fail("Ignited fire without flint and steel");return;
            }
            worker.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FLINT_AND_STEEL));
            if(!BuilderIgnition.igniteSoulFire(level,worker,owner,pos)
                    ||!level.getBlockState(pos).is(Blocks.SOUL_FIRE)
                    ||worker.getItem(0).getDamageValue()!=1){
                h.fail("Native soul fire ignition or real tool wear failed");return;
            }
            System.out.println("[BUILDER SOUL FIRE PASS] native flint and steel, real durability debit");
            h.succeed();
        });
    }

}
