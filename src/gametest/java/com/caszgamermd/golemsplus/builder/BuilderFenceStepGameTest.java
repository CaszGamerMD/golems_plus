package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A Dome-like ledge: real floor -> fence top (+0.5) -> real floor. */
public final class BuilderFenceStepGameTest {
    @GameTest(maxTicks=350,skyAccess=true)
    public void physicallyStepsAcrossHalfBlockFenceRise(GameTestHelper h){
        runCase(h,false);
    }
    @GameTest(maxTicks=350,skyAccess=true)
    public void leavesExistingFenceTopUsingRealGroundedStart(GameTestHelper h){
        runCase(h,true);
    }
    private static void runCase(GameTestHelper h,boolean onFence){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=0;x<8;x++){
            h.setBlock(x,2,3,Blocks.STONE);
            for(int y=2;y<=5;y++){
                h.setBlock(x,y,2,Blocks.STONE);
                h.setBlock(x,y,4,Blocks.STONE);
            }
        }
        h.setBlock(3,2,3,Blocks.SPRUCE_FENCE);
        BlockPos start=h.absolutePos(new BlockPos(1,3,3));
        BlockPos goal=h.absolutePos(new BlockPos(6,3,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+(onFence?2.5:.5),
                start.getY()+(onFence?.5:0),start.getZ()+.5);
        world.addFreshEntity(mob);
        final boolean[] crossedFence={false};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()){
                h.fail("Walking test lost normal physical movement");return;
            }
            if(mob.blockPosition().getX()==start.getX()+2
                    &&mob.getY()>start.getY()+.45)crossedFence[0]=true;
            if(h.getTick()>=10&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,goal,1.0);
            if(mob.onGround()&&mob.distanceToSqr(goal.getX()+.5,
                    goal.getY(),goal.getZ()+.5)<.16){
                if(!crossedFence[0]){
                    h.fail("Worker bypassed constrained fence ledge");return;
                }
                System.out.println("[FENCE STEP] PASS physical half-block rise, "
                        +"descent and grounded arrival tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>280)
                h.fail("Half-block fence traverse stalled: "+mob.position()
                        +" "+WorkerNavigation.debugStatus(mob));
        });
    }
    @GameTest(maxTicks=260,skyAccess=true)
    public void exactRouteLeavesSideSupportedFenceRail(GameTestHelper h){sideSupport(h,true);}
    @GameTest(maxTicks=260,skyAccess=true)
    public void ordinaryRouteLeavesSideSupportedFenceRail(GameTestHelper h){sideSupport(h,false);}
    private static void sideSupport(GameTestHelper h,boolean exact){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=4;x<=6;x++)for(int z=2;z<=4;z++)
            h.setBlock(x,2,z,Blocks.STONE);
        h.setBlock(3,2,3,Blocks.SPRUCE_FENCE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.FenceBlock.EAST,true));
        BlockPos start=h.absolutePos(new BlockPos(4,3,3));
        BlockPos goal=h.absolutePos(new BlockPos(6,3,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.12,start.getY()+.5,start.getZ()+.5);
        world.addFreshEntity(mob);
        final boolean[] inspected={false};
        h.onEachTick(()->{
            if(h.getTick()<10)return;
            if(!inspected[0]){
                if(!mob.onGround()||Math.abs(mob.getY()-start.getY()-.5)>.04
                        ||!mob.blockPosition().equals(start)){
                    h.fail("Fixture must stand half a block above its tile's own floor: "
                            +mob.position());return;
                }
                inspected[0]=true;
            }
            if(h.getTick()%5==0){
                if(exact)WorkerNavigation.routeToExact(world,mob,goal,1.0);
                else WorkerNavigation.routeTo(world,mob,goal,1.0);
            }
            if(mob.onGround()&&mob.distanceToSqr(goal.getX()+.5,
                    goal.getY(),goal.getZ()+.5)<(exact?.16:1.0)){
                System.out.println("[SIDE SUPPORT] PASS "+(exact?"exact":"ordinary")
                        +" fence-rail start tick="+h.getTick());
                h.succeed();return;
            }
            if(mob.getY()<goal.getY()-.04||mob.isNoGravity()){
                h.fail("Side-supported route fell off physical ledge");return;
            }
            if(h.getTick()>220)h.fail("Side-supported start stuck "+mob.position()
                    +" "+WorkerNavigation.debugStatus(mob));
        });
    }

    /** The real upper dome mixes three different half-block surfaces;
     * native short legs may fail even where a physical one-cell step is safe.
     */
    @GameTest(maxTicks=480,skyAccess=true)
    public void traversesMixedSlabFenceAndWallRoof(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<11;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=0;x<11;x++){
            for(int y=2;y<=5;y++){
                h.setBlock(x,y,2,Blocks.STONE);
                h.setBlock(x,y,4,Blocks.STONE);
            }
            h.setBlock(x,2,3,Blocks.STONE);
        }
        h.setBlock(3,2,3,Blocks.STONE_SLAB);
        h.setBlock(5,2,3,Blocks.SPRUCE_FENCE);
        h.setBlock(7,2,3,Blocks.COBBLESTONE_WALL);
        BlockPos start=h.absolutePos(new BlockPos(1,3,3));
        BlockPos goal=h.absolutePos(new BlockPos(9,3,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(mob);
        boolean[] crossed={false,false,false};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()
                    ||mob.getY()<goal.getY()-.65){
                h.fail("Mixed roof traversal abandoned real support "+mob.position());
                return;
            }
            if(h.getTick()>=10&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,goal,1.0);
            if(mob.blockPosition().getX()==start.getX()+2
                    &&mob.getY()<start.getY()-.35)crossed[0]=true;
            if(mob.blockPosition().getX()==start.getX()+4
                    &&mob.getY()>start.getY()+.35)crossed[1]=true;
            if(mob.blockPosition().getX()==start.getX()+6
                    &&mob.getY()>start.getY()+.35)crossed[2]=true;
            if(mob.onGround()&&mob.distanceToSqr(
                    goal.getX()+.5,goal.getY(),goal.getZ()+.5)<.13){
                if(!crossed[0]||!crossed[1]||!crossed[2]){
                    h.fail("Roof route skipped half-slab/fence/wall physical contact");
                    return;
                }
                System.out.println("[MIXED ROOF] PASS slab/fence/wall "
                        +"physical traversal tick="+h.getTick());
                h.succeed();
                return;
            }
            if(h.getTick()>430){
                var nearby=WorkerNavigation.groundReachableFrom(
                        world,mob,mob.blockPosition()).stream()
                        .map(p->p.subtract(start).toShortString()).sorted().toList();
                var tiles=new java.util.ArrayList<String>();
                for(int dx=0;dx<=8;dx++){
                    BlockPos top=start.offset(dx,0,0);
                    BlockPos low=top.below();
                    tiles.add(dx+": y3="+WorkerNavigation.canStandOn(world,mob,top)
                            +" y2="+WorkerNavigation.canStandOn(world,mob,low)
                            +" floorBlock="+world.getBlockState(low));
                }
                h.fail("Mixed roof stalled at "+mob.position()
                    +" nav="+WorkerNavigation.debugStatus(mob)
                    +" reachable="+nearby+" terrain="+tiles
                    +" "+WorkerNavigation.diagnoseExecutableStand(
                        world,mob,java.util.List.of(goal)));
            }
        });
    }

}
