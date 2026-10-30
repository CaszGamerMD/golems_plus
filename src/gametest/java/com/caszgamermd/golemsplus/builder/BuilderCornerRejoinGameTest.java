package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A real body supported at a platform corner, with its tile center over air. */
public final class BuilderCornerRejoinGameTest {
    @GameTest(maxTicks=220,skyAccess=true)
    public void exactWorkRouteRejoinsCornerSupport(GameTestHelper h){runCase(h,true);}
    @GameTest(maxTicks=220,skyAccess=true)
    public void ordinaryReturnRouteRejoinsCornerSupport(GameTestHelper h){runCase(h,false);}
    private static void runCase(GameTestHelper h,boolean exact){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,0,z,Blocks.STONE);
        for(int x=1;x<=4;x++)for(int z=1;z<=4;z++)
            h.setBlock(x,2,z,Blocks.STONE);
        BlockPos corner=h.absolutePos(new BlockPos(5,3,5));
        BlockPos goal=h.absolutePos(new BlockPos(2,3,2));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(corner.getX()+.12,corner.getY(),corner.getZ()+.12);
        world.addFreshEntity(mob);
        final boolean[] checked={false};
        h.onEachTick(()->{
            if(Math.abs(mob.getY()-corner.getY())>.04||mob.isNoGravity()){
                h.fail("Rejoin must stay on the real platform without falling/flying: "
                        +mob.position()+" grounded="+mob.onGround());return;
            }
            if(h.getTick()<10)return;
            if(!checked[0]){
                if(!mob.onGround()
                        ||WorkerNavigation.canStandOn(world,mob,mob.blockPosition())
                        ||WorkerNavigation.supportedHitboxCorners(world,mob)==0){
                    h.fail("Fixture must have a REAL supporting hitbox corner"
                            +" while tile center is unsupported");return;
                }
                if(WorkerNavigation.selectExecutableStand(world,mob,List.of(goal))==null){
                    h.fail("Trained preview rejected supported corner re-entry");return;
                }
                checked[0]=true;
            }
            if(h.getTick()%5==0){
                if(exact)WorkerNavigation.routeToExact(world,mob,goal,1.0);
                else WorkerNavigation.routeTo(world,mob,goal,1.0);
            }
            if(mob.onGround()&&mob.distanceToSqr(goal.getX()+.5,
                    goal.getY(),goal.getZ()+.5)<.16){
                System.out.println("[CORNER REJOIN] PASS "+(exact?"exact":"ordinary")
                        +" grounded re-entry tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>180)h.fail("Corner re-entry stalled "+mob.position()
                    +" "+WorkerNavigation.debugStatus(mob));
        });
    }
    @GameTest(maxTicks=220,skyAccess=true)
    public void exactRouteCentersFromAdjacentNativeStoppingDistance(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        BlockPos goal=h.absolutePos(new BlockPos(3,2,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        // Same floor, adjacent tile: native path arrival tolerance alone
        // can stop here without centering the required exact work pose.
        mob.setPos(goal.getX()-.14,goal.getY(),goal.getZ()+.5);
        world.addFreshEntity(mob);
        h.onEachTick(()->{
            if(h.getTick()>=10&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,goal,1.0);
            if(mob.onGround()&&mob.distanceToSqr(goal.getX()+.5,
                    goal.getY(),goal.getZ()+.5)<.08*.08){
                System.out.println("[EXACT ARRIVAL] PASS adjacent tile to centered pose "
                        +"tick="+h.getTick());
                h.succeed();return;
            }
            if(mob.getY()<goal.getY()-.04||mob.isNoGravity()){
                h.fail("Exact centering lost normal supported motion");return;
            }
            if(h.getTick()>180)h.fail("Exact approach never centered "+mob.position()
                    +" "+WorkerNavigation.debugStatus(mob));
        });
    }
    /** A true center-standing worker must also be able to take a curved,
     * off-center path around an obstruction. The grid is a route hint,
     * not a requirement that every footstep end at x/z +0.5.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=480,skyAccess=true)
    public void groundedWorkerCanUseFractionalDetourAroundPillar(GameTestHelper h){
        var world=h.getLevel();
        for(int x=2;x<=9;x++)for(int z=2;z<=9;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=2;x<=9;x++)for(int z=2;z<=9;z++)
            for(int y=2;y<=4;y++)h.setBlock(x,y,z,Blocks.AIR);
        h.setBlock(5,2,5,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(4,2,4));
        BlockPos goal=h.absolutePos(new BlockPos(6,2,6));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.2,start.getY(),start.getZ()+.2);
        world.addFreshEntity(mob);
        final boolean[] previewed={false};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()||mob.getY()<start.getY()-.07){
                h.fail("Fractional detour lost real support "+mob.position());return;
            }
            if(h.getTick()==15){
                var path=WorkerNavigation.previewLocalFreeSpaceRoute(
                        world,mob,goal);
                if(!mob.onGround()||path.isEmpty()
                        ||path.stream().noneMatch(p->
                            Math.abs(p.x-Math.floor(p.x)-.5)>.07
                            ||Math.abs(p.z-Math.floor(p.z)-.5)>.07)){
                    h.fail("Off-center obstacle detour not available "
                            +"feet="+mob.position()+" steps="+path);return;
                }
                previewed[0]=true;
            }
            if(h.getTick()>=20&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,goal,1.0);
            if(previewed[0]&&mob.onGround()
                    &&mob.distanceToSqr(goal.getX()+.5,
                            goal.getY(),goal.getZ()+.5)<.12){
                h.succeed();return;
            }
            if(h.getTick()>440)
                h.fail("Off-center obstacle navigation stalled "
                        +mob.position()+" "+WorkerNavigation.debugStatus(mob));
        });
    }
    /** Nonstandard-angle navigation: a 0.65-wide real golem stranded
     * at a platform corner must use diagonal or offset quarter-block
     * recovery instead of requiring integer block-center/cardinal steps.
     * No unsupported mid-air stride or ignored collision permitted.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=420,skyAccess=true)
    public void diagonalQuarterBlockCornerRejoin(GameTestHelper h){
        var world=h.getLevel();
        for(int x=3;x<=10;x++)for(int z=3;z<=10;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=3;x<=7;x++)for(int z=3;z<=7;z++)
            h.setBlock(x,2,z,Blocks.STONE);
        for(int x=3;x<=10;x++)for(int z=3;z<=10;z++)
            for(int y=3;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        BlockPos start=h.absolutePos(new BlockPos(8,3,8));
        BlockPos supportedCorner=h.absolutePos(new BlockPos(7,3,7));
        BlockPos goal=h.absolutePos(new BlockPos(5,3,5));
        // The GameTest bounds may cross a chunk border under randomized
        // coordinates and rotations. Force the spawn's actual chunk and
        // explicitly set the single stone support at the sampled corner.
        world.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,
                        start.getZ()>>4),2);
        world.setBlock(supportedCorner.below(),Blocks.STONE.defaultBlockState(),3);
        world.setBlock(start.below(),Blocks.AIR.defaultBlockState(),3);
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        // GameTests ROTATE their structures. The supported corner might
        // be NE, NW, SE or SW in actual world coordinates. Offset toward
        // the real adjoining platform rather than hardcoding +0.08.
        double fx=supportedCorner.getX()<start.getX()?.08:.92;
        double fz=supportedCorner.getZ()<start.getZ()?.08:.92;
        mob.setPos(start.getX()+fx,start.getY(),start.getZ()+fz);
        world.addFreshEntity(mob);
        h.onEachTick(()->{
            if(mob.isNoGravity()||!mob.isAlive()
                    ||mob.getY()<start.getY()-.07){
                h.fail("Angle recovery fell or flew at "+mob.position());return;
            }
            if(h.getTick()==12){
                // A lower floor one block beneath an unsupported
                // corner may be legal at the BLOCK CENTER, but that
                // does not make it a valid stance at the worker's
                // current Y. Compare the actual collision height.
                double centerFloor=WorkerNavigation.surveyFloorHeight(
                        world,mob.blockPosition());
                boolean currentCenter=WorkerNavigation.canStandOn(
                        world,mob,mob.blockPosition())
                        &&Math.abs(centerFloor-mob.getY())<.08;
                if(!mob.onGround()||currentCenter
                        ||WorkerNavigation.supportedHitboxCorners(world,mob)==0){
                    h.fail("Invalid fractional corner fixture "+mob.position()
                            +" grounded="+mob.onGround()
                            +" centerFloor="+centerFloor
                            +" currentCenter="+currentCenter
                            +" cornerMask="+WorkerNavigation.supportedHitboxCorners(
                                    world,mob)
                            +" stone="+world.getBlockState(supportedCorner.below())
                            +" supportXYZ="+supportedCorner.below()
                            +" chunkLoaded="+world.hasChunkAt(start)
                            +" feetXYZ="+mob.blockPosition());
                    return;
                }
            }
            if(h.getTick()>12&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,goal,1.0);
            if(mob.onGround()&&mob.distanceToSqr(
                    goal.getX()+.5,goal.getY(),goal.getZ()+.5)<.13){
                System.out.println("[NONSTANDARD ANGLE] PASS physical "
                        +"fractional diagonal rejoin in "+h.getTick()+" ticks");
                h.succeed();return;
            }
            if(h.getTick()>385)h.fail("Fractional diagonal failed "
                    +mob.position()+" "+WorkerNavigation.debugStatus(mob)
                    +" "+WorkerNavigation.diagnoseGroundedEdge(world,mob,goal));
        });
    }

    /** The dome roof sometimes requires a diagonal one-block descent
     * from an offset edge before returning upward around architecture.
     * Confirm the descent is a real gravity-and-collision movement.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=500,skyAccess=true)
    public void diagonallyDescendsOntoRealSupport(GameTestHelper h){
        var world=h.getLevel();
        for(int x=3;x<=11;x++)for(int z=3;z<=11;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=4;x<=6;x++)for(int z=4;z<=6;z++)
            h.setBlock(x,2,z,Blocks.STONE);
        for(int x=4;x<=11;x++)for(int z=4;z<=11;z++)
            for(int y=3;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        BlockPos start=h.absolutePos(new BlockPos(6,3,6));
        BlockPos lower=h.absolutePos(new BlockPos(7,2,7));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.7,start.getY(),start.getZ()+.7);
        world.addFreshEntity(mob);
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()
                    ||mob.getY()<lower.getY()-.11){
                h.fail("Diagonal descent fell below valid landing "
                        +mob.position());return;
            }
            if(h.getTick()>12&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,lower,1.0);
            if(mob.onGround()&&mob.distanceToSqr(
                    lower.getX()+.5,lower.getY(),lower.getZ()+.5)<.14){
                System.out.println("[ANGLED DESCENT] PASS real one-block "
                        +"diagonal drop in "+h.getTick()+" game ticks");
                h.succeed();return;
            }
            if(h.getTick()>455)h.fail("Diagonal descent stalled "
                    +mob.position()+" "+WorkerNavigation.debugStatus(mob));
        });
    }

    /** A face-aligned step is required when the worker approaches a
     * one-block-tall platform from its diagonal CORNER. It must first
     * reach a legal side, then make a normal one-block jump, and arrive
     * without creative flight or teleport.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=420,skyAccess=true)
    public void climbsOneBlockPlatformCornerByRealSideJump(GameTestHelper h){
        var world=h.getLevel();
        for(int x=3;x<=11;x++)for(int z=3;z<=11;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=7;x<=9;x++)for(int z=7;z<=9;z++)
            h.setBlock(x,2,z,Blocks.STONE);
        for(int x=4;x<=11;x++)for(int z=4;z<=11;z++)
            for(int y=3;y<=5;y++)
                h.setBlock(x,y,z,Blocks.AIR);
        BlockPos corner=h.absolutePos(new BlockPos(6,2,6));
        BlockPos goal=h.absolutePos(new BlockPos(8,3,8));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(corner.getX()+.62,corner.getY(),corner.getZ()+.62);
        world.addFreshEntity(mob);
        boolean[] usedRaisedFloor={false};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()
                    ||mob.getY()<corner.getY()-.18){
                h.fail("Corner jump left real floor or disabled gravity: "
                        +mob.position());return;
            }
            if(h.getTick()>=15&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,goal,1);
            if(mob.getY()>corner.getY()+.75)usedRaisedFloor[0]=true;
            if(mob.onGround()&&mob.distanceToSqr(
                    goal.getX()+.5,goal.getY(),goal.getZ()+.5)<.12){
                if(!usedRaisedFloor[0]){
                    h.fail("Arrival skipped the physical ascent");return;
                }
                System.out.println("[CORNER CLIMB] PASS grounded side-aligned"
                        +" one-block jump from diagonal corner in "
                        +h.getTick()+" game ticks");
                h.succeed();return;
            }
            if(h.getTick()>380)
                h.fail("Corner climb navigation stalled at "+mob.position()
                        +" "+WorkerNavigation.debugStatus(mob));
        });
    }

}
