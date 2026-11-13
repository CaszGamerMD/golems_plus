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

}
