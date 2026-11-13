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
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=650,skyAccess=true)
    public void propellerClimbsThirtyBlocksAndReturnsWithoutAnAnchor(GameTestHelper h) {
        var world=h.getLevel();
        BlockPos start=h.absolutePos(new BlockPos(12,3,12));
        BlockPos top=start.above(30);
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
                h.fail("Could not plan anchor-free 30-block ascent");return;
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
                System.out.println("[PROPELLER PASS] 30-block ascent and descent tick="+h.getTick());
                h.succeed();
            }
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=500,skyAccess=true)
    public void propellerFliesAroundWallAndValidatesHoverPlacement(GameTestHelper h) {
        var world=h.getLevel();
        BlockPos start=h.absolutePos(new BlockPos(10,4,10));
        BlockPos goal=h.absolutePos(new BlockPos(18,4,10));
        world.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        for(int y=1;y<=9;y++)for(int z=7;z<=13;z++)h.setBlock(14,y,z,Blocks.STONE);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        Vec3[] previous={worker.position()};
        h.onEachTick(()->{
            if(!world.noCollision(worker,worker.getBoundingBox())
                    ||worker.position().distanceTo(previous[0])>.45){
                h.fail("Propeller crossed a solid wall or teleported");return;
            }
            previous[0]=worker.position();
            if(h.getTick()==5&&!BuilderFlight.request(world,worker,goal)){
                h.fail("Could not route around wall");return;
            }
            if(h.getTick()>5&&!BuilderFlight.moving(worker)
                    &&worker.position().distanceToSqr(Vec3.atBottomCenterOf(goal))<.025){
                // The target is through the wall but within five-block hand reach.
                BlockPos target=goal.west(4);
                if(!BuilderFlight.safeWorkPose(world,worker,target,worker.position())
                        ||BuilderFlight.safeWorkPose(world,worker,goal.west(6),worker.position())){
                    h.fail("Hover placement did not preserve five-block reach");return;
                }
                System.out.println("[PROPELLER PASS] collision-safe wall detour and hover reach");
                h.succeed();
            }
        });
    }
}
