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
}
