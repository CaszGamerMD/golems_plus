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
            if(h.getTick()==590){
                BuilderTrace.dumpWorker(worker,"multi-flight stalled");
                h.fail("Multi-flight stalled at "+worker.position()+" intermediate="+landed[0]+" walked="+walked[0]
                        +" descending="+descending[0]+" flight="+BuilderFlight.traceStatus(worker)
                        +" trip="+BuilderMultiLegTravel.traceStatus(worker)
                        +" nav="+com.caszgamermd.golemsplus.worker.WorkerNavigation.debugStatus(worker));
            }
        });
    }

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
