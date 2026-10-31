package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A Dome-like ledge: real floor -> fence top (+0.5) -> real floor. */
public final class BuilderFenceStepGameTest {
    /** A real two-block drop is valid when the NEXT tile has solid
     * footing. It is never a two-block jump in the reverse direction.
     */
    @GameTest(maxTicks=280,skyAccess=true)
    public void descendsTwoBlocksOntoVerifiedLowerLanding(GameTestHelper h){
        var world=h.getLevel();
        h.setBlock(3,3,3,Blocks.STONE);
        h.setBlock(4,1,3,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(3,4,3));
        BlockPos landing=h.absolutePos(new BlockPos(4,2,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(mob);
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()){
                h.fail("Drop test Builder lost ordinary gravity");return;
            }
            if(h.getTick()==15&&WorkerNavigation.selectExecutableStand(
                    world,mob,java.util.List.of(landing))==null){
                h.fail("Trained planner rejected legal two-block descent");return;
            }
            if(h.getTick()>=16&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,landing,1.0);
            if(mob.onGround()&&mob.distanceToSqr(
                    landing.getX()+.5,landing.getY(),landing.getZ()+.5)<.13){
                System.out.println("[TWO BLOCK DROP] PASS real gravity and "
                        +"solid lower landing tick="+h.getTick());
                h.succeed();
            }
            if(h.getTick()>245)
                h.fail("Two-block descent stalled "+mob.position()
                        +" nav="+WorkerNavigation.debugStatus(mob));
        });
    }

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
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=480,skyAccess=true)
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
        // The 1.5-high wall is a BLOCKED STAIR, not a short hop.
        // Give the worker a solid, one-block-wide side ledge around it;
        // reaching the far side must follow that actual safe path.
        for(int x=6;x<=8;x++)for(int y=3;y<=5;y++)
            h.setBlock(x,y,4,Blocks.AIR);
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
            // A golem can stride over a lower slab without settling on
            // its top. It still physically crosses that tile under normal
            // gravity; require actual traversal, not an arbitrary pause.
            if(mob.blockPosition().getX()==start.getX()+2)
                crossed[0]=true;
            if(mob.blockPosition().getX()==start.getX()+4
                    &&mob.getY()>start.getY()+.35)crossed[1]=true;
            // The planner must not ASSUME wall tops are ordinary nodes,
            // but genuine normal-physics movement over an elevated wall
            // is still permitted when the entity really manages it.
            if(mob.blockPosition().getX()==start.getX()+6
                    &&(mob.blockPosition().getZ()==start.getZ()+1
                        ||mob.getY()>start.getY()+.35))crossed[2]=true;
            if(mob.onGround()&&mob.distanceToSqr(
                    goal.getX()+.5,goal.getY(),goal.getZ()+.5)<.13){
                if(!crossed[0]||!crossed[1]||!crossed[2]){
                    h.fail("Roof route missed monitored terrain: slab="+crossed[0]
                            +" fence="+crossed[1]+" wall="+crossed[2]
                            +" finalFeet="+mob.position());
                    return;
                }
                System.out.println("[MIXED ROOF] PASS slab/fence and "
                        +"grounded detour around 1.5-high wall tick="+h.getTick());
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

    @GameTest(maxTicks=130,skyAccess=true)
    public void doesNotSelectOneAndHalfBlockWallAsWalkingWaypoint(
            GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        BlockPos wall=h.absolutePos(new BlockPos(3,2,3));
        h.setBlock(3,2,3,Blocks.MOSSY_COBBLESTONE_WALL);
        BlockPos top=wall.above();
        BlockPos ordinary=h.absolutePos(new BlockPos(2,2,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(ordinary.getX()+.5,ordinary.getY(),ordinary.getZ()+.5);
        world.addFreshEntity(mob);
        h.onEachTick(()->{
            if(h.getTick()!=15)return;
            if(!mob.onGround()){
                h.fail("Builder is not grounded for wall collision test");return;
            }
            if(WorkerNavigation.canStandOn(world,mob,top)
                    ||WorkerNavigation.selectExecutableStand(
                            world,mob,java.util.List.of(top))!=null
                    ||!WorkerNavigation.canStandOn(world,mob,ordinary)){
                h.fail("1.5-block wall wrongly treated as reachable "
                        +"walking step instead of obstacle");return;
            }
            System.out.println("[WALL NO-CLIMB] PASS wall top excluded "
                    +"without hiding ordinary support");
            h.succeed();
        });
    }

    @GameTest(maxTicks=130,skyAccess=true)
    public void alreadyOnWallTopCanStillFindWayDown(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(3,2,3,Blocks.MOSSY_COBBLESTONE_WALL);
        // A real neighboring raised foothold has the SAME collision top
        // as a 1.5-wall. We are not asking the one-step mover to drop
        // 1.5 blocks onto distant ground in a single horizontal sweep.
        h.setBlock(4,2,3,Blocks.STONE);
        h.setBlock(4,3,3,Blocks.STONE_SLAB);
        BlockPos top=h.absolutePos(new BlockPos(3,3,3));
        BlockPos ground=h.absolutePos(new BlockPos(4,3,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(top.getX()+.5,top.getY()+.5,top.getZ()+.5);
        world.addFreshEntity(mob);
        h.onEachTick(()->{
            if(h.getTick()!=15)return;
            if(!mob.onGround()||!WorkerNavigation.canStandOn(
                    world,mob,top)){
                h.fail("Actual grounded wall-top worker was stranded "
                        +mob.position());return;
            }
            if(WorkerNavigation.selectExecutableStand(
                    world,mob,java.util.List.of(ground))==null){
                h.fail("No path to leave a wall already occupied by worker");
                return;
            }
            System.out.println("[WALL ESCAPE] PASS real current support "
                    +"remains an allowed path start");
            h.succeed();
        });
    }

    /** A route that starts WEST (away from target EAST), descends one
     * full block, circles a solid obstruction, then climbs back onto a
     * real elevated platform. No straight-line "closer every step" rule.
     * The golem must physically walk every part, never teleport/fly.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1000,skyAccess=true)
    public void detoursAwayAndDownBeforeReachingHigherWorkPlatform(
            GameTestHelper h){
        var world=h.getLevel();
        BlockPos start=h.absolutePos(new BlockPos(10,10,10));
        BlockPos goal=h.absolutePos(new BlockPos(15,10,10));
        // A narrow island at the original height; straight east is VOID.
        for(int x=8;x<=10;x++)
            h.setBlock(x,9,10,Blocks.STONE);
        // The ONLY connected route first goes west, then DOWN to the
        // low side passage, around the gap, then UP onto the far platform.
        // The passage starts only at west X8, then turns SOUTH one
        // extra tile before heading east. Without this turn, the golem
        // could step directly SOUTH from start and never move backward.
        h.setBlock(8,8,11,Blocks.STONE);
        for(int x=8;x<=13;x++)
            h.setBlock(x,8,12,Blocks.STONE);
        h.setBlock(14,9,12,Blocks.STONE);
        h.setBlock(14,9,11,Blocks.STONE);
        h.setBlock(14,9,10,Blocks.STONE);
        h.setBlock(15,9,10,Blocks.STONE);
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(mob);
        boolean[] wentWest={false},wentDown={false},wentUp={false};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()){
                h.fail("Detour worker vanished or disabled gravity");return;
            }
            if(h.getTick()==16){
                var route=com.caszgamermd.golemsplus.worker.WorkerRoutePlanner.plan(
                        start,java.util.List.of(goal),
                        tile->WorkerNavigation.canStandOn(world,mob,tile),
                        tile->(double)tile.getY(),null);
                if(route==null||!route.complete()||route.steps().isEmpty()
                        ||route.steps().get(0).getX()>=start.getX()
                        ||route.steps().stream().noneMatch(
                                tile->tile.getY()<start.getY())){
                    h.fail("Terrain planner refused real away/down/up detour: "
                            +route);return;
                }
            }
            if(h.getTick()>=18&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,goal,1.0);
            if(mob.getX()<start.getX()-.55)wentWest[0]=true;
            if(mob.getY()<start.getY()-.70)wentDown[0]=true;
            if(wentDown[0]&&mob.getY()>start.getY()-.15
                    &&mob.getX()>start.getX()+1)wentUp[0]=true;
            if(mob.onGround()&&mob.distanceToSqr(
                    goal.getX()+.5,goal.getY(),goal.getZ()+.5)<.13){
                if(!wentWest[0]||!wentDown[0]||!wentUp[0]){
                    h.fail("Reached goal without proving physical downward "
                            +"detour: west="+wentWest[0]+" down="+wentDown[0]
                            +" up="+wentUp[0]);return;
                }
                System.out.println("[DETOUR] PASS moved WEST away from EAST "
                        +"goal, DOWN one block, around void, and UP to work "
                        +"platform tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>930)
                h.fail("Physical away/down/up detour stalled pos="
                        +mob.position()+" nav="+WorkerNavigation.debugStatus(mob));
        });
    }

    @GameTest(maxTicks=100,skyAccess=true)
    public void blockedFirstStepReplansAroundWithoutChangingGoal(
            GameTestHelper h){
        BlockPos start=h.absolutePos(new BlockPos(2,2,3));
        BlockPos goal=h.absolutePos(new BlockPos(6,2,3));
        java.util.Set<BlockPos> valid=new java.util.HashSet<>();
        for(int x=1;x<=7;x++)for(int z=1;z<=6;z++)
            valid.add(h.absolutePos(new BlockPos(x,2,z)));
        BlockPos rejected=start.east();
        var route=com.caszgamermd.golemsplus.worker.WorkerRoutePlanner.plan(
                start,java.util.List.of(goal),valid::contains,
                tile->(double)tile.getY(),null,tile->0.0,
                null,java.util.Set.of(rejected));
        if(route==null||!route.complete()||route.steps().isEmpty()
                ||route.steps().contains(rejected)
                ||!route.steps().get(route.steps().size()-1).equals(goal)){
            h.fail("Excluded first step caused goal abandonment: "+route);
            return;
        }
        System.out.println("[DETOUR] PASS rejected FIRST edge, preserved "
                +"target, took alternate terrain-weighted route");
        h.succeed();
    }

    /** Real glass and stained glass have the same solid walking surface
     * as stone. Confirm both the server's collision model and actual
     * physically controlled Builder movement, not just a diagram.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=340,skyAccess=true)
    public void traversesActualClearAndMagentaGlass(GameTestHelper h){
        var world=h.getLevel();
        var magenta=BuilderBlockResolver.resolve(
                "minecraft:magenta_stained_glass",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(magenta==null){
            h.fail("Could not resolve real stained glass fixture");return;
        }
        for(int x=3;x<=12;x++){
            h.setBlock(x,2,3,x%2==0
                    ?Blocks.GLASS:magenta.state().getBlock());
            for(int y=2;y<=5;y++){
                h.setBlock(x,y,2,Blocks.AIR);
                h.setBlock(x,y,4,Blocks.AIR);
                // Empty GameTest arenas can retain barrier filler.
                // Glass collision is only meaningful with actual air
                // above it: clear the planned work corridor itself.
                if(y>=3)h.setBlock(x,y,3,Blocks.AIR);
            }
        }
        BlockPos start=h.absolutePos(new BlockPos(3,3,3));
        BlockPos goal=h.absolutePos(new BlockPos(12,3,3));
        world.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,
                        start.getZ()>>4),4);
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(mob);
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()
                    ||mob.getY()<start.getY()-.3){
                h.fail("Builder fell through ordinary glass");return;
            }
            if(h.getTick()==15){
                for(int x=3;x<=12;x++){
                    var p=h.absolutePos(new BlockPos(x,3,3));
                    if(!WorkerNavigation.canStandOn(world,mob,p)){
                        var state=world.getBlockState(p.below());
                        var shape=state.getCollisionShape(world,p.below());
                        h.fail("Glass standing check rejected at "
                                +p.subtract(start)+" below="+state
                                +" collisionMax="+(shape.isEmpty()?0:shape.max(
                                        net.minecraft.core.Direction.Axis.Y))
                                +" yFeet="+mob.getY()
                                +" grounded="+mob.onGround()
                                +" loaded="+world.hasChunkAt(p)
                                +" head="+world.getBlockState(p)
                                +" floor="+WorkerNavigation.surveyFloorHeight(
                                        world,p));return;
                    }
                }
                if(!WorkerNavigation.hasGroundRouteFrom(
                        world,mob,start,java.util.List.of(goal))){
                    h.fail("A* rejected contiguous real glass floor");return;
                }
            }
            if(h.getTick()>=15&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,goal,1.0);
            if(mob.onGround()&&mob.distanceToSqr(
                    goal.getX()+.5,goal.getY(),goal.getZ()+.5)<.15){
                System.out.println("[GLASS WALK] PASS physical clear/magenta"
                        +" glass ticks="+h.getTick()
                        +" feet="+mob.blockPosition().subtract(start));
                h.succeed();return;
            }
            if(h.getTick()>310)h.fail("Glass floor route stalled: "
                    +mob.position()+" "+WorkerNavigation.debugStatus(mob));
        });
    }

}
