package com.caszgamermd.golemsplus.cooking;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.Node;

/**
 * Route chefs along *walkable floor* instead of onto kitchen appliances.
 * Kitchen floor tiles are preferred, but any safe floor is allowed if the
 * kitchen is built on vanilla blocks. A path is rejected if any intermediate
 * footstep would take the golem onto a kitchen counter, oven or fridge.
 */
public final class ChefFloorNavigation {
    private ChefFloorNavigation(){}
    private static boolean kitchenFloor(ServerLevel level,BlockPos footing){
        var id=BuiltInRegistries.BLOCK.getKey(level.getBlockState(footing).getBlock());
        return id.getNamespace().equals("cookingforblockheads")
                &&id.getPath().endsWith("_kitchen_floor");
    }
    private static boolean appliance(ServerLevel level,BlockPos footing){
        var id=BuiltInRegistries.BLOCK.getKey(level.getBlockState(footing).getBlock());
        return id.getNamespace().equals("cookingforblockheads")
                &&!id.getPath().endsWith("_kitchen_floor");
    }
    private static boolean usable(ServerLevel level,BlockPos feet){
        if(!level.hasChunkAt(feet)||!level.hasChunkAt(feet.below())
                ||appliance(level,feet.below()))return false;
        return level.getBlockState(feet).getCollisionShape(level,feet).isEmpty()
                &&level.getBlockState(feet.above())
                        .getCollisionShape(level,feet.above()).isEmpty()
                &&!level.getBlockState(feet.below())
                        .getCollisionShape(level,feet.below()).isEmpty()
                &&!level.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
    }
    private static boolean clearPath(ServerLevel level,Path path){
        if(path==null||!path.canReach())return false;
        for(int i=1;i<path.getNodeCount();i++){
            Node p=path.getNode(i);
            if(appliance(level,new BlockPos(p.x,p.y-1,p.z)))return false;
        }
        return true;
    }
    private static boolean approach(ServerLevel level,ChefGolem chef,BlockPos feet,double speed){
        if(!usable(level,feet))return false;
        PathNavigation nav=chef.getNavigation();
        Path path=nav.createPath(feet,0);
        if(!clearPath(level,path))return false;
        return nav.moveTo(path,speed);
    }
    /** Find a path to a standing tile near the currently used kitchen device. */
    public static void work(ServerLevel level,ChefGolem chef,BlockPos machine){
        if(machine==null)return;
        // Search the kitchen's floor first. Keep cooks beside a work surface,
        // not standing on its top. Equal-height tiles are favored.
        BlockPos base=chef.blockPosition();
        for(int preference=0;preference<2;preference++){
            BlockPos best=null;
            double bestScore=Double.MAX_VALUE;
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)
                for(int dy=-2;dy<=2;dy++){
                    BlockPos feet=machine.offset(dx,dy,dz);
                    if(!usable(level,feet))continue;
                    boolean floor=kitchenFloor(level,feet.below());
                    if(preference==0&&!floor)continue;
                    double distance=feet.distSqr(machine)+feet.distSqr(base)*.04;
                    if(distance<bestScore){best=feet;bestScore=distance;}
                }
            if(best!=null&&approach(level,chef,best,.75))return;
        }
        // No safe path? Remain where we are rather than climb onto a counter.
        chef.getNavigation().stop();
    }
    /** Relaxed idle roam no farther than 10 blocks from its assigned kiosk. */
    public static void idle(ServerLevel level,ChefGolem chef,BlockPos home){
        if(!level.hasChunkAt(home))return;
        if(!chef.getNavigation().isDone())return;
        BlockPos base=chef.blockPosition();
        if(chef.getRandom().nextInt(3)!=0)return;
        for(int preference=0;preference<2;preference++){
            for(int n=0;n<18;n++){
                BlockPos feet=home.offset(chef.getRandom().nextInt(15)-7,
                        chef.getRandom().nextInt(3)-1,
                        chef.getRandom().nextInt(15)-7);
                if(home.distSqr(feet)>100||!usable(level,feet))continue;
                if(preference==0&&!kitchenFloor(level,feet.below()))continue;
                if(approach(level,chef,feet,.6))return;
            }
        }
        // Return to a safe tile near the kiosk if stranded.
        if(base.distSqr(home)>100)work(level,chef,home);
    }
}
