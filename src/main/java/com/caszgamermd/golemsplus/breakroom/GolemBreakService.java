package com.caszgamermd.golemsplus.breakroom;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.effect.*;
import com.caszgamermd.golemsplus.worker.*;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
/** Break reservations are temporary; the actual speed effect is saved by vanilla. */
public final class GolemBreakService {
 private static final Map<PathfinderMob,Rest> workers=new WeakHashMap<>();
 private static final class Rest {BlockPos furniture,stand;long chooseAt;int rest;boolean ready,working;}
 private static BlockPos home(PathfinderMob m){
  if(m instanceof com.caszgamermd.golemsplus.builder.BuilderGolem g)return g.home();
  if(m instanceof com.caszgamermd.golemsplus.beekeeper.BeekeeperGolem g)return g.home();
  if(m instanceof com.caszgamermd.golemsplus.trader.TraderGolem g)return g.home();
  if(m instanceof com.caszgamermd.golemsplus.cooking.ChefGolem g)return g.home();
  if(m instanceof com.caszgamermd.golemsplus.fishing.FisherGolem g)return g.home();
  return null;
 }
 public static boolean takingBreak(PathfinderMob m){var s=workers.get(m);return s!=null&&s.furniture!=null&&!s.working;}
 public static void initialize(){
  ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{if(entity instanceof PathfinderMob m&&home(m)!=null)workers.put(m,new Rest());});
  ServerEntityEvents.ENTITY_UNLOAD.register((entity,world)->workers.remove(entity));
  ServerTickEvents.END_SERVER_TICK.register(server->{for(var row:new ArrayList<>(workers.entrySet()))if(row.getKey().level() instanceof ServerLevel world&&world.getGameTime()%5==0)tick(world,row.getKey(),row.getValue());});
 }
 private static void tick(ServerLevel world,PathfinderMob mob,Rest s){
  BlockPos home=home(mob);if(home==null||!mob.isAlive()||mob.isPassenger()||!world.hasChunkAt(home))return;
  if(!(world.getBlockEntity(home) instanceof WorkerStationBlockEntity table))return;
  boolean working=(table.activeOrder()!=null||table.chefBusy()||table.isFishing())&&table.phase()!=WorkerPhase.PAUSED&&table.phase()!=WorkerPhase.COMPLETE&&table.phase()!=WorkerPhase.IDLE;
  if(working){
   if(!s.working&&(s.ready||mob.getTags().contains("golems_plus.rested"))){mob.addEffect(new MobEffectInstance(MobEffects.SPEED,3600,0));s.ready=false;mob.removeTag("golems_plus.rested");}
   if(s.furniture!=null)WorkerNavigation.stop(mob);
   s.furniture=null;s.stand=null;s.rest=0;s.working=true;return;
  }
  s.working=false;
  long now=world.getGameTime();
  if(s.furniture!=null){
   if(!BreakRoomContent.furniture(world.getBlockState(s.furniture).getBlock())||now>s.chooseAt+600){s.furniture=null;s.rest=0;return;}
   if(mob.distanceToSqr(s.stand.getX()+.5,s.stand.getY(),s.stand.getZ()+.5)<1){
    WorkerNavigation.stop(mob);mob.getLookControl().setLookAt(s.furniture.getX()+.5,s.furniture.getY()+.8,s.furniture.getZ()+.5);
    if(now%40==0&&(world.getBlockState(s.furniture).is(BreakRoomContent.ARCADE)||world.getBlockState(s.furniture).is(BreakRoomContent.SNACK_MACHINE)))mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    s.rest+=5;if(s.rest>=200){s.ready=true;mob.addTag("golems_plus.rested");}
    if(s.rest>=240+mob.getRandom().nextInt(160)){s.furniture=null;s.rest=0;s.chooseAt=now+40+mob.getRandom().nextInt(160);}
   }else if(!WorkerNavigation.routeToExact(world,mob,s.stand,.65)){s.furniture=null;s.rest=0;s.chooseAt=now+100;}
   return;
  }
  if(now<s.chooseAt)return;s.chooseAt=now+80+mob.getRandom().nextInt(160);
  var candidates=new ArrayList<BlockPos>();
  for(BlockPos p:BlockPos.betweenClosed(home.offset(-12,-2,-12),home.offset(12,3,12)))
   if(world.hasChunkAt(p)&&BreakRoomContent.furniture(world.getBlockState(p).getBlock())&&workers.values().stream().noneMatch(r->p.equals(r.furniture)))candidates.add(p.immutable());
  Collections.shuffle(candidates,new Random(mob.getRandom().nextLong()));
  for(BlockPos p:candidates)for(Direction d:Direction.Plane.HORIZONTAL){BlockPos stand=p.relative(d);
   if(WorkerNavigation.canStandOn(world,mob,stand)&&WorkerNavigation.routeToExact(world,mob,stand,.65)){s.furniture=p;s.stand=stand;s.rest=0;s.chooseAt=now;return;}
  }
 }
}
