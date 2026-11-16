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
public final class BuilderDynamicRouteRepairGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=900,skyAccess=true)
    public void committedTripRepairsAfterLandingBecomesBlocked(GameTestHelper h){
        var level=h.getLevel();
        for(int x=4;x<=17;x++)for(int z=7;z<=15;z++)
            for(int y=1;y<=15;y++)h.setBlock(x,y,z,Blocks.AIR);
        int[][] pads={{6,10},{8,10},{10,10},{12,10},{14,10},{8,12},{10,12},{12,12}};
        for(int[] pad:pads)h.setBlock(pad[0],7,pad[1],Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(6,8,10));
        BlockPos first=h.absolutePos(new BlockPos(8,8,10));
        BlockPos rejoin=h.absolutePos(new BlockPos(12,8,10));
        BlockPos finish=h.absolutePos(new BlockPos(14,8,10));
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        boolean[] blocked={false},repaired={false},joined={false};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(h.getTick()==5)worker.setOnGround(true);
            if(worker.onGround()&&!BuilderFlight.active(worker)){
                if(!blocked[0]&&worker.blockPosition().equals(first)){
                    // Simulate a newly placed obstruction on the next
                    // committed landing, not on the golem's current tile.
                    for(int y=8;y<=10;y++)h.setBlock(10,y,10,Blocks.STONE);
                    blocked[0]=true;
                }
                if(blocked[0]&&worker.blockPosition().equals(rejoin))joined[0]=true;
                if(worker.blockPosition().equals(finish)){
                    if(!blocked[0]||!repaired[0]||!joined[0]){
                        h.fail("Reached goal without repairing the saved suffix");return;
                    }
                    System.out.println("[MULTI REPAIR PASS] bypassed new obstruction and rejoined committed route");
                    h.succeed();return;
                }
            }
            BuilderMultiLegTravel.stand(level,worker,finish);
            if(BuilderMultiLegTravel.repairCount(worker)>0)repaired[0]=true;
            if(h.getTick()==890){
                BuilderTrace.dumpWorker(worker,"dynamic trip repair stalled");
                h.fail("Dynamic repair stalled at "+worker.position()+" blocked="+blocked[0]
                        +" repaired="+repaired[0]+" rejoined="+joined[0]+" trip="+BuilderMultiLegTravel.traceStatus(worker));
            }
        });
    }
}
