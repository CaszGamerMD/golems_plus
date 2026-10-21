package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * First safe construction pass: air-only placement, one real item per block,
 * physical pathfinding and persisted step index. Never force loads, break
 * existing structures, clone block entities or modify non-air blocks.
 */
public final class BuilderBuildService {
    private record Cache(String file, int rotation,int mirror,BlockPos origin,
                         List<BuilderConstructionPlan.Step> steps){}
    private static final Map<BuilderGolem,Cache> cached=new WeakHashMap<>();
    private BuilderBuildService(){}
    public static void dispatch(ServerLevel level,WorkerStationBlockEntity table){
        BuilderWarehouseCoordinator.pollTable(level,table);
        if(level.getGameTime()%20!=0 || !table.isBuilder()
                ||table.activeOrder()==null||!table.hasPlacedSchematic()
                ||table.phase()==WorkerPhase.PAUSED)return;
        UUID workerId=table.builderWorkerId();
        if(workerId!=null){
            // A missing worker may be in an unloaded chunk: do not spawn a
            // second worker with the same active order.
            if(level.getEntity(workerId)==null){
                table.setProblem("Builder worker chunk unloaded; return to its location");
            }
            return;
        }
        BlockPos p=table.getBlockPos();
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.assign(p,table.owner());
        worker.setPos(p.getX()+.5,p.getY()+1,p.getZ()+.5);
        if(level.addFreshEntity(worker))table.setBuilderWorkerId(worker.getUUID());
    }
    public static void tick(ServerLevel level,BuilderGolem worker){
        BlockPos home=worker.home();
        if(worker.owner()==null||!level.hasChunkAt(home))return;
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)
                ||!table.isBuilder()||!worker.owner().equals(table.owner())){
            worker.discard();return;
        }
        if(table.builderWorkerId()!=null && !table.builderWorkerId().equals(worker.getUUID())){
            worker.discard();return;
        }
        if(table.activeOrder()==null||table.phase()==WorkerPhase.COMPLETE){
            // A completed/canceled plan may still own boxed warehouse stock.
            // Recover real cargo first rather than losing it on job end.
            if(worker.hasSupplyBox()||table.supplyRequest()!=null){
                ServerPlayer recipient=level.getServer().getPlayerList()
                        .getPlayer(worker.owner());
                if(recipient!=null&&recipient.level()==level)try{
                    Cache outstanding=cache(table,worker);
                    if(BuilderWarehouseCoordinator.tick(level,worker,table,
                            recipient,outstanding.steps()))return;
                }catch(Exception ignored){}
            }
            if(worker.distanceToSqr(home.getX()+.5,home.getY()+1,home.getZ()+.5)>9)
                worker.getNavigation().moveTo(home.getX()+.5,home.getY()+1,
                        home.getZ()+.5,.85);
            return;
        }
        if(table.phase()==WorkerPhase.PAUSED){
            worker.getNavigation().stop();return;
        }
        ServerPlayer owner=level.getServer().getPlayerList().getPlayer(worker.owner());
        if(owner==null||owner.level()!=level){
            table.setProblem("Owner must be online in the same dimension");
            return;
        }
        try{
            Cache plan=cache(table,worker);
            if((worker.hasSupplyBox()||table.supplyRequest()!=null)
                    &&BuilderWarehouseCoordinator.tick(level,worker,table,
                            owner,plan.steps()))return;
            if(table.builderCursor()>=plan.steps().size()){
                table.setPhase(WorkerPhase.COMPLETE);return;
            }
            var step=plan.steps().get(table.builderCursor());
            BlockPos pos=new BlockPos(step.x(),step.y(),step.z());
            if(Math.abs((long)pos.getX()-home.getX())>256||
                    Math.abs((long)pos.getZ()-home.getZ())>256){
                table.setProblem("Build extends beyond 256-block Builder range");
                return;
            }
            if(!level.hasChunkAt(pos)){
                table.setProblem("Build chunk is not loaded");return;
            }
            if(!level.mayInteract(owner,pos)){
                table.setProblem("Owner cannot modify this location");return;
            }
            var state=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            if(state==null){
                table.setProblem("Unsupported block state: "+step.encodedState());return;
            }
            BlockState existing=level.getBlockState(pos);
            if(existing.equals(state.state())){
                table.advanceBuilder();table.setProblem("");return;
            }
            if(!existing.isAir()){
                table.setProblem("Occupied target "+pos+"; no destructive replacement");
                return;
            }
            if(!state.state().canSurvive(level,pos)){
                table.setProblem("Missing supporting blocks at "+pos);return;
            }
            if(worker.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>14){
                if(!worker.getNavigation().moveTo(pos.getX()+.5,pos.getY(),
                        pos.getZ()+.5,.85))
                    table.setProblem("Builder cannot path to "+pos);
                return;
            }
            BuilderSupplyInventory.Receipt payment=
                    BuilderSupplyInventory.consume(table,state.item());
            if(payment==null){
                if(BuilderWarehouseCoordinator.request(level,worker,table,
                        owner,plan.steps()))return;
                table.setProblem("Missing "+step.requiredItem()+" in Builder Table");
                return;
            }
            // The real consumed stack is debited from either a loose slot
            // or its packed shulker box BEFORE the world changes.
            if(!level.setBlock(pos,state.state(),3)){
                BuilderSupplyInventory.refund(table,payment);
                table.setProblem("World rejected placement at "+pos);
                return;
            }
            table.advanceBuilder();table.setProblem("");
        }catch(Exception exception){
            table.setProblem("Builder paused: "+exception.getMessage());
        }
    }
    private static Cache cache(WorkerStationBlockEntity table,BuilderGolem worker)
            throws Exception {
        Cache previous=cached.get(worker);
        if(previous!=null&&previous.file().equals(table.builderFile())
                &&previous.rotation()==table.builderRotation()
                &&previous.mirror()==table.builderMirror()
                &&previous.origin().equals(table.builderOrigin()))return previous;
        if(!table.hasPlacedSchematic())throw new IllegalArgumentException("No placed schematic");
        Path file=FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("schematics").resolve(table.builderFile());
        if(!Files.isRegularFile(file))throw new IllegalArgumentException("Saved schematic missing");
        var schematic=LitematicFileReader.read(file);
        var site=new BuildSite(table.builderOrigin().getX(),table.builderOrigin().getY(),
                table.builderOrigin().getZ(),
                BuildSite.Rotation.values()[table.builderRotation()/90],
                BuildSite.Mirror.values()[table.builderMirror()]);
        var result=BuilderConstructionPlan.create(schematic,site);
        if(!result.unsupported().isEmpty())
            throw new IllegalArgumentException("Unsupported states in selected placement");
        Cache next=new Cache(table.builderFile(),table.builderRotation(),
                table.builderMirror(),table.builderOrigin(),result.steps());
        cached.put(worker,next);
        return next;
    }
}
