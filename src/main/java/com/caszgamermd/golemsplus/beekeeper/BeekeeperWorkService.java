package com.caszgamermd.golemsplus.beekeeper;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Real honey/comb harvesting with finite tools and actual output storage. */
public final class BeekeeperWorkService {
    private BeekeeperWorkService(){}
    public static void dispatch(ServerLevel level,WorkerStationBlockEntity station){
        if(level.getGameTime()%40!=0||!station.isBeekeeper()||station.owner()==null)
            return;
        List<BeekeeperGolem> crew=new ArrayList<>();
        for(var id:station.beekeeperWorkers()){
            if(level.getEntity(id) instanceof BeekeeperGolem golem)crew.add(golem);
            else {station.setProblem("Beekeeper unloaded; load its chunk");return;}
        }
        if(station.beeToolbox()==null||station.beeOutput()==null){
            station.setProblem("Link a Beekeeper Toolbox and output container in the job block");
            return;
        }
        if(!level.hasChunkAt(station.beeToolbox())||!level.hasChunkAt(station.beeOutput())){
            station.setProblem("Toolbox or output container chunk unloaded");return;
        }
        if(!(level.getBlockEntity(station.beeToolbox()) instanceof BeeToolboxBlockEntity)
                ||!(level.getBlockEntity(station.beeOutput()) instanceof Container)){
            station.setProblem("Missing toolbox or output container");return;
        }
        // No worker creation here: place crafted Beekeepers near this block.
        if(station.phase()==WorkerPhase.PAUSED)return;
        Set<BlockPos> claimed=new HashSet<>();
        for(BeekeeperGolem bee:crew)if(bee.job()!=0)claimed.add(bee.hive());
        for(int i=0;i<crew.size()&&i<station.crewTarget();i++){
            BeekeeperGolem bee=crew.get(i);
            if(bee.job()!=0)continue;
            boolean assigned=false;
            BlockPos p=station.getBlockPos();
            int radius=station.beeRadius();
            for(int dx=-radius;dx<=radius&&!assigned;dx++)
                for(int dz=-radius;dz<=radius&&!assigned;dz++)
                    for(int dy=-6;dy<=6&&!assigned;dy++){
                        if(dx*dx+dz*dz>radius*radius)continue;
                        BlockPos hive=p.offset(dx,dy,dz);
                        if(claimed.contains(hive)||!level.hasChunkAt(hive))continue;
                        BlockState block=level.getBlockState(hive);
                        if(!(block.getBlock() instanceof BeehiveBlock)
                                ||block.getValue(BeehiveBlock.HONEY_LEVEL)<5)continue;
                        var tool=level.getBlockEntity(station.beeToolbox());
                        var output=level.getBlockEntity(station.beeOutput());
                        if(!(tool instanceof Container box)||!(output instanceof Container out))
                            return;
                        // Favor comb only when mode allows it and shears exist.
                        var choice=BeeHarvestPolicy.choose(station.beeMode(),
                                has(box,Items.SHEARS),has(box,Items.GLASS_BOTTLE),
                                BeeInventory.canFit(out,new ItemStack(Items.HONEYCOMB,3)),
                                BeeInventory.canFit(out,new ItemStack(Items.HONEY_BOTTLE)));
                        if(choice==BeeHarvestPolicy.Choice.WAIT)continue;
                        bee.start(hive,choice==BeeHarvestPolicy.Choice.BOTTLE);
                        claimed.add(hive);assigned=true;
                    }
            if(assigned)station.setProblem("");
            else station.setProblem("Waiting for full beehives, tools or output capacity");
        }
    }
    private static boolean has(Container inventory,net.minecraft.world.item.Item item){
        for(int i=0;i<inventory.getContainerSize();i++)
            if(inventory.getItem(i).is(item))return true;
        return false;
    }
    private static Container container(ServerLevel level,BlockPos pos){
        return level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof Container c?c:null;
    }
    public static void tickWorker(ServerLevel level,BeekeeperGolem worker){
        BlockPos home=worker.home();
        if(worker.owner()==null||!level.hasChunkAt(home))return;
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity job)
                ||!job.isBeekeeper()||!worker.owner().equals(job.owner())){
            worker.dropCargo(level);worker.discard();return;
        }
        if(!job.beekeeperWorkers().contains(worker.getUUID()))return;
        if(job.phase()==WorkerPhase.PAUSED){worker.getNavigation().stop();return;}
        if(worker.job()==0)
            com.caszgamermd.golemsplus.worker.WorkerIdleRoam.tick(level,worker,home);
        if(job.beeToolbox()==null||job.beeOutput()==null)return;
        ServerPlayer owner=level.getServer().getPlayerList().getPlayer(worker.owner());
        if(owner==null||owner.level()!=level)return; // no building in unprotected offline areas
        Container toolbox=container(level,job.beeToolbox());
        Container output=container(level,job.beeOutput());
        if(!(toolbox instanceof BeeToolboxBlockEntity)||output==null){
            job.setProblem("Beekeeper containers missing/unloaded");return;
        }
        switch(worker.job()){
            case 0 -> {
                // Idle roaming is handled even before tools are linked.
            }
            case 1 -> {
                if(!worker.moveTo(job.beeToolbox(),.85))break;
                ItemStack taken=BeeInventory.takeOne(toolbox,
                        worker.bottles()?Items.GLASS_BOTTLE:Items.SHEARS);
                if(taken.isEmpty()){worker.stage(0);break;}
                worker.setTool(taken);worker.stage(2);
            }
            case 2 -> {
                BlockPos hive=worker.hive();
                if(!level.hasChunkAt(hive)||!level.mayInteract(owner,hive)){
                    job.setProblem("Hive unloaded or blocked by protection");break;
                }
                if(!(level.getBlockState(hive).getBlock() instanceof BeehiveBlock)
                        ||level.getBlockState(hive).getValue(BeehiveBlock.HONEY_LEVEL)<5){
                    worker.stage(4);break; // return unspent tool
                }
                if(!worker.moveTo(hive,.88)){
                    if(worker.stuck()){job.setProblem("Cannot reach hive "+hive);worker.stage(4);}
                    break;
                }
                ItemStack result=worker.bottles()?new ItemStack(Items.HONEY_BOTTLE):
                        new ItemStack(Items.HONEYCOMB,3);
                if(!BeeInventory.canFit(output,result)){
                    job.setProblem("Output container full");break;
                }
                if(worker.tool().isEmpty()){
                    worker.stage(4);break;
                }
                // Vanilla dispenser-equivalent bee-safe collection; preserve
                // the resident bees and every other BE field. No bee-killing
                // or silk-touching the nest. Only a full hive can be reset.
                BlockState state=level.getBlockState(hive);
                if(!level.setBlock(hive,state.setValue(BeehiveBlock.HONEY_LEVEL,0),3)){
                    worker.stage(4);break;
                }
                if(worker.bottles())worker.setTool(ItemStack.EMPTY);
                else {
                    ItemStack shears=worker.tool().copy();
                    if(shears.isDamageableItem()){
                        shears.setDamageValue(shears.getDamageValue()+1);
                        if(shears.getDamageValue()>=shears.getMaxDamage())
                            shears=ItemStack.EMPTY;
                    }
                    worker.setTool(shears);
                }
                worker.setHarvest(result);worker.stage(3);
            }
            case 3 -> {
                if(!worker.moveTo(job.beeOutput(),.88))break;
                if(worker.harvest().isEmpty()){worker.stage(4);break;}
                if(BeeInventory.insert(output,worker.harvest())){
                    worker.setHarvest(ItemStack.EMPTY);
                    worker.stage(4);
                    job.setProblem("");
                }else job.setProblem("Output container full; keep cargo on golem");
            }
            case 4 -> {
                if(!worker.tool().isEmpty()){
                    if(!worker.moveTo(job.beeToolbox(),.85))break;
                    if(!BeeInventory.insert(toolbox,worker.tool())){
                        job.setProblem("Toolbox full; holding shears safely");break;
                    }
                    worker.setTool(ItemStack.EMPTY);
                }
                worker.stage(0);worker.moveTo(home,.82);
            }
        }
    }
}
