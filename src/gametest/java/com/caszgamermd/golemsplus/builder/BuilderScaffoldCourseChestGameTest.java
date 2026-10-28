package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** A physical single-Builder inventory course, not a path simulation:
 * 1. Withdraw real scaffolding from start CHEST.
 * 2. Place a real tower, climb it to required work height.
 * 3. Descend safely, pick up OWN placed scaffold blocks into pockets.
 * 4. Deliver quota to finish CHEST, which unlocks an iron door.
 * 5. Walk the real Builder entity through that door to finish.
 */
public final class BuilderScaffoldCourseChestGameTest {
    private static final int REQUIRED=5;

    @GameTest(maxTicks=1600,skyAccess=true)
    public void singleBuilderRecyclesScaffoldsToUnlockFinish(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        // Unopened iron door is the only opening in a real two-high wall.
        for(int z=0;z<8;z++)for(int y=2;y<=3;y++)
            helper.setBlock(6,y,z,Blocks.COBBLESTONE);
        helper.setBlock(6,2,3,Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING,Direction.EAST)
                .setValue(DoorBlock.OPEN,false)
                .setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER));
        helper.setBlock(6,3,3,Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING,Direction.EAST)
                .setValue(DoorBlock.OPEN,false)
                .setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
        BlockPos start=helper.absolutePos(new BlockPos(1,2,2));
        BlockPos finishChest=helper.absolutePos(new BlockPos(5,2,2));
        BlockPos door=helper.absolutePos(new BlockPos(6,2,3));
        BlockPos goal=helper.absolutePos(new BlockPos(7,2,3));
        helper.setBlock(1,2,2,Blocks.CHEST);
        helper.setBlock(5,2,2,Blocks.CHEST);
        if(!(level.getBlockEntity(start) instanceof Container source)
                ||!(level.getBlockEntity(finishChest) instanceof Container finish)){
            helper.fail("Actual chest block inventories did not spawn");return;
        }
        source.setItem(0,new ItemStack(Items.SCAFFOLDING,REQUIRED));
        source.setChanged();
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        if(!(level.getBlockEntity(helper.absolutePos(new BlockPos(0,2,0)))
                instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing real Builder Planner");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        table.claimIfUnowned(owner.getUUID());
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos spawn=helper.absolutePos(new BlockPos(2,2,2));
        builder.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        level.addFreshEntity(builder);
        if(!table.registerBuilderWorker(builder.getUUID())){
            helper.fail("Cannot register exactly one real Builder");return;
        }
        var course=new BuilderScaffoldCourseLogistics.Session(
                start,finishChest,door,REQUIRED);
        int[] phase={0},built={0};
        long[] started={helper.getTick()};
        BlockPos highWork=helper.absolutePos(new BlockPos(4,5,3));
        helper.onEachTick(()->{
            if(!builder.isAlive()){
                helper.fail("Single Builder died");return;
            }
            course.enforceGate(level);
            if(helper.getTick()-started[0]>400){
                helper.fail("Chest-scaffold course stalled phase="+phase[0]
                        +" pos="+builder.position()
                        +" carried="+builder.carriedCount(Items.SCAFFOLDING)
                        +" withdrawn="+course.withdrawn()
                        +" recovered="+course.recovered()
                        +" delivered="+course.delivered()
                        +" tracked="+table.scaffoldPositions(builder.getUUID()).size()
                        +" climb="+BuilderScaffolding.debugClimb(builder)
                        +" nav="+WorkerNavigation.debugStatus(builder));
                return;
            }
            switch(phase[0]){
                case 0 -> {
                    if(course.collect(level,builder,REQUIRED)!=REQUIRED
                            ||source.getItem(0).getCount()!=0){
                        helper.fail("Starting chest did not physically debit five scaffolds");
                        return;
                    }
                    if(course.deliver(level,builder,REQUIRED)!=0
                            ||course.unlocked()
                            ||level.getBlockState(door).getValue(DoorBlock.OPEN)){
                        helper.fail("Finish door opened without real finish-chest deposits");
                        return;
                    }
                    phase[0]=1;started[0]=helper.getTick();
                }
                case 1 -> {
                    if(helper.getTick()%5==0){
                        var motion=BuilderScaffolding.reach(level,builder,table,
                                owner,highWork,List.of());
                        if(motion==BuilderScaffolding.Result.NO_SAFE_TOWER
                                ||motion==BuilderScaffolding.Result.NEED_MATERIALS){
                            helper.fail("Cannot build physically safe scaffold tower: "+motion);
                            return;
                        }
                    }
                    built[0]=Math.max(built[0],
                            table.scaffoldPositions(builder.getUUID()).size());
                    if(built[0]>=2&&builder.getY()>=highWork.getY()-.15){
                        phase[0]=2;started[0]=helper.getTick();
                    }
                }
                case 2 -> {
                    if(helper.getTick()%5==0)
                        BuilderScaffolding.descendForTravel(level,builder,table);
                    if(builder.onGround()&&Math.abs(builder.getY()-spawn.getY())<.26
                            &&builder.getY()<highWork.getY()-1){
                        phase[0]=3;started[0]=helper.getTick();
                    }
                }
                case 3 -> {
                    int reclaimed=course.reclaim(level,builder,table);
                    if(course.recovered()>=built[0]&&builder.carriedCount(
                            Items.SCAFFOLDING)>=REQUIRED){
                        phase[0]=4;started[0]=helper.getTick();
                    }else if(reclaimed==0&&helper.getTick()%5==0){
                        // Walk closer to remaining tracked scaffolds,
                        // never delete or pick them up from a distance.
                        var remaining=table.scaffoldPositions(builder.getUUID());
                        if(!remaining.isEmpty()){
                            BlockPos at=remaining.iterator().next();
                            WorkerNavigation.routeTo(level,builder,at,1.0);
                        }
                    }
                }
                case 4 -> {
                    if(builder.distanceToSqr(finishChest.getX()+.5,
                            finishChest.getY()+.5,finishChest.getZ()+.5)>6)
                        WorkerNavigation.routeTo(level,builder,finishChest,1.0);
                    else {
                        int transferred=course.deliver(level,builder,REQUIRED);
                        if(course.unlocked()){
                            if(transferred!=REQUIRED
                                    ||countItems(finish)!=REQUIRED
                                    ||source.getItem(0).getCount()!=0){
                                helper.fail("Finish chest did not receive exact original material quota");
                                return;
                            }
                            phase[0]=5;started[0]=helper.getTick();
                        }
                    }
                }
                case 5 -> {
                    if(helper.getTick()%5==0)
                        WorkerNavigation.routeToExact(level,builder,goal,1.0);
                    if(course.passed(level,builder,goal)){
                        System.out.println("[SCAFFOLD COURSE CHESTS] PASS one real Builder"
                                +" start="+course.withdrawn()
                                +" built="+built[0]
                                +" pickedUp="+course.recovered()
                                +" finish="+course.delivered()
                                +" crossedDoor="+course.passed(level,builder,goal)
                                +" tick="+helper.getTick());
                        helper.succeed();
                    }
                }
                default -> helper.fail("Invalid course phase "+phase[0]);
            }
        });
    }
    private static int countItems(Container inventory){
        int sum=0;
        for(int i=0;i<inventory.getContainerSize();i++)
            if(inventory.getItem(i).is(Items.SCAFFOLDING))
                sum+=inventory.getItem(i).getCount();
        return sum;
    }
}
