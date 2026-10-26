package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * A genuinely stranded Builder has no pre-existing tower and cannot
 * rely on ground A* from an elevated ledge. It must construct a PAID
 * temporary scaffold from the ground up, descend with its real entity
 * physics, and land on solid ground without teleporting.
 */
public final class BuilderEscapeScaffoldGameTest {
    @GameTest(maxTicks=1250,skyAccess=true)
    public void strandedBuilderBuildsTemporaryEscapeAndDescends(GameTestHelper h){
        var level=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=10;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int x=2;x<=4;x++)for(int z=2;z<=4;z++)
            h.setBlock(x,6,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=h.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            h.fail("No Builder table in escape fixture");return;
        }
        var owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            h.fail("Escape Planner ownership unavailable");return;
        }
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos upper=h.absolutePos(new BlockPos(4,7,3));
        worker.setPos(upper.getX()+.5,upper.getY(),upper.getZ()+.5);
        worker.assign(home,owner.getUUID());
        worker.setItem(0,new ItemStack(Items.SCAFFOLDING,32));
        level.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())){
            h.fail("Cannot register escape Builder");return;
        }
        final int initial=worker.carriedCount(Items.SCAFFOLDING);
        final int[] phase={0};
        h.onEachTick(()->{
            if(!worker.isAlive()){
                h.fail("Builder died or vanished during escape");return;
            }
            if(h.getTick()%5!=0)return;
            if(phase[0]==0){
                var result=BuilderScaffolding.escapeDown(level,worker,
                        table,owner,List.of());
                if(result==BuilderScaffolding.Result.NEED_MATERIALS
                        ||result==BuilderScaffolding.Result.NO_SAFE_TOWER){
                    h.fail("Failed to create real scaffold rescue: "+result
                            +" at "+worker.blockPosition());return;
                }
                if(!table.scaffoldPositions(worker.getUUID()).isEmpty())
                    phase[0]=1;
                else if(h.getTick()>100){
                    h.fail("No escape scaffolding created result="+result
                            +" workerY="+worker.getY()
                            +" ground="+table.builderOrigin()
                            +" station="+table.getBlockPos()
                            +" carried="+worker.carriedCount(Items.SCAFFOLDING)
                            +" move="+BuilderScaffolding.debugClimb(worker));
                }
            }else{
                if(BuilderScaffolding.descendForTravel(level,worker,table))
                    return;
                if(worker.onGround()
                        &&worker.getY()<=upper.getY()-4.7
                        &&level.getBlockState(worker.blockPosition().below())
                                .is(Blocks.STONE)){
                    var placed=table.scaffoldPositions(worker.getUUID());
                    if(placed.size()<4
                            ||initial-worker.carriedCount(Items.SCAFFOLDING)
                                    !=placed.size()){
                        h.fail("Escape ladder used incorrect material receipts: "
                                +placed.size()+" blocks remaining="+
                                worker.carriedCount(Items.SCAFFOLDING));return;
                    }
                    for(var at:placed){
                        if(!level.getBlockState(at).is(Blocks.SCAFFOLDING)
                                ||at.getY()>=upper.getY())
                            h.fail("Unsafe/missing scaffold at "+at);
                    }
                    h.succeed();
                }else if(h.getTick()>800)
                    h.fail("Builder still stranded after physical escape: "
                            +worker.blockPosition()+" state="+
                            BuilderScaffolding.debugClimb(worker));
            }
        });
    }
}
