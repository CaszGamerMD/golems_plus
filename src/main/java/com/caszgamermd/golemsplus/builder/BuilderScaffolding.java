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
        // Prefer an existing walkable staircase or completed platform at
        // the target's exact X/Z before spending a single scaffold item.
        // Only route on real validated physical paths; never teleport.
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos landing=target.relative(side).below();
            if(!level.hasChunkAt(landing))continue;
            if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .canReachExact(level,worker,landing))continue;
            // When a Builder is already on a viable existing platform,
            // it must continue to PLACE rather than re-requesting a route
            // to its own feet and returning CLIMBING forever.
            if(worker.distanceToSqr(landing.getX()+.5,
                    landing.getY()+.5,landing.getZ()+.5)<2.25){
                worker.setNoGravity(false);
                return Result.NOT_NEEDED;
            }
            if(com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .routeToExact(level,worker,landing,1.0))
                return Result.CLIMBING;
        }
        var existing=table.scaffoldPositions(worker.getUUID());
        // Do not cling to a tower from a PREVIOUS work order. It can be
        // dozens of blocks away, provoking an endless move/climb loop.
        BlockPos base=existing.stream()
                .filter(p->Math.abs(p.getX()-target.getX())<=1
                        &&Math.abs(p.getZ()-target.getZ())<=1)
                .min(Comparator.comparingInt(BlockPos::getY)).orElse(null);
        if(base==null)base=findBase(level,worker,target,feet,table,plan);
        if(base==null)return Result.NO_SAFE_TOWER;
        double bx=base.getX()+.5,bz=base.getZ()+.5;
        double approachX=worker.getX()-bx,approachZ=worker.getZ()-bz;
        // Position at the foot of the selected column *first*. The old
        // implementation placed its full 48-block tower remotely, then
        // tried to walk through buildings to reach it.
        if(approachX*approachX+approachZ*approachZ>1.20){
            worker.setNoGravity(false);
            if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .routeToExact(level,worker,base,1.0)){
                // Vanilla exact-path range may be limited on a large
                // schematic. Travel toward the X/Z first, then validate
                // the exact foot position once within local navigation.
                if(worker.blockPosition().distSqr(base)>16*16)
                    com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .routeTo(level,worker,base,1.0);
                else return Result.NO_SAFE_TOWER;
            }
            return Result.CLIMBING;
        }
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
        var load=BuilderSupplyInventory.loadScaffold(level,worker,table,needed);
        if(load==BuilderSupplyInventory.ScaffoldLoad.MOVING)return Result.CLIMBING;
        if(load==BuilderSupplyInventory.ScaffoldLoad.MISSING)return Result.NEED_MATERIALS;
        for(int y=base.getY();y<=top;y++){
            var at=new BlockPos(base.getX(),y,base.getZ());
            if(level.getBlockState(at).is(Blocks.SCAFFOLDING))continue;
            if(!level.getBlockState(at.below()).isFaceSturdy(level,at.below(),Direction.UP)
                    &&!level.getBlockState(at.below()).is(Blocks.SCAFFOLDING))
                return Result.NO_SAFE_TOWER;
            var receipt=BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING);
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
            com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .routeToExact(level,worker,base,1.0);
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
    private static BlockPos findBase(ServerLevel level,BuilderGolem worker,
            BlockPos target,int feet,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> plan){
        java.util.List<Direction> sides=new java.util.ArrayList<>();
        for(Direction side:Direction.Plane.HORIZONTAL)sides.add(side);
        // Use the nearest adjacent X/Z to approach the future block and
        // climb vertically. Other sides remain fallbacks around obstacles.
        sides.sort(Comparator.comparingDouble(side->{
            BlockPos p=target.relative(side);
            return worker.distanceToSqr(p.getX()+.5,feet,p.getZ()+.5);
        }));
        for(Direction side:sides){
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
                if(clear&&(worker.blockPosition().distSqr(base)>16*16
                        ||com.caszgamermd.golemsplus.worker.WorkerNavigation
                                .canReachExact(level,worker,base)))
                    return base;
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
        positions.sort(Comparator.comparingInt((BlockPos block)->block.getY()).reversed());
        for(BlockPos pos:positions){
            if(!level.hasChunkAt(pos))continue;
            if(!level.getBlockState(pos).is(Blocks.SCAFFOLDING)){
                table.untrackScaffold(worker.getUUID(),pos);continue;
            }
            // Check storage before removal. The real block is removed once,
            // then the exact scaffold item is returned to the Planner.
            if(!BuilderSupplyInventory.canStoreScaffolding(table))break;
            if(!level.setBlock(pos,Blocks.AIR.defaultBlockState(),3))break;
            if(!BuilderSupplyInventory.storeScaffolding(table,
                    new ItemStack(Items.SCAFFOLDING))){
                // Unexpected insertion failure: return physical item as a
                // drop instead of losing it or synthesizing a later refund.
                net.minecraft.world.Containers.dropItemStack(level,
                        pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,
                        new ItemStack(Items.SCAFFOLDING));
            }
            table.untrackScaffold(worker.getUUID(),pos);
        }
    }
}
