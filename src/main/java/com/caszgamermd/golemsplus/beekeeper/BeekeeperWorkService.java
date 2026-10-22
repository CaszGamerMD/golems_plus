package com.caszgamermd.golemsplus.beekeeper;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.phys.AABB;
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
        if(station.beeOutput()==null){
            station.setProblem("Link an output chest from the tool bag's job menu");
            return;
        }
        if(!level.hasChunkAt(station.beeOutput())){
            station.setProblem("Output container chunk unloaded");return;
        }
        if(!(level.getBlockEntity(station.beeOutput()) instanceof Container)){
            station.setProblem("Output container missing or invalid");return;
        }
        // No worker creation here: place crafted Beekeepers near this block.
        if(station.phase()==WorkerPhase.PAUSED)return;
        Set<BlockPos> claimed=new HashSet<>();
        for(BeekeeperGolem bee:crew)if(bee.job()>=1&&bee.job()<=4)claimed.add(bee.hive());
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
                        var tool=station;
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
            if(assigned){station.setProblem("");continue;}
            // A quiet hive is an opportunity for husbandry. Breed only
            // when there is no harvest job and two adult bees are ready.
            Container toolbox=station;
            if(toolbox!=null&&hasFlowerPair(toolbox)&&station.owner()!=null){
                Set<UUID> reserved=new HashSet<>();
                for(BeekeeperGolem working:crew)if(working.job()>=5){
                    if(working.mateA()!=null)reserved.add(working.mateA());
                    if(working.mateB()!=null)reserved.add(working.mateB());
                }
                BlockPos center=station.getBlockPos();
                var bees=level.getEntitiesOfClass(Bee.class,
                        new AABB(center).inflate(station.beeRadius(),6,station.beeRadius()),
                        b->b.isAlive()&&b.getAge()==0&&b.canFallInLove()
                                &&!b.isInLove()&&!reserved.contains(b.getUUID()));
                if(bees.size()>=2){
                    // Prefer bees already near one another for successful
                    // natural vanilla mating after the flowers are fed.
                    Bee first=null,second=null;double best=Double.MAX_VALUE;
                    for(int u=0;u<bees.size();u++)
                        for(int v=u+1;v<bees.size();v++){
                            double dist=bees.get(u).distanceToSqr(bees.get(v));
                            if(dist<best){
                                first=bees.get(u);second=bees.get(v);best=dist;
                            }
                        }
                    if(first!=null&&second!=null){
                        bee.startBreeding(first.getUUID(),second.getUUID());
                        station.setProblem("");
                        continue;
                    }
                }
            }
            station.setProblem("Waiting for ready hives, or two breedable bees and flowers");
        }
    }
    private static boolean hasFlowerPair(Container inventory){
        // Both flowers must come from one stack so we can return exactly
        // the same item if either bee becomes ineligible before breeding.
        for(int i=0;i<inventory.getContainerSize();i++){
            ItemStack stack=inventory.getItem(i);
            if(stack.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                        net.minecraft.resources.Identifier.tryParse("minecraft:bee_food")))&&stack.getCount()>=2)return true;
        }
        return false;
    }
    private static ItemStack takeFlowerPair(Container inventory){
        for(int i=0;i<inventory.getContainerSize();i++){
            ItemStack stack=inventory.getItem(i);
            if(stack.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                        net.minecraft.resources.Identifier.tryParse("minecraft:bee_food")))&&stack.getCount()>=2){
                ItemStack moved=inventory.removeItem(i,2);
                inventory.setChanged();
                return moved;
            }
        }
        return ItemStack.EMPTY;
    }
    private static boolean canBreed(Bee bee){
        return bee!=null&&bee.isAlive()&&bee.getAge()==0
                &&bee.canFallInLove()&&!bee.isInLove();
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
        if(job.beeOutput()==null)return;
        ServerPlayer owner=level.getServer().getPlayerList().getPlayer(worker.owner());
        if(owner==null||owner.level()!=level)return; // no building in unprotected offline areas
        Container toolbox=job;
        Container output=container(level,job.beeOutput());
        if(output==null){
            job.setProblem("Beekeeper output chest missing/unloaded");return;
        }
        switch(worker.job()){
            case 0 -> {
                // Idle roaming is handled even before tools are linked.
            }
            case 1 -> {
                if(!worker.moveTo(job.getBlockPos(),.85))break;
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
            case 5 -> {
                if(!worker.moveTo(job.getBlockPos(),.85))break;
                if(!worker.breedFlowers().isEmpty()){worker.stage(6);break;}
                ItemStack flowers=takeFlowerPair(toolbox);
                if(flowers.isEmpty()){worker.finishBreeding();break;}
                worker.setBreedFlowers(flowers);worker.stage(6);
            }
            case 6 -> {
                var a=worker.mateA()==null?null:level.getEntity(worker.mateA());
                var b=worker.mateB()==null?null:level.getEntity(worker.mateB());
                Bee first=a instanceof Bee bee?bee:null;
                Bee second=b instanceof Bee bee?bee:null;
                if(!canBreed(first)||!canBreed(second)){
                    worker.stage(7);break;
                }
                BlockPos near=first.blockPosition();
                if(!worker.moveTo(near,.83)){
                    if(worker.stuck())worker.stage(7);
                    break;
                }
                // A mating pair needs to be physically near each other.
                if(first.distanceToSqr(second)>49){
                    if(worker.stuck())worker.stage(7);
                    break;
                }
                if(worker.breedFlowers().getCount()<2){worker.stage(7);break;}
                first.setInLove(owner);
                second.setInLove(owner);
                worker.setBreedFlowers(ItemStack.EMPTY);
                worker.finishBreeding();
                job.setProblem("");
            }
            case 7 -> {
                if(!worker.breedFlowers().isEmpty()){
                    if(!worker.moveTo(job.getBlockPos(),.85))break;
                    if(!BeeInventory.insert(toolbox,worker.breedFlowers())){
                        job.setProblem("Toolbox full; Beekeeper keeping unused flowers");
                        break;
                    }
                    worker.setBreedFlowers(ItemStack.EMPTY);
                }
                worker.finishBreeding();
            }
            case 4 -> {
                if(!worker.tool().isEmpty()){
                    if(!worker.moveTo(job.getBlockPos(),.85))break;
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
