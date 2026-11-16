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
}
