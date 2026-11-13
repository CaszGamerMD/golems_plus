package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.CreativeCrateBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.Direction;

/** Both fluid tools are one reusable supply, and a placed crate is virtual. */
public final class BuilderCreativeCrateFluidGameTest {
    @GameTest(maxTicks=80,skyAccess=true)
    public void retractedUpwardPistonPlacesAsOneRealBlock(GameTestHelper h){
        var piston=BuilderBlockResolver.resolve(
                "minecraft:piston[extended=false,facing=up]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var sticky=BuilderBlockResolver.resolve(
                "minecraft:sticky_piston[extended=false,facing=north]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(piston==null||sticky==null
                ||piston.item()!=net.minecraft.world.item.Items.PISTON
                ||sticky.item()!=net.minecraft.world.item.Items.STICKY_PISTON
                ||piston.state().getValue(BlockStateProperties.FACING)!=Direction.UP
                ||piston.state().getValue(BlockStateProperties.EXTENDED)
                ||BuilderBlockResolver.resolve(
                        "minecraft:piston[extended=true,facing=up]",
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)!=null
                ||BuilderBlockResolver.resolve("minecraft:piston_head",
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)!=null){
            h.fail("Retracted piston state was rejected or moving piston allowed");
            return;
        }
        h.setBlock(3,2,3,Blocks.STONE);
        h.getLevel().setBlock(h.absolutePos(new BlockPos(3,3,3)),
                piston.state(),3);
        h.onEachTick(()->{
            if(h.getTick()<12)return;
            if(!BuilderPlacementState.matches(h.getLevel().getBlockState(
                    h.absolutePos(new BlockPos(3,3,3))),piston.state())){
                h.fail("Upward-facing unextended piston did not survive placement");
                return;
            }
            h.succeed();
        });
    }
    @GameTest(maxTicks=80,skyAccess=true)
    public void ascendingRailHoldsSlopeAfterTemporaryUpperRailIsRemoved(GameTestHelper h){
        // The elevated connector sits on a real solid block one Y higher.
        h.setBlock(4,2,4,Blocks.STONE);
        h.setBlock(5,3,4,Blocks.STONE);
        h.setBlock(5,4,4,Blocks.RAIL); // temporary raised connection
        h.setBlock(4,3,4,Blocks.RAIL.defaultBlockState()
                .setValue(RailBlock.SHAPE,RailShape.ASCENDING_EAST));
        var lower=h.absolutePos(new BlockPos(4,3,4));
        var raised=h.absolutePos(new BlockPos(5,4,4));
        if(!raised.equals(BuilderRailSlope.raisedEnd(lower,
                Blocks.RAIL.defaultBlockState().setValue(
                        RailBlock.SHAPE,RailShape.ASCENDING_EAST)))){
            h.fail("Ascending east rail connector world position is wrong");return;
        }
        if(!h.getLevel().getBlockState(lower).is(Blocks.RAIL)){
            h.fail("Temporary rail could not establish ascending connection");return;
        }
        h.setBlock(5,4,4,Blocks.AIR);
        h.onEachTick(()->{
            if(h.getTick()<12)return;
            var lowerState=h.getLevel().getBlockState(lower);
            if(!h.getLevel().getBlockState(raised).isAir()
                    ||!lowerState.is(Blocks.RAIL)
                    ||lowerState.getValue(RailBlock.SHAPE)!=RailShape.ASCENDING_EAST){
                h.fail("Ascending rail lost its shape after temporary rail retrieval");
                return;
            }
            h.succeed();
        });
    }

