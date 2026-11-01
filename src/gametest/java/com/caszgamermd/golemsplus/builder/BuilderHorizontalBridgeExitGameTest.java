package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** Live physics regressions for crossing from flat, owned scaffold into stone. */
public final class BuilderHorizontalBridgeExitGameTest {
    /** A supported horizontal bridge meets a finished stone block at
     * exactly the SAME elevation. Tower-only exit logic used to miss this
     * physical side step, leaving the Builder at the bridge edge forever.
     */
    @GameTest(maxTicks=350,skyAccess=true)
    public void sameHeightStoneExitFromHorizontalBridgeTop(GameTestHelper helper){
        horizontalBridgeEqualHeightExit(helper,false,false);
    }

    /** A BOTTOM=true horizontal bridge can let a real mob sink into the
     * scaffold cell. Climb that existing cell before exiting sideways.
     */
    @GameTest(maxTicks=350,skyAccess=true)
    public void sameHeightStoneExitFromInsideHorizontalBridge(GameTestHelper helper){
        horizontalBridgeEqualHeightExit(helper,true,false);
    }

    /** The Builder's feet are INSIDE the bridge, but the adjacent stone
     * landing is level with those feet. Exit sideways without first
     * climbing above the bridge and falling back down. */
    @GameTest(maxTicks=300,skyAccess=true)
    public void bridgeDismountsDirectlyToLowerStoneLedge(GameTestHelper helper){
        horizontalBridgeEqualHeightExit(helper,true,true);
    }

    private static void horizontalBridgeEqualHeightExit(
            GameTestHelper helper,boolean startInside,boolean lowerLedge){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing horizontal-bridge Planner");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            helper.fail("Cannot claim horizontal-bridge Planner");return;
        }
        BlockPos shaft=helper.absolutePos(new BlockPos(3,2,4));
        BlockPos bridge=shaft.east().atY(4);
        BlockPos ledge=bridge.east().below(lowerLedge?1:0);
        // The bridge itself has NO scaffold directly below it. Only the
        // neighboring vertical column anchors this legitimate side piece.
        for(int y=shaft.getY();y<=bridge.getY();y++)
            level.setBlock(shaft.atY(y),
                    Blocks.SCAFFOLDING.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE,0),3);
        level.setBlock(bridge,Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE,1)
                .setValue(net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM,true),3);
        level.setBlock(ledge,Blocks.STONE.defaultBlockState(),3);
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        builder.setPos(bridge.getX()+.5,
                bridge.getY()+(startInside?.10:1.0),bridge.getZ()+.5);
        level.addFreshEntity(builder);
        if(!table.registerBuilderWorker(builder.getUUID())){
            helper.fail("Cannot register bridge Builder");return;
        }
        for(int y=shaft.getY();y<=bridge.getY();y++)
            if(!table.trackScaffold(builder.getUUID(),shaft.atY(y))){
                helper.fail("Could not track anchor column");return;
            }
        if(!table.trackScaffold(builder.getUUID(),bridge)){
            helper.fail("Could not track unsupported bridge");return;
        }
        if(!level.getBlockState(bridge).is(Blocks.SCAFFOLDING)
                ||!level.getBlockState(ledge).is(Blocks.STONE)){
            helper.fail("Physical bridge/ledge fixture missing");return;
        }
        final long started=helper.getTick();
        helper.onEachTick(()->{
            // Let Minecraft settle the spawned body onto the actual bridge
            // support before asking it to navigate. The bug being tested
            // is a worker already stranded on a scaffold during a job.
            if(lowerLedge&&helper.getTick()<10)return;
            if(lowerLedge&&helper.getTick()==10)
                System.out.println("[BRIDGE SETTLED] feet="+builder.position()
                        +" grounded="+builder.onGround());
            // Native scaffold collision may gently lift a spawned mob as
            // its body settles. That is NOT a forced climb. Fail only if
            // the vertical scaffold controller explicitly starts a tower
            // ascent despite the legal foot-height stone exit.
            if(lowerLedge&&BuilderScaffolding.climbingNow(level,builder)){
                helper.fail("Started scaffold climb instead of legal "
                        +"foot-height side exit: "+builder.position());return;
            }
            if(!builder.isAlive()){
                helper.fail("Bridge Builder disappeared");return;
            }
            if(builder.onGround()
                    &&builder.blockPosition().equals(ledge.above())
                    &&Math.abs(builder.getY()-ledge.getY()-1.0)<.25){
                System.out.println("[HORIZONTAL BRIDGE EXIT] PASS inside="+startInside
                        +" physical feet="+builder.position());
                helper.succeed();return;
            }
            if(helper.getTick()-started>250){
                helper.fail("Bridge-to-same-Y-stone stalled inside="+startInside
                        +" feet="+builder.position()
                        +" motion="+BuilderScaffolding.debugClimb(builder));
                return;
            }
            if(helper.getTick()%5==0){
                BuilderScaffolding.stepOffTowardWork(
                        level,builder,table,ledge.above());
            }
        });
    }
}
