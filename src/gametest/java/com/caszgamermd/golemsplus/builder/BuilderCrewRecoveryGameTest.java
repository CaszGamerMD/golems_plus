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
}
