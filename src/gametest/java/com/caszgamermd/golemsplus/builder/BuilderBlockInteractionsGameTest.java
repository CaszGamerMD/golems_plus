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
}
