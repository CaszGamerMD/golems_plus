package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real entity physics exercises flight beyond the old 20-block grapple range. */
public final class BuilderPropellerGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=1400,skyAccess=true)
    public void propellerClimbsHundredBlocksAndReturnsWithoutAnAnchor(GameTestHelper h) {
        var world=h.getLevel();
        BlockPos start=h.absolutePos(new BlockPos(12,3,12));
        BlockPos top=start.above(100);
        world.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        h.setBlock(12,2,12,Blocks.STONE);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        boolean[] descending={false};
        Vec3[] previous={worker.position()};
        h.onEachTick(()->{
            if(!worker.isAlive()||worker.position().distanceTo(previous[0])>.45){
                h.fail("Propeller flight lost the builder or moved discontinuously");return;
            }
            previous[0]=worker.position();
            if(h.getTick()==5&&!BuilderFlight.request(world,worker,top)){
                h.fail("Could not plan anchor-free 100-block ascent");return;
            }
            if(h.getTick()<6)return;
            if(!descending[0]&&worker.position().distanceToSqr(Vec3.atBottomCenterOf(top))<.025){
                if(!worker.propellerSpinning()||!worker.isNoGravity()){
                    h.fail("Arrived without powered hover");return;
                }
                descending[0]=true;
                if(!BuilderFlight.request(world,worker,start))h.fail("Could not plan descent");
            }
            if(descending[0]&&!BuilderFlight.moving(worker)&&worker.onGround()
                    &&worker.position().distanceToSqr(Vec3.atBottomCenterOf(start))<.025){
                System.out.println("[PROPELLER PASS] 100-block ascent and descent tick="+h.getTick());
                h.succeed();
            }
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=300,skyAccess=true)
    public void propellerRejectsLongHorizontalFlightAndChainedHops(GameTestHelper h) {
        var world=h.getLevel();
        BlockPos start=h.absolutePos(new BlockPos(10,4,10));
        BlockPos near=start.east(2).above(4);
        BlockPos far=start.east(8).above(4);
        world.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        h.setBlock(10,3,10,Blocks.STONE);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        h.onEachTick(()->{
            if(h.getTick()==5){
                if(BuilderFlight.request(world,worker,far)){
                    h.fail("Long horizontal flight was accepted");return;
                }
                if(!BuilderFlight.request(world,worker,near)){
                    h.fail("Short sideways adjustment with vertical lift rejected");return;
                }
            }
            if(h.getTick()>5&&!BuilderFlight.moving(worker)
                    &&worker.position().distanceToSqr(Vec3.atBottomCenterOf(near))<.025){
                if(BuilderFlight.request(world,worker,near.east(1))){
                    h.fail("Chained hops escaped the original takeoff envelope");return;
                }
                h.succeed();
            }
        });
    }
}
