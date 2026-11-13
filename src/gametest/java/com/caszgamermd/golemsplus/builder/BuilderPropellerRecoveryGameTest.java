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

}
