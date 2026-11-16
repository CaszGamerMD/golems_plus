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
public final class BuilderSameHeightDetourGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=900,skyAccess=true)
    public void sameHeightDetourRequiresSixSupportedFlights(GameTestHelper h){
        var level=h.getLevel();
        // Isolated platforms: the only initial supported landing moves
        // away from the destination. A tall wall prevents a direct
        // flight to the parallel return lane, even with over/under detours.
        for(int x=4;x<=15;x++)for(int z=7;z<=15;z++)
            for(int y=1;y<=15;y++)h.setBlock(x,y,z,Blocks.AIR);
        int[][] pads={{8,10},{6,10},{6,12},{8,12},{10,12},{12,12},{12,10}};
        for(int[] pad:pads)h.setBlock(pad[0],7,pad[1],Blocks.STONE);
        for(int y=3;y<=14;y++)h.setBlock(8,y,11,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(8,8,10));
        BlockPos finish=h.absolutePos(new BlockPos(12,8,10));
        var expected=new java.util.ArrayList<BlockPos>();
        for(int i=1;i<pads.length;i++)expected.add(h.absolutePos(new BlockPos(pads[i][0],8,pads[i][1])));
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        int[] next={0};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(h.getTick()==5)worker.setOnGround(true);
            if(worker.onGround()&&!BuilderFlight.active(worker)){
                if(next[0]<expected.size()&&worker.blockPosition().equals(expected.get(next[0])))next[0]++;
                if(worker.blockPosition().equals(finish)){
                    if(next[0]!=expected.size()){h.fail("Skipped required supported detour landings: "+next[0]);return;}
                    System.out.println("[MULTI DETOUR PASS] six supported same-height flights, first away from goal");
                    h.succeed();return;
                }
            }
            BuilderMultiLegTravel.stand(level,worker,finish);
            if(h.getTick()==890){
                BuilderTrace.dumpWorker(worker,"same-height detour stalled");
                h.fail("Detour stalled at "+worker.position()+" landings="+next[0]
                        +" trip="+BuilderMultiLegTravel.traceStatus(worker)
                        +" flight="+BuilderFlight.traceStatus(worker));
            }
        });
    }

}
