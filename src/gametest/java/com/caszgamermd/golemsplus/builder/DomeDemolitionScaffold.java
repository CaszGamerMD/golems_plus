package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * DEMOLITION TEST ONLY: physically assemble and climb a real scaffold tower
 * when walking, jumping and the grappling hook fail to regain roof access.
 * The scaffold item comes from the actual worker's slot 7. No remote
 * placement, creative flight, instant vertical movement or world-pasted
 * towers. Every placement is within 3m of the worker's actual eye.
 *
 * Plan a return route BEFORE purchasing scaffolds: require a valid ground
 * dismount and an upper, supported roof landing independent of the target.
 * The landing is checked anew after each stage of construction. Other
 * support blocks remain unchanged.
 */
final class DomeDemolitionScaffold {
    private static final int MAX_STAGE=3;
    private static final int MATERIAL_SLOT=7;
    private static final int MAX_COLUMNS=4;
    private final ServerLevel level;
    private final BuilderGolem mob;
    private final Map<BlockPos,net.minecraft.world.level.block.state.BlockState> remaining;
    private final Set<BlockPos> owned=new HashSet<>();
    private final Map<BlockPos,Long> failed=new HashMap<>();
    private BlockPos column,landing;
    private int top,placed,climbs,dismounts,failedRoutes;
    private long started,progressTick,nextPlan;
    private boolean finished,abandoned;
    private String phase="NONE";
    DomeDemolitionScaffold(ServerLevel level,BuilderGolem mob,
            Map<BlockPos,net.minecraft.world.level.block.state.BlockState> remaining){
        this.level=level;this.mob=mob;this.remaining=remaining;
    }
    boolean active(){return column!=null;}
    int placed(){return placed;}
    int climbs(){return climbs;}
    int dismounts(){return dismounts;}
    String status(){return phase+" tower="+column+" landing="+landing
            +" top="+top+" placed="+placed+" climbs="+climbs
            +" dismounts="+dismounts+" rejected="+failedRoutes
            +" materials="+mob.getItem(MATERIAL_SLOT).getCount();}
    private boolean validClearColumn(BlockPos base,int maxY){
        if(level.getBlockState(base.below()).getCollisionShape(
                level,base.below()).isEmpty())return false;
        // The owner can safely step off at the bottom if this shaft
        // becomes unusable. Never build above an isolated pit.
        boolean groundExit=false;
        for(Direction side:Direction.Plane.HORIZONTAL)
            if(WorkerNavigation.canStandOn(level,mob,base.relative(side)))
                groundExit=true;
        if(!groundExit)return false;
        for(int y=base.getY();y<=maxY;y++){
            BlockPos p=new BlockPos(base.getX(),y,base.getZ());
            if(!level.hasChunkAt(p)
                    ||remaining.containsKey(p)
                    ||!level.getBlockState(p).isAir()
                        &&!owned.contains(p))
                return false;
        }
        return true;
    }
    private record Access(BlockPos base,BlockPos landing,double score){}
    /** Find a CLEAR vertical shaft beside a real, supported upper landing.
     * There is no pre-recorded route or fake work position. Candidates
     * come from the dome's remaining physical shell.
     */
    boolean tryStart(long tick){
        if(active()||tick<nextPlan||!mob.onGround()
                ||BuilderGrappleAccess.active(mob)
                ||mob.getItem(MATERIAL_SLOT).isEmpty()
                ||!mob.getItem(MATERIAL_SLOT).is(Items.SCAFFOLDING))
            return false;
        nextPlan=tick+180;
        BlockPos here=mob.blockPosition();
        int highest=remaining.keySet().stream().mapToInt(BlockPos::getY)
                .max().orElse(here.getY());
        if(highest-here.getY()<4)return false;
        List<Access> possible=new ArrayList<>();
        int checked=0;
        for(BlockPos roof:remaining.keySet()){
            if(roof.getY()<highest-3||roof.getY()>highest
                    ||++checked>800)continue;
            BlockPos stand=roof.above();
            if(!WorkerNavigation.canStandOn(level,mob,stand))continue;
            if(stand.getY()-here.getY()>38)continue;
            for(Direction side:Direction.Plane.HORIZONTAL){
                BlockPos base=new BlockPos(stand.getX()+side.getStepX(),
                        here.getY(),stand.getZ()+side.getStepZ());
                BlockPos topCell=base.atY(stand.getY()-1);
                if(failed.getOrDefault(base,0L)>tick
                        ||!validClearColumn(base,topCell.getY())
                        ||!WorkerNavigation.canStandOn(level,mob,base))
                    continue;
                // A scaffold column must be within actual horizontal
                // stepping distance of an upper supported landing.
                // The entity never simply flies from a scaffold to roof.
                double score=base.distSqr(here)
                        +Math.abs(stand.getY()-here.getY())*.2
                        +stand.distSqr(roof)*2;
                possible.add(new Access(base,stand,score));
                if(possible.size()>=150)break;
            }
            if(possible.size()>=150)break;
        }
        possible.sort(Comparator.comparingDouble(Access::score));
        for(Access path:possible.subList(0,Math.min(24,possible.size()))){
            if(WorkerNavigation.selectExecutableStand(
                    level,mob,List.of(path.base()))==null)continue;
            column=path.base().immutable();
            landing=path.landing().immutable();
            top=column.getY()-1;
            started=tick;progressTick=tick;
            phase="APPROACH";
            finished=false;abandoned=false;
            System.out.println("[DOME SCAFFOLD] PLANNED base="+column
                    +" roof="+landing+" height="+
                    (landing.getY()-column.getY())
                    +" real items="+mob.getItem(MATERIAL_SLOT).getCount());
            return true;
        }
        return false;
    }
    /** Called every GameTest work tick, while BuilderGolem.tick() drives
     * the existing physical scaffold climbing motion on every entity tick.
     */
    boolean tick(long now){
        if(!active())return false;
        if(!mob.isAlive())return false;
        if(BuilderScaffolding.isControllingMovement(mob)){
            if(now-started>3000){
                fail(now,"vertical controller timeout");
            }
            return true;
        }
        if(phase.equals("DISMOUNT")){
            if(mob.onGround()&&mob.blockPosition().equals(landing)){
                dismounts++;
                System.out.println("[DOME SCAFFOLD] LANDED physically at "
                        +landing+" blocks="+placed+" climbs="+climbs);
                reset();
                return true;
            }
            if(now-progressTick>180)fail(now,"dismount timeout");
            return true;
        }
        if(now-progressTick>450){fail(now,"no stage progress");return true;}
        if(!mob.onGround()){
            return true;
        }
        // After building the first scaffold, the worker can enter it
        // using the native move controller; all further ascent is a
        // vertical scaffold climb, never creative flight.
        double dx=mob.getX()-column.getX()-.5;
        double dz=mob.getZ()-column.getZ()-.5;
        if(dx*dx+dz*dz>1.10){
            if(!WorkerNavigation.routeToExact(level,mob,column,1.0)
                    &&now-progressTick>180)fail(now,"no base approach");
            return true;
        }
        if(mob.getY()<top+.80){
            phase="ASCEND";
            climbs++;
            BuilderScaffolding.begin(level,mob,column,top+1.0,false);
            return true;
        }
        if(top>=landing.getY()-1){
            if(!WorkerNavigation.canStandOn(level,mob,landing)){
                fail(now,"upper landing no longer supported");
                return true;
            }
            if(BuilderScaffolding.stepToPlatform(level,mob,landing)){
                phase="DISMOUNT";progressTick=now;
                return true;
            }
            fail(now,"physical step to roof refused");
            return true;
        }
        if(!mob.getItem(MATERIAL_SLOT).is(Items.SCAFFOLDING)
                ||mob.getItem(MATERIAL_SLOT).isEmpty()){
            fail(now,"ran out of physically carried scaffolds");return true;
        }
        int budget=MAX_STAGE;
        while(budget-->0 && top<landing.getY()-1){
            BlockPos p=column.atY(top+1);
            // Real Builder's placement reach is THREE blocks. Do not
            // create a prebuilt tower or place a block from ground level.
            Vec3 center=Vec3.atCenterOf(p);
            if(mob.getEyePosition().distanceToSqr(center)>9.0)break;
            if(!level.hasChunkAt(p)||remaining.containsKey(p)
                    ||!level.getBlockState(p).isAir()
                    ||!(level.getBlockState(p.below()).is(Blocks.SCAFFOLDING)
                        ||level.getBlockState(p.below()).isFaceSturdy(
                                level,p.below(),Direction.UP))){
                fail(now,"blocked scaffold placement "+p);return true;
            }
            // A scaffolding shaft is stable only above real scaffold or
            // solid footing. Every real block debits one actual item.
            if(!level.setBlock(p,Blocks.SCAFFOLDING.defaultBlockState(),3)){
                fail(now,"world rejected scaffold "+p);return true;
            }
            ItemStack supply=mob.getItem(MATERIAL_SLOT).copy();
            supply.shrink(1);
            mob.setItem(MATERIAL_SLOT,supply);
            owned.add(p.immutable());
            placed++;top++;progressTick=now;
            phase="BUILD";
            if(placed%8==0)System.out.println("[DOME SCAFFOLD] PLACED "
                    +placed+" physical blocks, top="+top
                    +" workerY="+mob.getY());
        }
        if(top>=column.getY()){
            phase="ASCEND";
            climbs++;
            BuilderScaffolding.begin(level,mob,column,top+1.0,false);
        }
        return true;
    }
    private void reset(){
        column=null;landing=null;phase="NONE";
    }
    private void fail(long now,String why){
        failedRoutes++;
        System.out.println("[DOME SCAFFOLD] REPLAN "+why+" "+status());
        failed.put(column,now+900);
        BuilderScaffolding.cancel(mob);
        nextPlan=now+220;
        reset();
    }
}
