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
        // Existing crafted crew members execute the job; the station never
        // materializes extra golems automatically.
        if(level.getGameTime()%20!=0 || !table.isBuilder()
                ||table.activeOrder()==null||!table.hasPlacedSchematic()
                ||table.phase()==WorkerPhase.PAUSED)return;
        var linked=table.builderWorkerIds();
        // Never replace a registered but unloaded worker: its work, materials
        // and provisional scaffold could be persisted in another chunk.
        for(UUID id:linked)if(level.getEntity(id)==null){
            table.setProblem("Builder crew member unloaded; load its chunk");
            return;
        }
        // Wait for the player to craft and place builders beside the table.
    }
    public static void tick(ServerLevel level,BuilderGolem worker){
        BlockPos home=worker.home();
        if(worker.owner()==null||!level.hasChunkAt(home))return;
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)
                ||!table.isBuilder()||!worker.owner().equals(table.owner())){
            worker.discard();return;
        }
        if(!table.builderWorkerIds().contains(worker.getUUID())){
            // Never discard inventory-bearing entities: a misconfigured
            // workstation must not silently destroy delivered shulker cargo.
            table.setProblem("Unrecognized Builder Golem near workstation");
            return;
        }
        if(table.activeOrder()==null||table.phase()==WorkerPhase.COMPLETE){
            worker.setNoGravity(false);
            BuilderScaffolding.retrieve(level,worker,table,false);
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
            if(!worker.hasSupplyBox()&&table.supplyRequest()==null)
                com.caszgamermd.golemsplus.worker.WorkerIdleRoam.tick(level,worker,home);
            return;
        }
        if(table.phase()==WorkerPhase.PAUSED){
            worker.getNavigation().stop();
            worker.setNoGravity(false);return;
        }
        int membership=table.builderWorkerIds().indexOf(worker.getUUID());
        if(membership>=table.crewTarget()&&table.claimedStep(worker.getUUID())<0
                &&!worker.hasSupplyBox()){
            worker.setNoGravity(false);
            BuilderScaffolding.retrieve(level,worker,table,false);
            worker.getNavigation().moveTo(home.getX()+.5,home.getY()+1,home.getZ()+.5,.8);
            return;
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
            int index=table.claimBuilderStep(worker.getUUID());
            if(index<0)return; // other workers own every slot in this window
            var step=plan.steps().get(index);
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
                table.completeBuilderStep(worker.getUUID(),index);
                BuilderScaffolding.retrieve(level,worker,table,false);
                table.setProblem("");return;
            }
            if(!existing.isAir()){
                table.setProblem("Occupied target "+pos+"; no destructive replacement");
                return;
            }
            if(!state.state().canSurvive(level,pos)){
                table.setProblem("Missing supporting blocks at "+pos);return;
            }
            double horizontal=Math.pow(worker.getX()-(pos.getX()+.5),2)
                    +Math.pow(worker.getZ()-(pos.getZ()+.5),2);
            if(pos.getY()-worker.getY()>2.5 && horizontal>=36){
                // Reach the base of the tower on foot before laying any
                // temporary blocks; never construct remotely.
                worker.getNavigation().moveTo(pos.getX()+.5,
                        worker.blockPosition().getY(),pos.getZ()+.5,.85);
                return;
            }
            if(pos.getY()-worker.getY()>2.5 && horizontal<36){
                var climbing=BuilderScaffolding.reach(level,worker,table,owner,pos,
                        plan.steps());
                if(climbing==BuilderScaffolding.Result.NEED_MATERIALS){
                    table.setProblem("Needs scaffolding for height "+pos.getY()
                            +" (minecraft:scaffolding)");
                    if(BuilderWarehouseCoordinator.requestScaffolding(level,worker,
                            table,owner,plan.steps()))return;
                    return;
                }
                if(climbing==BuilderScaffolding.Result.NO_SAFE_TOWER){
                    table.setProblem("No clear scaffold column beside "+pos);
                    return;
                }
                if(climbing==BuilderScaffolding.Result.CLIMBING)return;
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
            table.completeBuilderStep(worker.getUUID(),index);
            BuilderSupplyInventory.recycleEmptyShulkers(table);
            BuilderScaffolding.retrieve(level,worker,table,false);
            table.setProblem("");
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
