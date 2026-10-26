package com.caszgamermd.golemsplus.breakroom;
import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.world.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import java.util.UUID;
public final class GolemBreakGameTest {
 @GameTest(maxTicks=1000,skyAccess=true)
 public void idleBuilderPhysicallyVisitsFurnitureAndRests(GameTestHelper h){
  for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,1,z,Blocks.STONE);
  h.setBlock(1,2,1,WorksiteContent.BUILDER_TABLE);
  h.setBlock(5,2,5,BreakRoomContent.ARCADE);
  var home=h.absolutePos(new BlockPos(1,2,1));
  var table=(WorkerStationBlockEntity)h.getLevel().getBlockEntity(home);
  var owner=UUID.randomUUID();table.claimIfUnowned(owner);
  var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,h.getLevel());
  var spawn=h.absolutePos(new BlockPos(2,2,2));worker.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);worker.assign(home,owner);
  table.registerBuilderWorker(worker.getUUID());h.getLevel().addFreshEntity(worker);
  var furniture=h.absolutePos(new BlockPos(5,2,5));
  h.onEachTick(()->{
   if(GolemBreakService.rested(worker)){
    if(worker.distanceToSqr(furniture.getX()+.5,furniture.getY(),furniture.getZ()+.5)>5){h.fail("Rest granted away from furniture");return;}
    h.succeed();
   }
  });
 }
}
