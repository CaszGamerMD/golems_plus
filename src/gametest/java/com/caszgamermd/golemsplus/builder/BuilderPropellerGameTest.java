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
    /** Full Planner -> claim -> material -> walk -> lift -> place pipeline.
     * No hook, scaffolding, or existing platform is supplied. */
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=1600,skyAccess=true)
    public void plannerClaimsDistantFloatingBlockViaWalkThenPropeller(GameTestHelper h) {
        var world=h.getLevel();
        for(int x=3;x<=24;x++)for(int z=3;z<=20;z++)h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(4,2,4,WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,4,Blocks.CHEST);
        BlockPos station=h.absolutePos(new BlockPos(4,2,4));
        BlockPos chest=h.absolutePos(new BlockPos(5,2,4));
        BlockPos start=h.absolutePos(new BlockPos(7,2,4));
        BlockPos target=h.absolutePos(new BlockPos(19,14,12));
        world.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        if(!(world.getBlockEntity(station) instanceof com.caszgamermd.golemsplus.world.WorkerStationBlockEntity table)
                ||!(world.getBlockEntity(chest) instanceof net.minecraft.world.Container stock)){
            h.fail("Missing Planner or storage");return;
        }
        stock.setItem(0,new net.minecraft.world.item.ItemStack(Blocks.STONE,8));
        var owner=h.makeMockServerPlayerInLevel();
        var id=owner.getUUID();
        try{
            var snapshot=BuilderPlacementSnapshot.encode("Propeller access",java.util.List.of(
                    new LitematicFileReader.Block(target.getX(),target.getY(),target.getZ(),"minecraft:stone")),
                    java.util.List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),
                    BuilderPlacementSnapshot.worldSpaceSite());
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("golems_plus").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            String filename="placed-gametest-propeller-access.gpb";
            java.nio.file.Files.write(folder.resolve(filename),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                    ||!table.setPlacedBuilderPlan(id,filename,"Propeller access",station,0,0,
                        plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,chest)){
                h.fail("Planner refused propeller test job");return;
            }
            table.setBuilderMaterialsForFile(filename,plan.required());
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
            worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
            worker.assign(station,id);
            world.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)){
                h.fail("Could not start propeller construction");return;
            }
            boolean[] walked={false},flew={false};
            h.onEachTick(()->{
                if(!worker.isAlive()){h.fail("Builder lost");return;}
                if(!worker.propellerSpinning()&&worker.position().distanceToSqr(Vec3.atBottomCenterOf(start))>9)
                    walked[0]=true;
                if(worker.propellerSpinning()&&worker.getY()>start.getY()+5)flew[0]=true;
                if(world.getBlockState(target).is(Blocks.STONE)){
                    if(!walked[0]||!flew[0]){
                        h.fail("Construction did not demonstrate walk plus powered lift");return;
                    }
                    System.out.println("[PROPELLER BUILD PASS] distant floating block tick="+h.getTick());
                    h.succeed();
                }
                if(h.getTick()==1400)h.fail("Propeller construction stalled feet="+worker.position()
                        +" claim="+table.claimedStep(worker.getUUID())+" problem="+table.problem());
            });
        }catch(Exception ex){h.fail("Propeller test setup failed "+ex);}
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=250,skyAccess=true)
    public void propellerCanLiftOverLocalLedgeWithoutLongHorizontalFlight(GameTestHelper h){
        var world=h.getLevel();
        BlockPos start=h.absolutePos(new BlockPos(10,4,10));
        BlockPos goal=start.east(2);
        h.setBlock(10,3,10,Blocks.STONE);
        h.setBlock(12,3,10,Blocks.STONE);
        h.setBlock(11,4,10,Blocks.STONE);
        world.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        boolean[] climbed={false};
        h.onEachTick(()->{
            if(h.getTick()==5&&!BuilderFlight.request(world,worker,goal)){
                h.fail("No local ledge detour");return;
            }
            if(worker.getY()>start.getY()+.8)climbed[0]=true;
            if(h.getTick()>5&&!BuilderFlight.active(worker)&&worker.onGround()
                    &&worker.position().distanceToSqr(Vec3.atBottomCenterOf(goal))<.04){
                if(!climbed[0]){h.fail("Did not lift above ledge");return;}
                h.succeed();
            }
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=400,skyAccess=true)
    public void propellerWalksToRoofEdgeBeforeDescending(GameTestHelper h){
        var world=h.getLevel();
        for(int x=3;x<=20;x++)for(int z=3;z<=20;z++)h.setBlock(x,1,z,Blocks.STONE);
        for(int x=7;x<=13;x++)for(int z=7;z<=13;z++)h.setBlock(x,6,z,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(10,7,10));
        BlockPos depot=h.absolutePos(new BlockPos(5,2,5));
        world.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        boolean[] walked={false},flew={false};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(!BuilderFlight.active(worker)&&worker.onGround()
                    &&worker.getY()>start.getY()-.1
                    &&worker.position().distanceToSqr(Vec3.atBottomCenterOf(start))>4)walked[0]=true;
            if(BuilderFlight.active(worker))flew[0]=true;
            if(worker.getY()<start.getY()-3&&worker.onGround()&&!BuilderFlight.active(worker)){
                if(!walked[0]||!flew[0]){h.fail("Did not walk to launch and descend");return;}
                h.succeed();return;
            }
            if(h.getTick()%5==0&&!BuilderFlight.active(worker))
                BuilderFlight.approachDescentLaunch(world,worker,depot);
        });
    }
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=800,skyAccess=true)
    public void propellerReturnsToDepotFromWideRoofWithoutGrapple(GameTestHelper h){
        var world=h.getLevel();
        for(int x=2;x<=36;x++)for(int z=2;z<=36;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=12;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int x=5;x<=31;x++)for(int z=5;z<=31;z++)h.setBlock(x,7,z,Blocks.STONE);
        h.setBlock(3,2,18,Blocks.CHEST);
        BlockPos start=h.absolutePos(new BlockPos(18,8,18));
        BlockPos depot=h.absolutePos(new BlockPos(3,2,18));
        world.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        boolean[] flew={false};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(h.getTick()==790){h.fail("Wide roof return stalled feet="+worker.position()+" grounded="+worker.onGround()+" flight="+BuilderFlight.active(worker)+" flew="+flew[0]);return;}
            if(BuilderGrappleAccess.active(worker)){
                h.fail("Propeller return started a competing grapple");return;
            }
            if(BuilderFlight.active(worker))flew[0]=true;
            if(h.getTick()%5==0&&!BuilderReturnService.approach(world,worker,depot,.8)){
                if(!flew[0]||!worker.onGround()||worker.getY()>depot.getY()+.1){
                    h.fail("Depot return did not descend and physically arrive");return;
                }
                System.out.println("[PROPELLER DEPOT PASS] wide roof to real storage tick="+h.getTick());
                h.succeed();
            }
        });
    }    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=300,skyAccess=true)
    public void propellerEscapesOpenRailPocketToRealUpperFloor(GameTestHelper h){
        var world=h.getLevel();
        for(int x=8;x<=14;x++)for(int z=8;z<=14;z++){
            h.setBlock(x,2,z,Blocks.STONE);
            // Empty structure templates do not explicitly clear air.
            // Guarantee the open shaft and landing headroom exist.
            for(int y=3;y<=9;y++)h.setBlock(x,y,z,Blocks.AIR);
            if(x!=11||z!=11){
                h.setBlock(x,3,z,Blocks.STONE);
                h.setBlock(x,4,z,Blocks.STONE);
                h.setBlock(x,5,z,Blocks.STONE);
            }
        }
        BlockPos start=h.absolutePos(new BlockPos(11,3,11));
        world.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        h.onEachTick(()->{
            if(h.getTick()==5&&!BuilderFlight.escapePocket(world,worker,start.east(6))){
                BlockPos landing=start.east().above(3);
                h.fail("No collision-safe lift from the open pocket feet="+worker.position()
                        +" grounded="+worker.onGround()+" landing="+landing
                        +" stand="+com.caszgamermd.golemsplus.worker.WorkerNavigation.canStandOn(world,worker,landing)
                        +" shaftClear="+BuilderFlight.clear(world,worker,Vec3.atBottomCenterOf(start.above(3)))
                        +" landingClear="+BuilderFlight.clear(world,worker,Vec3.atBottomCenterOf(landing)));return;
            }
            if(h.getTick()>5&&!BuilderFlight.active(worker)&&worker.onGround()
                    &&worker.getY()>=start.getY()+2.9){
                if(BuilderGrappleAccess.active(worker)){
                    h.fail("Pocket escape used a grapple");return;
                }
                System.out.println("[PROPELLER POCKET PASS] real upper floor tick="+h.getTick());
                h.succeed();
            }
        });
    }

    /** No low hover on ordinary flat ground, nor across unsupported air.
     * These checks exercise the production walk-first fallback. */
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=120,skyAccess=true)
    public void lowTerrainHoverPrefersWalkingAndRejectsDeepGap(GameTestHelper h){
        var world=h.getLevel();
        for(int x=8;x<=18;x++)for(int y=1;y<=8;y++)h.setBlock(x,y,12,Blocks.AIR);
        for(int x=8;x<=11;x++)h.setBlock(x,2,12,Blocks.STONE);
        h.setBlock(16,2,12,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(9,3,12));
        BlockPos walk=h.absolutePos(new BlockPos(11,3,12));
        BlockPos gap=h.absolutePos(new BlockPos(16,3,12));
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        h.onEachTick(()->{
            if(h.getTick()==5){
                if(BuilderFlight.terrainToward(world,worker,java.util.List.of(walk))){
                    h.fail("Low hover replaced available walking");return;
                }
            }
            if(h.getTick()==30){
                if(BuilderFlight.terrainToward(world,worker,java.util.List.of(gap))){
                    h.fail("Low hover crossed unsupported air");return;
                }
                h.succeed();
            }
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=300,skyAccess=true)
    public void lowTerrainHoverCrossesUnevenGroundAndLands(GameTestHelper h){
        var world=h.getLevel();
        for(int x=8;x<=16;x++)for(int z=10;z<=14;z++){
            h.setBlock(x,2,z,Blocks.STONE);
            for(int y=3;y<=8;y++)h.setBlock(x,y,z,Blocks.AIR);
            if(x==11||x==13)h.setBlock(x,3,z,Blocks.STONE_SLAB);
            if(x==12)h.setBlock(x,3,z,Blocks.STONE);
        }
        BlockPos start=h.absolutePos(new BlockPos(10,3,12));
        BlockPos goal=h.absolutePos(new BlockPos(14,3,12));
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        Vec3[] previous={worker.position()};
        boolean[] powered={false};
        h.onEachTick(()->{
            if(!worker.isAlive()||worker.position().distanceTo(previous[0])>.45){
                h.fail("Low hover lost the worker or skipped physical movement");return;
            }
            previous[0]=worker.position();
            if(h.getTick()==5&&!BuilderFlight.requestTerrain(world,worker,java.util.List.of(goal))){
                h.fail("No supported low hover route across uneven ground");return;
            }
            if(h.getTick()<=5)return;
            if(worker.propellerSpinning())powered[0]=true;
            if(worker.getY()>start.getY()+2.025){
                h.fail("Terrain hover climbed more than one block above the highest ground");return;
            }
            if(!BuilderFlight.active(worker)&&worker.onGround()
                    &&worker.position().distanceToSqr(Vec3.atBottomCenterOf(goal))<.04){
                if(!powered[0]){h.fail("No physical powered terrain travel");return;}
                System.out.println("[LOW TERRAIN HOVER PASS] four-block uneven route and grounded landing");
                h.succeed();
            }
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=600,skyAccess=true)
    public void propellerRecoversWhenOriginalLandingEnvelopeCloses(GameTestHelper h){
        var world=h.getLevel();
        for(int x=7;x<=17;x++)for(int z=9;z<=15;z++)
            for(int y=0;y<=10;y++)h.setBlock(x,y,z,Blocks.AIR);
        h.setBlock(10,3,12,Blocks.STONE);
        h.setBlock(14,3,12,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(10,4,12));
        BlockPos hover=start.east(2).above(4);
        BlockPos landing=start.east(4);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        boolean[] removed={false};
        h.onEachTick(()->{
            if(h.getTick()==5&&!BuilderFlight.request(world,worker,hover)){
                h.fail("Cannot establish initial vertical hover");return;
            }
            if(h.getTick()<6)return;
            if(!removed[0]&&!BuilderFlight.moving(worker)
                    &&worker.position().distanceToSqr(Vec3.atBottomCenterOf(hover))<.04){
                h.setBlock(10,3,12,Blocks.AIR);
                removed[0]=true;
            }
            if(!removed[0])return;
            if(!BuilderFlight.active(worker)&&worker.onGround()
                    &&worker.position().distanceToSqr(Vec3.atBottomCenterOf(landing))<.04){
                System.out.println("[PROPELLER LANDING RECOVERY PASS] safe landing outside closed takeoff envelope");
                h.succeed();return;
            }
            if(h.getTick()%5==0)BuilderFlight.land(world,worker,false);
        });
    }
}
