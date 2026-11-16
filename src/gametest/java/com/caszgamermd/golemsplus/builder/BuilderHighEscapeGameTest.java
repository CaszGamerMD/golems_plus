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
public final class BuilderHighEscapeGameTest {
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

}
