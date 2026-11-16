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
            String file="placed-crew-reset.gpb";java.nio.file.Files.write(folder.resolve(file),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,2)
                    ||!table.setPlacedBuilderPlan(id,file,"Crew reset",station,0,0,plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,depot)){
                h.fail("Crew reset job refused");return;
            }
            var a=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            var b=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos ap=depot.south(),bp=depot.east();
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

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=300,skyAccess=true)
    public void highEscapeMustLandAtDepotHeightBeforeWalkingHome(GameTestHelper h){
        var level=h.getLevel();
        for(int x=4;x<=22;x++)for(int z=4;z<=20;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=12;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int x=8;x<=13;x++)for(int z=8;z<=13;z++)h.setBlock(x,8,z,Blocks.STONE);
        BlockPos low=h.absolutePos(new BlockPos(8,3,8)),high=h.absolutePos(new BlockPos(13,11,13));
        var box=new BuilderEscapeBox.Bounds(low.getX()-1,low.getY()-1,low.getZ()-1,
                high.getX()+1,high.getY()+1,high.getZ()+1);
        BlockPos start=h.absolutePos(new BlockPos(12,9,10)),depot=h.absolutePos(new BlockPos(19,2,10));
        h.setBlock(19,2,10,Blocks.CHEST);
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        boolean[] outsideHigh={false},landed={false};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(h.getTick()==5)worker.setOnGround(true);
            if(box.sideOutside(worker.blockPosition())&&worker.getY()>depot.getY()+3)
                outsideHigh[0]=true;
            if(!landed[0]){
                if(!BuilderEscapeBox.tick(level,worker,box,depot)){
                    if(!outsideHigh[0]||!worker.onGround()||BuilderFlight.active(worker)
                            ||Math.abs(worker.getY()-depot.getY())>.6){
                        h.fail("Released depot route before clear air and grounded OnSite height: "+worker.position());return;
                    }
                    landed[0]=true;
                }
            }else if(!BuilderReturnService.approach(level,worker,depot,1.0)){
                System.out.println("[ESCAPE THREE STAGES PASS] side air, actual OnSite elevation, physical storage reach");
                h.succeed();return;
            }
            if(h.getTick()==290)h.fail("Three stage escape stalled: "+worker.position()+" outsideHigh="+outsideHigh[0]+" landed="+landed[0]);
        });
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=600,skyAccess=true)
    public void multiLegFlightUsesIntermediateLandingAndWalk(GameTestHelper h){
        var level=h.getLevel();
        for(int x=5;x<=19;x++)for(int z=6;z<=15;z++)
            for(int y=1;y<=12;y++)h.setBlock(x,y,z,Blocks.AIR);
        h.setBlock(8,1,10,Blocks.STONE);
        h.setBlock(10,4,10,Blocks.STONE);
        h.setBlock(11,4,10,Blocks.STONE);
        h.setBlock(13,7,10,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(8,2,10));
        BlockPos middle=h.absolutePos(new BlockPos(10,5,10));
        BlockPos finish=h.absolutePos(new BlockPos(13,8,10));
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        boolean[] landed={false},walked={false},descending={false},downMiddle={false},downWalk={false};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(h.getTick()==5)worker.setOnGround(true);
            if(worker.onGround()&&!BuilderFlight.active(worker)){
                if(!descending[0]){
                    if(worker.blockPosition().equals(middle))landed[0]=true;
                    if(landed[0]&&worker.blockPosition().equals(middle.east()))walked[0]=true;
                    if(worker.blockPosition().equals(finish)){
                        if(!landed[0]||!walked[0]){h.fail("Skipped intermediate landing or walk");return;}
                        descending[0]=true;BuilderMultiLegTravel.clear(worker);
                    }
                }else{
                    if(worker.blockPosition().equals(middle.east()))downMiddle[0]=true;
                    if(downMiddle[0]&&worker.blockPosition().equals(middle))downWalk[0]=true;
                    if(worker.blockPosition().equals(start)){
                        if(!downMiddle[0]||!downWalk[0]){h.fail("Return skipped intermediate landing or walk");return;}
                        System.out.println("[MULTI FLIGHT PASS] supported flights and intermediate walking both directions");
                        h.succeed();return;
                    }
                }
            }
            BuilderMultiLegTravel.stand(level,worker,descending[0]?start:finish);
            if(h.getTick()==590)h.fail("Multi-flight stalled at "+worker.position()+" intermediate="+landed[0]+" walked="+walked[0]+" descending="+descending[0]);
        });
    }
}
