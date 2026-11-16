package com.caszgamermd.golemsplus.builder;
import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** A one-wide corridor with no alternative route or flight. */
public final class BuilderCircuitWalkGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=800,skyAccess=true)
    public void crossRailsAndDustRepeatedly(GameTestHelper h){
        var level=h.getLevel();
        for(int x=4;x<=19;x++){
            h.setBlock(x,1,10,Blocks.STONE);
            for(int y=2;y<=4;y++){
                h.setBlock(x,y,9,Blocks.STONE);
                h.setBlock(x,y,11,Blocks.STONE);
                h.setBlock(x,y,10,Blocks.AIR);
            }
        }
        for(int x:new int[]{4,19})for(int y=2;y<=4;y++)h.setBlock(x,y,10,Blocks.STONE);
        h.setBlock(7,2,10,Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE,RailShape.EAST_WEST));
        h.setBlock(8,2,10,Blocks.POWERED_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE,RailShape.EAST_WEST));
        h.setBlock(9,2,10,Blocks.DETECTOR_RAIL.defaultBlockState().setValue(DetectorRailBlock.SHAPE,RailShape.EAST_WEST));
        h.setBlock(10,2,10,Blocks.ACTIVATOR_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE,RailShape.EAST_WEST));
        h.setBlock(12,1,10,Blocks.REDSTONE_BLOCK);
        h.setBlock(12,2,10,Blocks.REDSTONE_WIRE);
        h.setBlock(13,2,10,Blocks.REDSTONE_WIRE);
        h.setBlock(16,2,10,Blocks.REDSTONE_WIRE);
        BlockPos start=h.absolutePos(new BlockPos(5,2,10)),finish=h.absolutePos(new BlockPos(18,2,10));
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPropellerEnabled(false);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var crossed=new java.util.HashSet<BlockPos>();
        int[] completed={0};
        int[] circuit={7,8,9,10,12,13,16};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(h.getTick()==5){
                worker.setOnGround(true);
                for(int x:circuit){
                    BlockPos at=h.absolutePos(new BlockPos(x,2,10));
                    if(!WorkerNavigation.canStandOn(level,worker,at)
                            ||Math.abs(WorkerNavigation.surveyFloorHeight(level,at)-start.getY())>.01){
                        h.fail("Circuit tile changed ordinary floor: "+at);return;
                    }
                }
                if(level.getBlockState(h.absolutePos(new BlockPos(12,2,10))).getValue(BlockStateProperties.POWER)==0
                        ||level.getBlockState(h.absolutePos(new BlockPos(16,2,10))).getValue(BlockStateProperties.POWER)!=0){
                    h.fail("Powered/unpowered wire fixture invalid");return;
                }
                var path=worker.getNavigation().createPath(finish,0);
                if(path==null||!path.canReach()){
                    h.fail("Native path cannot enter rail corridor from ordinary floor");return;
                }
            }
            if(!worker.isAlive()||worker.isNoGravity()||BuilderFlight.active(worker)
                    ||Math.abs(worker.getY()-start.getY())>.15){
                h.fail("Flat crossing jumped, dropped or flew: "+worker.position());return;
            }
            crossed.add(worker.blockPosition().immutable());
            BlockPos goal=completed[0]%2==0?finish:start;
            if(worker.position().distanceToSqr(Vec3.atBottomCenterOf(goal))<.10){
                for(int x:circuit)if(!crossed.contains(h.absolutePos(new BlockPos(x,2,10)))){
                    h.fail("Skipped circuit tile "+x);return;
                }
                completed[0]++;crossed.clear();
                if(completed[0]==6){
                    System.out.println("[CIRCUIT WALK PASS] six grounded crossings of all four rail types and powered/unpowered dust");
                    h.succeed();return;
                }
                WorkerNavigation.stop(worker);
                goal=completed[0]%2==0?finish:start;
            }
            if(h.getTick()%5==0)WorkerNavigation.routeToExact(level,worker,goal,1.0);
            if(h.getTick()==790)h.fail("Circuit crossing stalled: "+worker.position()+" completed="+completed[0]);
        });
    }
}
