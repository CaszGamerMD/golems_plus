package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Temporary physical climb tower. Every block is purchased with one real
 * scaffolding item and recorded on the station before use. Cleans only its
 * own surviving scaffold blocks (top-down), never a player's scaffolding.
 */
public final class BuilderScaffolding {
    public enum Result { CLIMBING, NEED_MATERIALS, NO_SAFE_TOWER, NOT_NEEDED }
    private static final int MAX_HEIGHT=48;
    private BuilderScaffolding(){}

    public static Result reach(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer player,BlockPos target,
            List<BuilderConstructionPlan.Step> plan){
        int feet=worker.blockPosition().getY();
        // Short climbs require no scaffold; vanilla walking remains preferred.
        if(target.getY()-feet<=2){
            worker.setNoGravity(false);
            return Result.NOT_NEEDED;
        }
        if(target.getY()-feet>MAX_HEIGHT)return Result.NO_SAFE_TOWER;
        var existing=table.scaffoldPositions(worker.getUUID());
        BlockPos base;
        if(!existing.isEmpty()){
            base=existing.stream().min(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
        }else{
            base=findBase(level,target,feet,table,plan);
        }
        if(base==null)return Result.NO_SAFE_TOWER;
        int top=target.getY()-1;
        if(top-base.getY()+1>MAX_HEIGHT)return Result.NO_SAFE_TOWER;
        int needed=0;
        for(int y=base.getY();y<=top;y++){
            var p=new BlockPos(base.getX(),y,base.getZ());
            if(!level.hasChunkAt(p)||!level.mayInteract(player,p))
                return Result.NO_SAFE_TOWER;
            if(level.getBlockState(p).is(Blocks.SCAFFOLDING)){
                if(!table.isTrackedScaffold(p))return Result.NO_SAFE_TOWER;
            }else if(level.getBlockState(p).isAir())needed++;
            else return Result.NO_SAFE_TOWER;
        }
        int available=BuilderSupplyInventory.available(table).getOrDefault(
                "minecraft:scaffolding",0);
        if(available<needed)return Result.NEED_MATERIALS;
        for(int y=base.getY();y<=top;y++){
            var at=new BlockPos(base.getX(),y,base.getZ());
            if(level.getBlockState(at).is(Blocks.SCAFFOLDING))continue;
            if(!level.getBlockState(at.below()).isFaceSturdy(level,at.below(),Direction.UP)
                    &&!level.getBlockState(at.below()).is(Blocks.SCAFFOLDING))
                return Result.NO_SAFE_TOWER;
            var receipt=BuilderSupplyInventory.consume(table,Items.SCAFFOLDING);
            if(receipt==null)return Result.NEED_MATERIALS;
            if(!level.setBlock(at,Blocks.SCAFFOLDING.defaultBlockState(),3)){
                BuilderSupplyInventory.refund(table,receipt);
                return Result.NO_SAFE_TOWER;
            }
            if(!table.trackScaffold(worker.getUUID(),at)){
                level.setBlock(at,Blocks.AIR.defaultBlockState(),3);
                BuilderSupplyInventory.refund(table,receipt);
                return Result.NO_SAFE_TOWER;
            }
        }
        double cx=base.getX()+.5,cz=base.getZ()+.5;
        double dx=worker.getX()-cx,dz=worker.getZ()-cz;
        if(dx*dx+dz*dz>1.5){
            worker.setNoGravity(false);
            worker.getNavigation().moveTo(cx,base.getY(),cz,.85);
            return Result.CLIMBING;
        }
        if(worker.getY()<top+.1){
            worker.getNavigation().stop();
            // Gradual movement instead of teleporting. The tower is a real,
            // owned physical scaffold column; never climb without it.
            worker.setNoGravity(true);
            worker.setDeltaMovement(0,Math.min(.12,(top+.15-worker.getY())/10),0);
            return Result.CLIMBING;
        }
        worker.setDeltaMovement(0,0,0);
        worker.setNoGravity(false);
        return Result.NOT_NEEDED;
    }
    private static BlockPos findBase(ServerLevel level,BlockPos target,int feet,
            WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> plan){
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos adjacent=target.relative(side);
            for(int dy=0;dy<=5;dy++){
                BlockPos base=new BlockPos(adjacent.getX(),feet-dy,adjacent.getZ());
                if(!level.hasChunkAt(base)||!level.hasChunkAt(base.below()))continue;
                if(!level.getBlockState(base.below()).isFaceSturdy(level,
                        base.below(),Direction.UP))continue;
                boolean clear=true;
                for(int y=base.getY();y<target.getY();y++){
                    BlockPos at=new BlockPos(base.getX(),y,base.getZ());
                    if(!level.hasChunkAt(at)||!level.getBlockState(at).isAir()
                            ||table.isTrackedScaffold(at)){
                        clear=false;break;
                    }
                    for(var step:plan)
                        if(step.x()==at.getX()&&step.y()==at.getY()
                                &&step.z()==at.getZ()){clear=false;break;}
                    if(!clear)break;
                }
                if(clear)return base;
            }
        }
        return null;
    }
    /**
     * Instant top-down removal only when worker is safely on the ground.
     * Refunds exactly one block per confirmed tracked scaffold; if storage
     * is full leaves the corresponding real block intact for later cleanup.
     */
    public static void retrieve(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,boolean force){
        var positions=new java.util.ArrayList<>(table.scaffoldPositions(worker.getUUID()));
        if(positions.isEmpty())return;
        if(!force &&(!worker.onGround()||worker.getY()>positions.getFirst().getY()+2))
            return;
        positions.sort(Comparator.comparingInt(BlockPos::getY).reversed());
        for(BlockPos pos:positions){
            if(!level.hasChunkAt(pos))continue;
            if(!level.getBlockState(pos).is(Blocks.SCAFFOLDING)){
                table.untrackScaffold(worker.getUUID(),pos);continue;
            }
            // Reclaim from the top of the column; keep inventory room first.
            if(!BuilderSupplyInventory.storeScaffolding(table,new ItemStack(Items.SCAFFOLDING)))
                break;
            if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),3))
                table.untrackScaffold(worker.getUUID(),pos);
            else {
                // World declined removal. Refund would otherwise duplicate.
                var receipt=BuilderSupplyInventory.consume(table,Items.SCAFFOLDING);
                if(receipt!=null)table.setItem(receipt.slot(),receipt.previous());
                break;
            }
        }
    }
}
