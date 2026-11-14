package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Isolated recovery fixture: no neighboring long test builds. */
public final class BuilderPropellerRecoveryGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=200,skyAccess=true)
    public void poweredExitPreservesScaffoldAndReachesSolidLanding(GameTestHelper h){
        var level=h.getLevel();
        for(int x=6;x<=16;x++)for(int z=6;z<=16;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=11;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int y=2;y<=6;y++)h.setBlock(10,y,10,Blocks.SCAFFOLDING);
        for(int x=12;x<=15;x++)for(int z=9;z<=12;z++)h.setBlock(x,6,z,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(10,7,10));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        Vec3[] anchor={null};
        h.onEachTick(()->{
            if(h.getTick()==5){
                anchor[0]=worker.position();
                if(!BuilderFlight.exitStalledScaffold(level,worker)){
                    h.fail("No physical powered exit from scaffold");return;
                }
            }
            if(h.getTick()<=5||anchor[0]==null)return;
            if(h.getTick()==190){
                h.fail("Powered escape stalled feet="+worker.position()+" active="+BuilderFlight.active(worker)
                        +" moving="+BuilderFlight.moving(worker)+" grounded="+worker.onGround()
                        +" gravity="+!worker.isNoGravity()+" floor="+level.getBlockState(worker.blockPosition().below()));
                return;
            }
            double dx=worker.getX()-anchor[0].x,dz=worker.getZ()-anchor[0].z;
            if(dx*dx+dz*dz>4.001){h.fail("Recovery exceeded 2m flight envelope");return;}
            if(!BuilderFlight.active(worker)&&worker.onGround()){
                if(!level.getBlockState(worker.blockPosition().below()).is(Blocks.STONE)
                        ||!level.getBlockState(start.below()).is(Blocks.SCAFFOLDING)){
                    h.fail("Recovery did not land on real stone or destroyed scaffold");return;
                }
                System.out.println("[POWERED SCAFFOLD EXIT PASS] tick="+h.getTick()+" landing="+worker.blockPosition());
                h.succeed();
            }
        });
    }
    /** A real 48-block construction job: the builder should load its
     * active-layer bulk material in one physical depot visit. */
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=1800,skyAccess=true)
    public void stockedBuilderCompletesBulkLayerWithoutFrequentDepotTrips(GameTestHelper h){
        var level=h.getLevel();
        for(int x=2;x<=27;x++)for(int z=2;z<=25;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=8;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(4,2,4,WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,4,Blocks.CHEST);
        BlockPos station=h.absolutePos(new BlockPos(4,2,4));
        BlockPos depot=h.absolutePos(new BlockPos(5,2,4));
        BlockPos start=h.absolutePos(new BlockPos(7,2,4));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        if(!(level.getBlockEntity(station) instanceof com.caszgamermd.golemsplus.world.WorkerStationBlockEntity table)
                ||!(level.getBlockEntity(depot) instanceof net.minecraft.world.Container stock)){
            h.fail("Missing bulk construction depot");return;
        }
        stock.setItem(0,new net.minecraft.world.item.ItemStack(Blocks.SMOOTH_STONE,64));
        stock.setItem(1,new net.minecraft.world.item.ItemStack(Blocks.SCAFFOLDING,64));
        stock.setItem(2,new net.minecraft.world.item.ItemStack(WorksiteContent.GRAPPLE_HOOK));
        stock.setItem(3,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET));
        var cells=new java.util.ArrayList<LitematicFileReader.Block>();
        var targets=new java.util.ArrayList<BlockPos>();
        for(int x=12;x<20;x++)for(int z=12;z<18;z++){
            BlockPos p=h.absolutePos(new BlockPos(x,2,z));
            targets.add(p);
            cells.add(new LitematicFileReader.Block(p.getX(),p.getY(),p.getZ(),"minecraft:smooth_stone"));
        }
        var owner=h.makeMockServerPlayerInLevel();var id=owner.getUUID();
        try{
            var snapshot=BuilderPlacementSnapshot.encode("Bulk layer stock",cells,java.util.List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),BuilderPlacementSnapshot.worldSpaceSite());
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            String file="placed-gametest-bulk-layer.gpb";java.nio.file.Files.write(folder.resolve(file),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                    ||!table.setPlacedBuilderPlan(id,file,"Bulk layer stock",station,0,0,plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,depot)){
                h.fail("Bulk layer Planner refused job");return;
            }
            table.setBuilderMaterialsForFile(file,plan.required());
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
            worker.assign(station,id);level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)){
                h.fail("Bulk layer job refused start");return;
            }
            int[] peak={0};
            h.onEachTick(()->{
                peak[0]=Math.max(peak[0],worker.carriedCount(net.minecraft.world.item.Items.SMOOTH_STONE));
                long built=targets.stream().filter(p->level.getBlockState(p).is(Blocks.SMOOTH_STONE)).count();
                if(built==48){
                    if(peak[0]<40){h.fail("Bulk layer only staged "+peak[0]+" blocks");return;}
                    int remaining=worker.carriedCount(net.minecraft.world.item.Items.SMOOTH_STONE);
                    for(int slot=0;slot<stock.getContainerSize();slot++)
                        if(stock.getItem(slot).is(net.minecraft.world.item.Items.SMOOTH_STONE))remaining+=stock.getItem(slot).getCount();
                    if(remaining!=16){h.fail("Physical material conservation failed: remaining="+remaining);return;}
                    System.out.println("[BULK LAYER STOCK PASS] paid 48 blocks, peak pouch="+peak[0]+" tick="+h.getTick());
                    h.succeed();return;
                }
                if(h.getTick()==1700)h.fail("Bulk layer stalled built="+built+" peak="+peak[0]+" problem="+table.problem());
            });
        }catch(Exception e){h.fail("Bulk stock test setup failed "+e);}
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=160,skyAccess=true)
    public void nearbyCrewSharesOnlyRealSurplusWithoutRemoteTransfer(GameTestHelper h){
        var level=h.getLevel();
        for(int x=5;x<=18;x++)for(int z=5;z<=16;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(6,2,6,WorksiteContent.BUILDER_TABLE);
        BlockPos station=h.absolutePos(new BlockPos(6,2,6));
        BlockPos near=h.absolutePos(new BlockPos(10,2,10));
        BlockPos far=h.absolutePos(new BlockPos(16,2,10));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(near.getX()>>4,near.getZ()>>4),3);
        if(!(level.getBlockEntity(station) instanceof com.caszgamermd.golemsplus.world.WorkerStationBlockEntity table)){
            h.fail("Missing Planner");return;
        }
        var owner=h.makeMockServerPlayerInLevel();var id=owner.getUUID();
        if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,2)){h.fail("Crew setup failed");return;}
        var receiver=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        var donor=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        receiver.setPos(near.getX()+.5,near.getY(),near.getZ()+.5);
        donor.setPos(far.getX()+.5,far.getY(),far.getZ()+.5);
        // Register real entities without starting their idle return AI.
        level.addFreshEntity(receiver);level.addFreshEntity(donor);
        if(!table.registerBuilderWorker(receiver.getUUID())||!table.registerBuilderWorker(donor.getUUID())){
            h.fail("Worker registration failed");return;
        }
        var material=net.minecraft.world.item.Items.SMOOTH_STONE;
        donor.setItem(0,new net.minecraft.world.item.ItemStack(material,2));
        h.onEachTick(()->{
            if(h.getTick()==5){
                if(BuilderSupplyInventory.borrowNearby(level,receiver,table,material,null,1)
                        ||receiver.carriedCount(material)!=0){h.fail("Remote material transfer");return;}
            }
            if(h.getTick()>5&&h.getTick()%5==0
                    &&receiver.distanceToSqr(donor)>4){
                com.caszgamermd.golemsplus.worker.WorkerNavigation.routeToExact(
                        level,donor,near.east(),1.0);
            }
            if(h.getTick()>5&&receiver.distanceToSqr(donor)<=4
                    &&receiver.onGround()&&donor.onGround()){
                if(!BuilderSupplyInventory.borrowNearby(level,receiver,table,material,null,1)
                        ||receiver.carriedCount(material)!=1||donor.carriedCount(material)!=1){
                    h.fail("No conserved nearby surplus transfer");return;
                }
                if(BuilderSupplyInventory.borrowNearby(level,receiver,table,material,null,1)){
                    h.fail("Donor's reserved material was taken");return;
                }
                System.out.println("[CREW SURPLUS PASS] one real transfer, one reserved, remote rejected");h.succeed();
            }else if(h.getTick()==150)h.fail("Donor did not walk into physical transfer range");
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=600,skyAccess=true)
    public void groundBuilderWalksAroundRoofAndFliesToFinishingLayer(GameTestHelper h){
        var level=h.getLevel();
        for(int x=3;x<=24;x++)for(int z=3;z<=24;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=10;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int x=8;x<=19;x++)for(int z=7;z<=19;z++)h.setBlock(x,6,z,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(13,2,13));
        BlockPos target=h.absolutePos(new BlockPos(13,7,13));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);level.addFreshEntity(worker);
        boolean[] walked={false},flew={false};
        Vec3[] anchor={null};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(!BuilderFlight.active(worker)&&worker.onGround()
                    &&worker.getY()<start.getY()+.2&&worker.position().distanceToSqr(Vec3.atBottomCenterOf(start))>9)
                walked[0]=true;
            if(BuilderFlight.active(worker)){
                if(anchor[0]==null)anchor[0]=worker.position();
                flew[0]=true;
                double dx=worker.getX()-anchor[0].x,dz=worker.getZ()-anchor[0].z;
                if(dx*dx+dz*dz>4.01){h.fail("Roof access exceeded local flight envelope");return;}
            }
            if(flew[0]&&!BuilderFlight.active(worker)&&worker.onGround()
                    &&worker.getY()>=target.getY()-.1){
                if(!walked[0]){h.fail("Roof access did not walk around obstruction");return;}
                if(!level.getBlockState(target.below()).is(Blocks.STONE)){
                    h.fail("Roof was altered during flight");return;
                }
                System.out.println("[ROOF ASCENT PASS] real walk and local lift tick="+h.getTick());h.succeed();return;
            }
            if(h.getTick()%5==0&&!BuilderFlight.active(worker))
                BuilderFlight.approachRoofWork(level,worker,target,true);
            if(h.getTick()==590)h.fail("Roof ascent stalled feet="+worker.position()+" walked="+walked[0]+" flew="+flew[0]);
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=150,skyAccess=true)
    public void fractionalTakeoffDoesNotExceedTwoMetres(GameTestHelper h){
        var level=h.getLevel();
        for(int x=6;x<=16;x++)for(int z=6;z<=16;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=10;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int x=12;x<=15;x++)for(int z=9;z<=12;z++)h.setBlock(x,6,z,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(10,2,10));
        BlockPos landing=h.absolutePos(new BlockPos(12,7,10));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        // Ordinary walking centers only to about 2.5cm; reproduce the
        // actual failed roof handoff, not an artificially perfect pose.
        worker.setPos(start.getX()+.475,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        Vec3[] anchor={null};
        h.onEachTick(()->{
            if(h.getTick()==5){
                anchor[0]=worker.position();
                if(BuilderFlight.request(level,worker,landing)){
                    h.fail("Integer landing incorrectly accepted >2m");return;
                }
                if(!BuilderFlight.requestSupportedLanding(level,worker,landing)){
                    h.fail("Fractional supported landing was rejected");return;
                }
            }
            if(h.getTick()<=5||anchor[0]==null)return;
            double dx=worker.getX()-anchor[0].x,dz=worker.getZ()-anchor[0].z;
            if(dx*dx+dz*dz>4.0001){h.fail("Flight exceeded actual two-metre envelope");return;}
            if(!BuilderFlight.active(worker)&&worker.onGround()){
                if(!worker.blockPosition().equals(landing)){
                    h.fail("Did not reach intended landing tile: "+worker.position());return;
                }
                System.out.println("[FRACTIONAL TAKEOFF PASS] actual <=2m; landed on intended tile");h.succeed();
            }
        });
    }


    /** Landing on fractional-height terrain is an ordinary powered
     * descent, not a license to reset the two-block flight envelope. */
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=240,skyAccess=true)
    public void propellerLandsOnActualBottomSlabSurface(GameTestHelper h){
        var level=h.getLevel();
        for(int x=8;x<=14;x++)for(int z=8;z<=14;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=9;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(11,2,10,Blocks.STONE_SLAB);
        BlockPos start=h.absolutePos(new BlockPos(10,2,10));
        BlockPos slab=h.absolutePos(new BlockPos(11,2,10));
        BlockPos hover=start.above(4);
        Vec3 landing=new Vec3(slab.getX()+.5,slab.getY()+.5,slab.getZ()+.5);
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        boolean[] descending={false};
        Vec3[] previous={worker.position()};
        h.onEachTick(()->{
            if(!worker.isAlive()||worker.position().distanceTo(previous[0])>.45){
                h.fail("Fractional landing lost physical motion");return;
            }
            previous[0]=worker.position();
            if(h.getTick()==5&&!BuilderFlight.request(level,worker,hover)){
                h.fail("Could not establish powered hover");return;
            }
            if(h.getTick()<=5)return;
            if(!descending[0]&&!BuilderFlight.moving(worker)
                    &&worker.position().distanceToSqr(Vec3.atBottomCenterOf(hover))<.04){
                descending[0]=true;
                if(!BuilderFlight.requestSupportedLanding(level,worker,slab)){
                    h.fail("Physical half-block landing rejected");return;
                }
            }
            if(descending[0]&&!BuilderFlight.active(worker)&&worker.onGround()){
                if(worker.position().distanceToSqr(landing)>.04){
                    h.fail("Landed at wrong surface: "+worker.position());return;
                }
                System.out.println("[FRACTIONAL SLAB LANDING PASS] real half-block surface");
                h.succeed();
            }
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=120,skyAccess=true)
    public void materialNetworkTransfersClaimThenRecallsAndResumes(GameTestHelper h){
        var level=h.getLevel();
        for(int x=3;x<=20;x++)for(int z=3;z<=17;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=6;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(5,2,5,WorksiteContent.BUILDER_TABLE);
        h.setBlock(6,2,5,Blocks.CHEST);
        BlockPos station=h.absolutePos(new BlockPos(5,2,5));
        BlockPos depot=h.absolutePos(new BlockPos(6,2,5));
        BlockPos target=h.absolutePos(new BlockPos(15,2,12));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(depot.getX()>>4,depot.getZ()>>4),3);
        if(!(level.getBlockEntity(station) instanceof com.caszgamermd.golemsplus.world.WorkerStationBlockEntity table)
                ||!(level.getBlockEntity(depot) instanceof net.minecraft.world.Container chest)){
            h.fail("Missing network fixture");return;
        }
        var owner=h.makeMockServerPlayerInLevel();var id=owner.getUUID();
        try{
            var cells=java.util.List.of(new LitematicFileReader.Block(target.getX(),target.getY(),target.getZ(),"minecraft:glass"));
            var snapshot=BuilderPlacementSnapshot.encode("Network material",cells,java.util.List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),BuilderPlacementSnapshot.worldSpaceSite());
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            String file="placed-gametest-network-material.gpb";java.nio.file.Files.write(folder.resolve(file),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,2)
                    ||!table.setPlacedBuilderPlan(id,file,"Network material",station,0,0,plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,depot)){h.fail("Network job refused");return;}
            var requester=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            var donor=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos a=depot.south(),c=depot.east();
            requester.setPos(a.getX()+.5,a.getY(),a.getZ()+.5);
            donor.setPos(c.getX()+.5,c.getY(),c.getZ()+.5);
            level.addFreshEntity(requester);level.addFreshEntity(donor);
            if(!table.registerBuilderWorker(requester.getUUID())||!table.registerBuilderWorker(donor.getUUID())
                    ||!table.startPlacedBuilder(id)){h.fail("Network crew refused");return;}
            var glass=net.minecraft.world.item.Items.GLASS;
            h.onEachTick(()->{
                if(h.getTick()==5){
                    donor.setItem(0,new net.minecraft.world.item.ItemStack(glass,3));
                    if(table.claimPreferredBuilderZoneStep(requester.getUUID(),java.util.List.of(0),plan.steps(),0)!=0
                            ||!BuilderMaterialNetwork.offer(level,requester,table,plan.steps(),0,glass)){
                        h.fail("Could not release material-limited claim");return;
                    }
                    if(table.claimedStep(requester.getUUID())>=0
                            ||!BuilderMaterialNetwork.reservedForOther(level,table,requester.getUUID(),0)
                            ||!BuilderMaterialNetwork.offered(level,table,donor,plan.steps()).contains(0)
                            ||table.claimPreferredBuilderZoneStep(donor.getUUID(),java.util.List.of(0),plan.steps(),0)!=0
                            ||donor.carriedCount(glass)!=3||requester.carriedCount(glass)!=0){
                        h.fail("Stocked handoff violated exclusive claim or inventory conservation");return;
                    }
                    System.out.println("[MATERIAL NETWORK HANDOFF PASS] exact step transferred without moving items");
                    // The recipient runs out before placing; a different
                    // carrier now holds glass. Do not ping-pong this cell.
                    donor.setItem(0,net.minecraft.world.item.ItemStack.EMPTY);
                    requester.setItem(0,new net.minecraft.world.item.ItemStack(glass,2));
                    if(BuilderMaterialNetwork.offer(level,donor,table,plan.steps(),0,glass)
                            ||table.claimedStep(donor.getUUID())!=0){
                        h.fail("Recently transferred claim bounced back");return;
                    }
                    requester.setItem(0,net.minecraft.world.item.ItemStack.EMPTY);
                    System.out.println("[MATERIAL NETWORK NO BOUNCE PASS] recent claim retained");
                    // Fixture simulates consumption of the last stock, not
                    // completion of the unfinished construction cell.
                    donor.setItem(0,net.minecraft.world.item.ItemStack.EMPTY);
                    BuilderMaterialNetwork.shortage(level,table,plan.steps());
                    if(table.claimedStep(donor.getUUID())>=0
                            ||!BuilderMaterialNetwork.recallTick(level,requester,table)
                            ||!BuilderMaterialNetwork.recallTick(level,donor,table)){
                        h.fail("Crew did not enter shared resupply wait");return;
                    }
                }
                if(h.getTick()==10){
                    chest.setItem(0,new net.minecraft.world.item.ItemStack(glass,5));
                    if(!BuilderMaterialNetwork.recallTick(level,requester,table)
                            ||!BuilderMaterialNetwork.recallTick(level,donor,table)
                            ||BuilderMaterialNetwork.recallTick(level,requester,table)
                            ||BuilderMaterialNetwork.recallTick(level,donor,table)){
                        h.fail("Restock did not release the waiting crew");return;
                    }
                    if(!table.builderStepReadyForClaim(0)||chest.getItem(0).getCount()!=5){
                        h.fail("Resupply incorrectly completed a cell or consumed stock");return;
                    }
                    System.out.println("[MATERIAL NETWORK RESUPPLY PASS] both crew wait and resume on physical stock");
                    h.succeed();
                }
            });
        }catch(Exception e){h.fail("Network setup failed "+e);}
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=220,skyAccess=true)
    public void unthrownGrappleRestoresGravityAndRejectsConcurrentFlight(GameTestHelper h){
        var level=h.getLevel();
        for(int x=4;x<=22;x++)for(int z=5;z<=17;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=12;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int x=15;x<=18;x++)for(int z=9;z<=12;z++)h.setBlock(x,6,z,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(7,2,10));
        BlockPos goal=h.absolutePos(new BlockPos(16,7,10));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        worker.setItem(BuilderGolem.GRAPPLE_BELT_SLOT,new net.minecraft.world.item.ItemStack(WorksiteContent.GRAPPLE_HOOK));
        level.addFreshEntity(worker);
        h.onEachTick(()->{
            if(h.getTick()==5){
                BuilderGrappleAccess.reach(level,worker,null,goal);
                if(!BuilderGrappleAccess.debug(worker).contains("WALK_TO_LINE")){
                    h.fail("Could not establish real unthrown grapple approach");return;
                }
                if(BuilderFlight.request(level,worker,start.above(3))){
                    h.fail("Flight accepted during grapple approach");return;
                }
                // Reproduce the stale powered state from a prior controller.
                worker.setNoGravity(true);
                worker.setDeltaMovement(.3,.1,.3);
            }
            if(h.getTick()==6){
                // Call the same controller used before super.tick; the
                // harness callback can run before the entity tick itself.
                BuilderGrappleAccess.tickMotion(level,worker);
                if(worker.isNoGravity()&&BuilderGrappleAccess.debug(worker).contains("WALK_TO_LINE")){
                    h.fail("Unthrown grapple retained gravity override: "+BuilderGrappleAccess.debug(worker));return;
                }
                if(worker.isNoGravity()){
                    h.fail("Unexpected powered phase during distant approach: "+BuilderGrappleAccess.debug(worker));return;
                }
                System.out.println("[GRAPPLE APPROACH GRAVITY PASS] ordinary walk owns motion");
                h.succeed();
            }
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=500,skyAccess=true)
    public void fractionalRoofEdgeReturnsToOnSite(GameTestHelper h){
        var level=h.getLevel();
        for(int x=4;x<=24;x++)for(int z=5;z<=18;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=10;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int x=8;x<=16;x++)for(int z=8;z<=12;z++)h.setBlock(x,6,z,Blocks.STONE_SLAB);
        h.setBlock(19,2,10,Blocks.CHEST);
        BlockPos start=h.absolutePos(new BlockPos(11,6,10));
        BlockPos depot=h.absolutePos(new BlockPos(19,2,10));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY()+.5,start.getZ()+.5);
        level.addFreshEntity(worker);
        Vec3[] previous={worker.position()};
        boolean[] powered={false};
        h.onEachTick(()->{
            if(!worker.isAlive()||worker.position().distanceTo(previous[0])>1.0){
                h.fail("Fractional roof return lost continuous motion");return;
            }
            previous[0]=worker.position();
            powered[0]|=BuilderFlight.active(worker);
            if(h.getTick()>5&&h.getTick()%5==0)
                BuilderReturnService.approach(level,worker,depot,.8);
            if(powered[0]&&worker.onGround()&&!BuilderFlight.active(worker)
                    &&BuilderReturnService.withinStorageReach(worker,depot)){
                System.out.println("[FRACTIONAL ROOF RETURN PASS] slab edge to real OnSite");
                h.succeed();
            }
            if(h.getTick()==490)h.fail("Fractional roof return stalled feet="+worker.position());
        });
    }
}
