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
public final class BuilderMultiLegTravelGameTest {
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
        if(!level.getBlockState(finish.below()).is(Blocks.STONE)){
            h.fail("Missing finish support immediately after fixture setup at "+finish.below());return;
        }
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        BlockPos launch=h.absolutePos(new BlockPos(11,5,10));
        boolean[] landed={false},walked={false},descending={false},downMiddle={false},downWalk={false};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(h.getTick()==5){
                worker.setOnGround(true);
                boolean first=BuilderFlight.canFlyBetween(level,worker,start,middle);
                boolean walking=com.caszgamermd.golemsplus.worker.WorkerNavigation.hasGroundRouteFrom(level,worker,middle,List.of(launch));
                boolean second=BuilderFlight.canFlyBetween(level,worker,launch,finish);
                System.out.println("[MULTI FIXTURE PROBE] start="+start+" middle="+middle+" launch="+launch+" finish="+finish
                        +" first="+first+" walk="+walking+" second="+second
                        +" launchFloor="+level.getBlockState(launch.below())
                        +" finishFloor="+level.getBlockState(finish.below()));
                if(!first||!walking||!second){
                    h.fail("Required fixture transition not physically available: first="+first+" walk="+walking+" second="+second);return;
                }
            }
            if(worker.onGround()&&!BuilderFlight.active(worker)){
                if(!descending[0]){
                    if(worker.blockPosition().equals(middle))landed[0]=true;
                    if(landed[0]&&worker.blockPosition().equals(launch))walked[0]=true;
                    if(worker.blockPosition().equals(finish)){
                        if(!landed[0]||!walked[0]){h.fail("Skipped intermediate landing or walk");return;}
                        descending[0]=true;BuilderMultiLegTravel.clear(worker);
                    }
                }else{
                    if(worker.blockPosition().equals(launch))downMiddle[0]=true;
                    if(downMiddle[0]&&worker.blockPosition().equals(middle))downWalk[0]=true;
                    if(worker.blockPosition().equals(start)){
                        if(!downMiddle[0]||!downWalk[0]){h.fail("Return skipped intermediate landing or walk");return;}
                        System.out.println("[MULTI FLIGHT PASS] supported flights and intermediate walking both directions");
                        h.succeed();return;
                    }
                }
            }
            BuilderMultiLegTravel.stand(level,worker,descending[0]?start:finish);
            if(h.getTick()==590){
                BuilderTrace.dumpWorker(worker,"multi-flight stalled");
                h.fail("Multi-flight stalled at "+worker.position()+" intermediate="+landed[0]+" walked="+walked[0]
                        +" descending="+descending[0]+" flight="+BuilderFlight.traceStatus(worker)
                        +" trip="+BuilderMultiLegTravel.traceStatus(worker)
                        +" nav="+com.caszgamermd.golemsplus.worker.WorkerNavigation.debugStatus(worker));
            }
        });
    }

}
