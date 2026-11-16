package com.caszgamermd.golemsplus.builder;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
public final class BuilderCrewRecoveryGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=100,skyAccess=true)
    public void allUnloadBeforeReducedCrewResumes(GameTestHelper h){
        crewBarrier(h,false);
    }
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=1900,skyAccess=true)
    public void timedOutRetreatResumesWithReturnedWorkerWithoutLosingCargo(GameTestHelper h){
        crewBarrier(h,true);
    }
    private void crewBarrier(GameTestHelper h,boolean delayed){
        var level=h.getLevel();
        for(int x=3;x<=18;x++)for(int z=3;z<=16;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(5,2,5,WorksiteContent.BUILDER_TABLE);
        h.setBlock(6,2,5,Blocks.CHEST);
        BlockPos station=h.absolutePos(new BlockPos(5,2,5)),depot=h.absolutePos(new BlockPos(6,2,5));
        BlockPos target=h.absolutePos(new BlockPos(10,2,5));
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(depot.getX()>>4,depot.getZ()>>4),3);
        if(!(level.getBlockEntity(station) instanceof WorkerStationBlockEntity table)
                ||!(level.getBlockEntity(depot) instanceof Container chest)){
            h.fail("No recovery depot");return;
        }
        var owner=h.makeMockServerPlayerInLevel();var id=owner.getUUID();
        try{
            var cells=List.of(new LitematicFileReader.Block(target.getX(),target.getY(),target.getZ(),"minecraft:glass"));
            var snapshot=BuilderPlacementSnapshot.encode("Crew reset",cells,List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),BuilderPlacementSnapshot.worldSpaceSite());
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            String file=delayed?"placed-crew-reset-timeout.gpb":"placed-crew-reset.gpb";java.nio.file.Files.write(folder.resolve(file),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,2)
                    ||!table.setPlacedBuilderPlan(id,file,"Crew reset",station,0,0,plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,depot)){
                h.fail("Crew reset job refused");return;
            }
            var a=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            var b=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos ap=depot.south(),bp=delayed?depot.east(8):depot.east();
            a.setPos(ap.getX()+.5,ap.getY(),ap.getZ()+.5);
            b.setPos(bp.getX()+.5,bp.getY(),bp.getZ()+.5);
            level.addFreshEntity(a);level.addFreshEntity(b);
            if(!table.registerBuilderWorker(a.getUUID())||!table.registerBuilderWorker(b.getUUID())
                    ||!table.startPlacedBuilder(id)){h.fail("Crew refused");return;}
            h.runAtTickTime(5,()->{
                a.setOnGround(true);b.setOnGround(true);
                a.setItem(0,new ItemStack(Items.GLASS,3));b.setItem(0,new ItemStack(Items.GLASS,4));
                int layer=table.builderActiveLayer(plan.steps()),done=table.builderWorkDone();
                if(table.claimPreferredBuilderZoneStep(a.getUUID(),List.of(0),plan.steps(),0)!=0){
                    h.fail("Could not establish unfinished claim");return;
                }
                BuilderCrewRecovery.begin(level,table,plan.steps());
                if(table.claimedStep(a.getUUID())>=0||!BuilderCrewRecovery.recalling(table)){
                    h.fail("Recovery did not release claims");return;
                }
                if(!BuilderCrewRecovery.tick(level,a,table,plan.steps())
                        ||!a.isEmpty()||b.isEmpty()||!BuilderCrewRecovery.recalling(table)){
                    h.fail("First unload did not retain crew barrier");return;
                }
                if(delayed){
                    // Keep the second registered worker physically away
                    // from storage and unserviced, with its real cargo.
                    h.runAtTickTime(1810,()->{
                        BuilderCrewRecovery.tick(level,a,table,plan.steps());
                        if(BuilderCrewRecovery.recalling(table)
                                ||!BuilderCrewRecovery.staff(table,layer,table.builderWorkerIds()).equals(List.of(a.getUUID()))
                                ||BuilderCrewRecovery.staff(table,layer+1,table.builderWorkerIds()).contains(b.getUUID())
                                ||!BuilderCrewRecovery.staff(table,layer+1,List.of(b.getUUID())).equals(List.of(a.getUUID()))
                                ||!BuilderCrewRecovery.sidelined(table,b.getUUID())
                                ||b.carriedCount(Items.GLASS)!=4||!b.isAlive()
                                ||table.builderWorkDone()!=done||table.crewTarget()!=2){
                            h.fail("Timeout did not preserve stranded worker and resume only safe crew");return;
                        }
                        if(!BuilderCrewRecovery.tick(level,b,table,plan.steps())
                                ||b.carriedCount(Items.GLASS)!=4
                                ||!BuilderCrewRecovery.sidelined(table,b.getUUID())){
                            h.fail("Stranded worker did not enter independent physical recovery");return;
                        }
                        System.out.println("[CREW RETREAT TIMEOUT PASS] safe worker resumed, stranded worker retained cargo and registration");
                        h.succeed();
                    });
                    return;
                }
                if(!BuilderCrewRecovery.tick(level,b,table,plan.steps())
                        ||!b.isEmpty()||BuilderCrewRecovery.recalling(table)){
                    h.fail("Second unload did not release crew barrier");return;
                }
                int glass=0;for(int i=0;i<chest.getContainerSize();i++)
                    if(chest.getItem(i).is(Items.GLASS))glass+=chest.getItem(i).getCount();
                if(glass!=7||table.builderWorkDone()!=done||table.builderActiveLayer(plan.steps())!=layer
                        ||BuilderCrewRecovery.staff(table,layer,table.builderWorkerIds()).size()!=1
                        ||BuilderCrewRecovery.staff(table,layer+1,table.builderWorkerIds()).size()!=2
                        ||table.crewTarget()!=2||!level.getBlockState(target).isAir()){
                    h.fail("Recovery changed inventory, progress or permanent crew target");return;
                }
                System.out.println("[CREW RETREAT BARRIER PASS] both real pouches unloaded; same layer; one active; next layer full crew");
                h.succeed();
            });
        }catch(Exception e){h.fail("Crew reset setup: "+e);}
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=100,skyAccess=true)
    public void finalMaterialPickupKeepsClaimAndUsesRealStock(GameTestHelper h){
        var level=h.getLevel();
        for(int x=3;x<=16;x++)for(int z=3;z<=16;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(5,2,5,WorksiteContent.BUILDER_TABLE);
        h.setBlock(6,2,5,Blocks.CHEST);
        BlockPos station=h.absolutePos(new BlockPos(5,2,5)),
                depot=h.absolutePos(new BlockPos(6,2,5)),
                target=h.absolutePos(new BlockPos(10,2,5));
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(depot.getX()>>4,depot.getZ()>>4),3);
        if(!(level.getBlockEntity(station) instanceof WorkerStationBlockEntity table)
                ||!(level.getBlockEntity(depot) instanceof Container chest)){
            h.fail("Missing pickup fixture");return;
        }
        var owner=h.makeMockServerPlayerInLevel();var id=owner.getUUID();
        try{
            var cells=List.of(new LitematicFileReader.Block(target.getX(),target.getY(),target.getZ(),"minecraft:polished_diorite"));
            var snapshot=BuilderPlacementSnapshot.encode("Final material pickup",cells,List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),BuilderPlacementSnapshot.worldSpaceSite());
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            String file="placed-crew-final-pickup.gpb";java.nio.file.Files.write(folder.resolve(file),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                    ||!table.setPlacedBuilderPlan(id,file,"Final material pickup",station,0,0,plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,depot)){h.fail("Pickup plan refused");return;}
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos from=depot.south();
            worker.setPos(from.getX()+.5,from.getY(),from.getZ()+.5);level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)){
                h.fail("Pickup crew refused");return;
            }
            h.runAtTickTime(5,()->{
                worker.setOnGround(true);
                chest.setItem(0,new ItemStack(Items.POLISHED_DIORITE,1));
                chest.setItem(1,new ItemStack(WorksiteContent.GRAPPLE_HOOK,1));
                chest.setItem(2,new ItemStack(Items.BUCKET,1));
                if(BuilderGolem.temporaryScaffoldingEnabled())
                    chest.setItem(3,new ItemStack(Items.SCAFFOLDING,64));
                if(table.claimPreferredBuilderZoneStep(worker.getUUID(),List.of(0),plan.steps(),0)!=0){
                    h.fail("No initial claim");return;
                }
                BuilderMaterialNetwork.shortage(level,table,plan.steps());
                if(BuilderMaterialNetwork.recalling(table,worker.getUUID())
                        ||table.claimedStep(worker.getUUID())!=0
                        ||!table.builderWorkOrder(worker.getUUID()).equals(List.of(0))){
                    h.fail("Available material incorrectly caused recall/claim loss");return;
                }
                var prep=BuilderSupplyInventory.prepareWorkOrder(level,worker,table,plan.steps(),List.of(0));
                if(prep!=BuilderSupplyInventory.LoadStatus.READY
                        ||worker.carriedCount(Items.POLISHED_DIORITE)!=1
                        ||!chest.getItem(0).isEmpty()
                        ||table.claimedStep(worker.getUUID())!=0
                        ||!level.getBlockState(target).isAir()
                        ||table.builderStepCompletedForMaterials(0)){
                    h.fail("Physical pickup failed: "+prep+" claim="+table.claimedStep(worker.getUUID()));return;
                }
                // A truly empty source must still trigger the old recall.
                // The fixture removes its sample item solely for this check.
                for(int i=0;i<worker.getContainerSize();i++)
                    if(worker.getItem(i).is(Items.POLISHED_DIORITE))worker.removeItemNoUpdate(i);
                BuilderMaterialNetwork.resetForCrew(table);
                BuilderMaterialNetwork.shortage(level,table,plan.steps());
                if(!BuilderMaterialNetwork.recalling(table,worker.getUUID())
                        ||table.claimedStep(worker.getUUID())>=0){
                    h.fail("Real material shortage no longer recalls");return;
                }
                System.out.println("[CREW FINAL PICKUP PASS] real stock paid, claim preserved, genuine shortage recalls");
                h.succeed();
            });
        }catch(Exception e){h.fail("Pickup fixture setup: "+e);}
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=800,skyAccess=true)
    public void isolatedStockedWorkerResumesWithoutDepot(GameTestHelper h){
        var level=h.getLevel();
        for(int x=3;x<=19;x++)for(int z=3;z<=19;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=7;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(5,2,5,WorksiteContent.BUILDER_TABLE);
        h.setBlock(6,2,5,Blocks.CHEST);
        // Depot is deliberately unreachable by walking. The builder and
        // its legitimate construction target are outside this sealed room.
        for(int x=4;x<=7;x++)for(int z=4;z<=6;z++)
            if(x==4||x==7||z==4||z==6)
                for(int y=2;y<=6;y++)h.setBlock(x,y,z,Blocks.STONE);
        BlockPos station=h.absolutePos(new BlockPos(5,2,5)),
                depot=h.absolutePos(new BlockPos(6,2,5)),
                target=h.absolutePos(new BlockPos(14,2,12)),
                from=h.absolutePos(new BlockPos(12,2,12));
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(depot.getX()>>4,depot.getZ()>>4),3);
        if(!(level.getBlockEntity(station) instanceof WorkerStationBlockEntity table)){
            h.fail("Missing isolated recovery Planner");return;
        }
        var owner=h.makeMockServerPlayerInLevel();var id=owner.getUUID();
        try{
            var cells=List.of(new LitematicFileReader.Block(target.getX(),target.getY(),target.getZ(),"minecraft:glass"));
            var snapshot=BuilderPlacementSnapshot.encode("Isolated stocked recovery",cells,List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),BuilderPlacementSnapshot.worldSpaceSite());
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            String file="placed-crew-stocked-rescue.gpb";java.nio.file.Files.write(folder.resolve(file),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                    ||!table.setPlacedBuilderPlan(id,file,"Isolated stocked recovery",station,0,0,plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,depot)){h.fail("Recovery job refused");return;}
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            worker.setPropellerEnabled(false);
            worker.setPos(from.getX()+.5,from.getY(),from.getZ()+.5);level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)){
                h.fail("Recovery crew refused");return;
            }
            h.runAtTickTime(5,()->{
                worker.setOnGround(true);worker.setItem(0,new ItemStack(Items.GLASS,3));
                BuilderCrewRecovery.begin(level,table,plan.steps());
                BuilderCrewRecovery.tick(level,worker,table,plan.steps());
                if(!BuilderCrewRecovery.recalling(table)||worker.carriedCount(Items.GLASS)!=3){
                    h.fail("Did not preserve unreachable worker's cargo");return;
                }
            });
            h.runAtTickTime(650,()->{
                BuilderCrewRecovery.tick(level,worker,table,plan.steps());
                if(BuilderCrewRecovery.recalling(table)||table.claimedStep(worker.getUUID())!=0
                        ||worker.carriedCount(Items.GLASS)!=3||!worker.isAlive()
                        ||table.builderStepCompletedForMaterials(0)
                        ||!level.getBlockState(target).isAir()
                        ||BuilderReturnService.withinStorageReach(worker,depot)){
                    h.fail("Stocked rescue failed to preserve real claim/cargo: "+table.problem()
                            +" claim="+table.claimedStep(worker.getUUID())+" feet="+worker.position());return;
                }
                System.out.println("[CREW STOCKED RESCUE PASS] unreachable depot, actual stocked claim preserved");
                h.succeed();
            });
        }catch(Exception e){h.fail("Isolated stocked recovery setup: "+e);}
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=240,skyAccess=true)
    public void escapeBoxCommitsBeforeDepotReturn(GameTestHelper h){
        var level=h.getLevel();
        for(int x=3;x<=20;x++)for(int z=3;z<=20;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        BlockPos low=h.absolutePos(new BlockPos(7,2,7));
        BlockPos high=h.absolutePos(new BlockPos(13,4,13));
        var box=BuilderEscapeBox.Bounds.from(List.of(
                new BuilderConstructionPlan.Step(low.getX(),low.getY(),low.getZ(),"minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(high.getX(),high.getY()-1,high.getZ(),"minecraft:stone","minecraft:stone",false,
                        new BuilderConstructionPlan.Companion(high.getX(),high.getY(),high.getZ(),"minecraft:stone"))));
        if(box.minX()!=low.getX()-1||box.minY()!=low.getY()-1||box.minZ()!=low.getZ()-1
                ||box.maxX()!=high.getX()+1||box.maxY()!=high.getY()+1||box.maxZ()!=high.getZ()+1){
            h.fail("Escape box failed to include all six faces and companion cell");return;
        }
        BlockPos start=h.absolutePos(new BlockPos(9,2,10));
        BlockPos depot=h.absolutePos(new BlockPos(18,2,10));
        h.setBlock(18,2,10,Blocks.CHEST);
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        worker.setPropellerEnabled(false); // this regression must exercise walking
        level.addFreshEntity(worker);
        BlockPos[] committed={null};boolean[] escaped={false};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(h.getTick()==5)worker.setOnGround(true);
            if(!escaped[0]){
                boolean owns=BuilderEscapeBox.tick(level,worker,box,depot);
                BlockPos current=BuilderEscapeBox.waypoint(worker);
                if(current!=null){
                    if(committed[0]==null)committed[0]=current;
                    else if(!committed[0].equals(current)){h.fail("Healthy escape waypoint changed");return;}
                }
                if(!owns){
                    if(committed[0]==null||!box.outside(worker.blockPosition())){
                        h.fail("OnSite route released before physical escape");return;
                    }
                    escaped[0]=true;
                }
            }else if(!BuilderReturnService.approach(level,worker,depot,1.0)){
                h.succeed();return;
            }
            if(h.getTick()==230)h.fail("Escape/depot route stalled: "+worker.position()+" waypoint="+committed[0]+" box="+box);
        });
    }

}
