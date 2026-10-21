package com.caszgamermd.golemsplus.worker;

import com.caszgamermd.golemsplus.beekeeper.BeekeeperGolem;
import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.cooking.ChefGolem;
import com.caszgamermd.golemsplus.trader.TraderGolem;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Crafted worker: use on the ground near an owned matching job block.
 * An entity is not created for free by the workstation. No fishing item
 * until there is an actual fishing worker entity.
 */
public final class WorkerGolemItem extends Item {
    private final WorkerSpecialty profession;
    public WorkerGolemItem(WorkerSpecialty profession,Properties properties) {
        super(properties);
        this.profession=profession;
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        Player player=context.getPlayer();
        if(player==null)return InteractionResult.PASS;
        if(!(context.getLevel() instanceof ServerLevel level))
            return InteractionResult.SUCCESS;
        BlockPos placed=context.getClickedPos().relative(context.getClickedFace());
        if(!level.getBlockState(placed).isAir()
                ||!level.getBlockState(placed.above()).isAir()) {
            player.displayClientMessage(Component.literal(
                    "Need two blocks of empty space to place this Golem"),true);
            return InteractionResult.FAIL;
        }
        WorkerStationBlockEntity station=null;
        BlockPos nearest=null;
        double best=Double.MAX_VALUE;
        for(int dx=-10;dx<=10;dx++)for(int dy=-5;dy<=5;dy++)
            for(int dz=-10;dz<=10;dz++){
                BlockPos pos=placed.offset(dx,dy,dz);
                double distance=placed.distSqr(pos);
                if(distance>100||distance>=best||!level.hasChunkAt(pos))continue;
                if(!(level.getBlockEntity(pos) instanceof WorkerStationBlockEntity found)
                        ||!(found.getBlockState().getBlock()
                             instanceof com.caszgamermd.golemsplus.world.WorkerStationBlock block)
                        ||block.specialty()!=profession
                        ||!level.mayInteract(player,pos)
                        ||(found.owner()!=null
                            &&!found.owner().equals(player.getUUID())))continue;
                station=found;nearest=pos;best=distance;
            }
        if(station==null){
            player.displayClientMessage(Component.literal(
                    "Place near your matching job block (within 10 blocks)"),true);
            return InteractionResult.FAIL;
        }
        if(!station.claimIfUnowned(player.getUUID())){
            player.displayClientMessage(Component.literal(
                    "This job block belongs to another player"),true);
            return InteractionResult.FAIL;
        }
        int count=switch(profession) {
            case BUILDER -> station.builderWorkerIds().size();
            case TRADER -> station.traderWorkerIds().size();
            case BEEKEEPER -> station.beekeeperWorkers().size();
            case COOKING -> station.chefWorkerId()==null?0:1;
            case FISHING -> 4;
        };
        if(count>= (profession==WorkerSpecialty.COOKING?1:4)){
            player.displayClientMessage(Component.literal(
                    "This workstation already has its maximum crew"),true);
            return InteractionResult.FAIL;
        }
        PathfinderMob worker=switch(profession) {
            case BUILDER -> new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            case TRADER -> new TraderGolem(WorksiteContent.TRADER_GOLEM,level);
            case BEEKEEPER -> new BeekeeperGolem(WorksiteContent.BEEKEEPER_GOLEM,level);
            case COOKING -> new ChefGolem(WorksiteContent.CHEF_GOLEM,level);
            case FISHING -> null;
        };
        if(worker==null)return InteractionResult.FAIL;
        if(worker instanceof BuilderGolem g)g.assign(nearest,player.getUUID());
        else if(worker instanceof TraderGolem g)g.assign(nearest,player.getUUID());
        else if(worker instanceof BeekeeperGolem g)g.assign(nearest,player.getUUID());
        else if(worker instanceof ChefGolem g)g.assign(nearest,player.getUUID());
        worker.setPos(placed.getX()+.5,placed.getY(),placed.getZ()+.5);
        if(!level.addFreshEntity(worker)){
            player.displayClientMessage(Component.literal("Cannot place Golem here"),true);
            return InteractionResult.FAIL;
        }
        if(!station.registerCraftedWorker(profession,worker.getUUID())){
            worker.discard();
            player.displayClientMessage(Component.literal(
                    "Could not register Golem with this workstation"),true);
            return InteractionResult.FAIL;
        }
        if(!player.getAbilities().instabuild)
            context.getItemInHand().shrink(1);
        player.displayClientMessage(Component.literal(
                profession.name()+" Golem assigned to "+nearest.toShortString()),true);
        return InteractionResult.SUCCESS;
    }
}