    @GameTest(maxTicks=80,skyAccess=true)
    public void creativeCrateAndReusableFluidDemand(GameTestHelper h){
        h.setBlock(2,2,2,WorksiteContent.CREATIVE_CRATE);
        if(!(h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2,2,2)))
                instanceof CreativeCrateBlockEntity crate)){
            h.fail("Creative Crate block entity missing");return;
        }
        if(!crate.isEmpty()||!crate.getItem(0).isEmpty()
                ||!crate.canPlaceItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK))){
            h.fail("Creative Crate is not an empty virtual supply depot");return;
        }
        var water=BuilderBlockResolver.resolve("minecraft:water[level=0]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var lava=BuilderBlockResolver.resolve("minecraft:lava[level=0]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var bubble=BuilderBlockResolver.resolve("minecraft:bubble_column[drag=false]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(water==null||water.item()!=WorksiteContent.INFINITE_WATER_BUCKET
                ||lava==null||lava.item()!=WorksiteContent.INFINITE_LAVA_BUCKET
                ||bubble==null||bubble.item()!=WorksiteContent.INFINITE_WATER_BUCKET
                ||!bubble.state().is(Blocks.BUBBLE_COLUMN)
                ||bubble.state().getValue(BubbleColumnBlock.DRAG_DOWN)
                ||BuilderBlockResolver.resolve("minecraft:water[level=1]",
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)!=null){
            h.fail("Fluid source resolver/bucket mappings incorrect");return;
        }
        var steps=List.of(
                new BuilderConstructionPlan.Step(0,0,0,"minecraft:water[level=0]",
                        "golems_plus:infinite_water_bucket"),
                new BuilderConstructionPlan.Step(1,0,0,"minecraft:water[level=0]",
                        "golems_plus:infinite_water_bucket"),
                new BuilderConstructionPlan.Step(3,0,0,"minecraft:bubble_column[drag=false]",
                        "golems_plus:infinite_water_bucket"),
                new BuilderConstructionPlan.Step(2,0,0,"minecraft:lava[level=0]",
                        "golems_plus:infinite_lava_bucket"));
        var needed=BuilderMaterialDemand.requiredMaterials(steps);
        if(needed.getOrDefault("golems_plus:infinite_water_bucket",0L)!=1L
                ||needed.getOrDefault("golems_plus:infinite_lava_bucket",0L)!=1L){
            h.fail("Infinite fluid bucket was counted once PER block");return;
        }
        var layers=new LitematicFileReader.Schematic("bubble water pass",List.of(
                new LitematicFileReader.Block(0,0,0,"minecraft:bubble_column[drag=false]"),
                new LitematicFileReader.Block(1,0,0,"minecraft:smooth_stone"),
                new LitematicFileReader.Block(2,0,0,"minecraft:water[level=0]")),Map.of());
        var layerPlan=BuilderConstructionPlan.create(layers,new BuildSite(0,64,0,
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE));
        if(!layerPlan.unsupported().isEmpty()||layerPlan.steps().size()!=3
                ||BuilderWaterPass.isWaterStep(layerPlan.steps().getFirst().encodedState())
                ||!BuilderWaterPass.isWaterStep(layerPlan.steps().get(1).encodedState())
                ||!BuilderWaterPass.isWaterStep(layerPlan.steps().get(2).encodedState())){
            h.fail("Bubble column and source water were not queued last in layer");return;
        }
        // A Builder must use water, not setBlock(BUBBLE_COLUMN). Prove the
        // engine actually turns source water into an upward column when
        // the soul sand underneath has already been physically placed.
        h.setBlock(4,2,4,Blocks.SOUL_SAND);
        h.setBlock(4,3,4,Blocks.WATER);
        var bubblePos=h.absolutePos(new BlockPos(4,3,4));
        h.onEachTick(()->{
            if(h.getTick()<2)return;
            var state=h.getLevel().getBlockState(bubblePos);
            if(state.is(Blocks.BUBBLE_COLUMN)
                    &&!state.getValue(BubbleColumnBlock.DRAG_DOWN)){
                h.succeed();return;
            }
            if(h.getTick()>60)h.fail("Source water above soul sand did not form a bubble column");
        });
    }
    @GameTest(maxTicks=100,skyAccess=true)
    public void extendedPistonRequiresRealPowerAndPreservesPlanIndices(GameTestHelper h){
        String baseState="minecraft:sticky_piston[extended=true,facing=east]";
        String headState="minecraft:piston_head[facing=east,short=false,type=sticky]";
        var schematic=new LitematicFileReader.Schematic("Piston commissioning",List.of(
                new LitematicFileReader.Block(0,0,0,baseState),
                new LitematicFileReader.Block(1,0,0,headState),
                new LitematicFileReader.Block(0,1,0,"minecraft:redstone_block")),Map.of());
        var plan=BuilderConstructionPlan.create(schematic,new BuildSite(0,64,0,
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE));
        if(plan.steps().size()!=3||!plan.unsupported().isEmpty()
                ||!plan.steps().get(0).encodedState().contains("extended=false")
                ||!BuilderPistonWork.verification(plan.steps().get(1).encodedState())
                ||plan.steps().get(1).requiredItem()!=null
                ||plan.required().getOrDefault("minecraft:sticky_piston",0L)!=1L){
            h.fail("Piston plan must retain one paid base and one pending verification");return;
        }
        BlockPos base=h.absolutePos(new BlockPos(3,3,3));
        BlockPos head=h.absolutePos(new BlockPos(4,3,3));
        var world=h.getLevel();
        h.setBlock(3,3,3,Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING,Direction.EAST));
        if(BuilderPistonWork.matches(world,head,headState,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)){
            h.fail("Unpowered piston verification was falsely completed");return;
        }
        h.setBlock(3,4,3,Blocks.REDSTONE_BLOCK);
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(BuilderPistonWork.matches(world,head,headState,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)){
                System.out.println("[PISTON COMMISSION PASS] vanilla power generated exact head");
                h.succeed();
            }else if(h.getTick()>80)h.fail("Real redstone did not generate matching piston head");
        });
    }

    /** A complete physical job: the unpowered head must not hold the
     * lower Y gate, yet the job cannot complete until vanilla powers it. */
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=1200,skyAccess=true)
    public void plannerBuildsPowerBeforeCompletingPistonVerification(GameTestHelper h){
        var world=h.getLevel();
        for(int x=3;x<=20;x++)for(int z=3;z<=18;z++)h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(4,2,4,WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,4,Blocks.CHEST);
        BlockPos station=h.absolutePos(new BlockPos(4,2,4));
        BlockPos chest=h.absolutePos(new BlockPos(5,2,4));
        BlockPos start=h.absolutePos(new BlockPos(7,2,4));
        BlockPos base=h.absolutePos(new BlockPos(13,4,12));
        BlockPos head=base.east();
        BlockPos power=base.above();
        world.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        if(!(world.getBlockEntity(station) instanceof com.caszgamermd.golemsplus.world.WorkerStationBlockEntity table)
                ||!(world.getBlockEntity(chest) instanceof net.minecraft.world.Container stock)){
            h.fail("Missing piston Planner or storage");return;
        }
        stock.setItem(0,new ItemStack(Blocks.STICKY_PISTON,1));
        stock.setItem(1,new ItemStack(Blocks.REDSTONE_BLOCK,1));
        var owner=h.makeMockServerPlayerInLevel();
        var id=owner.getUUID();
        try{
            String baseState="minecraft:sticky_piston[extended=true,facing=east]";
            String headState="minecraft:piston_head[facing=east,short=false,type=sticky]";
            var snapshot=BuilderPlacementSnapshot.encode("Piston phase gate",List.of(
                    new LitematicFileReader.Block(base.getX(),base.getY(),base.getZ(),baseState),
                    new LitematicFileReader.Block(head.getX(),head.getY(),head.getZ(),headState),
                    new LitematicFileReader.Block(power.getX(),power.getY(),power.getZ(),"minecraft:redstone_block")),List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),
                    BuilderPlacementSnapshot.worldSpaceSite());
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("golems_plus").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            String filename="placed-gametest-piston-commission.gpb";
            java.nio.file.Files.write(folder.resolve(filename),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                    ||!table.setPlacedBuilderPlan(id,filename,"Piston phase gate",station,0,0,
                        plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,chest)){
                h.fail("Planner refused piston construction");return;
            }
            table.setBuilderMaterialsForFile(filename,plan.required());
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
            worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
            worker.assign(station,id);world.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)){
                h.fail("Could not start piston construction");return;
            }
            boolean[] sawPending={false};
            h.onEachTick(()->{
                boolean extended=BuilderPistonWork.matches(world,head,headState,
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(world.getBlockState(base).is(Blocks.STICKY_PISTON)
                        &&!extended&&table.builderActiveLayer(plan.steps())==power.getY()
                        &&!table.builderStepCompletedForMaterials(1))sawPending[0]=true;
                if(table.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.COMPLETE){
                    if(!sawPending[0]||!extended||!world.getBlockState(power).is(Blocks.REDSTONE_BLOCK)
                            ||stock.getItem(0).getCount()!=0||stock.getItem(1).getCount()!=0){
                        h.fail("Piston completed without real power, deferred verification or paid materials");return;
                    }
                    System.out.println("[PISTON PLANNER PASS] paid base, higher-layer power, verified head tick="+h.getTick());
                    h.succeed();return;
                }
                if(h.getTick()==1100)h.fail("Piston phase gate stalled cursor="+table.builderCursor()
                        +" active="+table.builderActiveStepIndex(plan.steps())+" problem="+table.problem());
            });
        }catch(Exception e){h.fail("Piston phase test setup failed "+e);}
    }

    @GameTest(maxTicks=100,skyAccess=true)
    public void recoversMissingLowerBubbleSourcesBeforeFinishingUpperClaim(GameTestHelper h){
        var world=h.getLevel();
        BlockPos base=h.absolutePos(new BlockPos(4,2,4));
        BlockPos lower=base.above(),middle=base.above(2),upper=base.above(3);
        String encoded="minecraft:bubble_column[drag=false]";
        var wanted=BuilderBlockResolver.resolve(encoded,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE).state();
        var planned=Map.of(base,Blocks.SOUL_SAND.defaultBlockState(),lower,wanted,middle,wanted,upper,wanted);
        for(int y=3;y<=5;y++){
            h.setBlock(4,y,4,Blocks.AIR);
            h.setBlock(3,y,4,Blocks.GLASS);h.setBlock(5,y,4,Blocks.GLASS);
            h.setBlock(4,y,3,Blocks.GLASS);h.setBlock(4,y,5,Blocks.GLASS);
        }
        h.setBlock(4,2,4,Blocks.SOUL_SAND);
        var claim=new BuilderConstructionPlan.Step(upper.getX(),upper.getY(),upper.getZ(),encoded,BuilderWaterPass.WATER_BUCKET);
        var repair=BuilderWaterPass.bubbleAction(world,upper,claim,planned);
        if(repair==null||repair==claim||repair.y()!=lower.getY()){
            h.fail("Upper claim did not choose its lowest missing source");return;
        }
        h.setBlock(4,3,4,Blocks.WATER);
        repair=BuilderWaterPass.bubbleAction(world,upper,claim,planned);
        if(repair==null||repair==claim||repair.y()!=middle.getY()){
            h.fail("Upper claim did not choose next missing source");return;
        }
        h.setBlock(4,4,4,Blocks.WATER);h.setBlock(4,5,4,Blocks.WATER);
        if(BuilderWaterPass.bubbleAction(world,upper,claim,planned)!=claim
                ||!BuilderWaterPass.refreshBubble(world,upper,wanted)){
            h.fail("Real generator not selected for contiguous water");return;
        }
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(world.getBlockState(lower).equals(wanted)
                    &&world.getBlockState(middle).equals(wanted)
                    &&world.getBlockState(upper).equals(wanted)){
                System.out.println("[BUILDER BUBBLE REPAIR PASS] lowest source repair and natural full-column propagation");h.succeed();
            }else if(h.getTick()==90)h.fail("Column did not naturally recover "+world.getBlockState(lower)+" "+world.getBlockState(middle)+" "+world.getBlockState(upper));
        });
    }

}
