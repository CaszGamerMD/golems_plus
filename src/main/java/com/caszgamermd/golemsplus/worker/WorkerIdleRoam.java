package com.caszgamermd.golemsplus.worker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;

/**
 * A relaxed idle patrol around the assigned workstation. Only call when
 * the worker has no active job; job navigation always takes precedence.
 * Never teleports, force-loads chunks or picks a goal outside the radius.
 */
public final class WorkerIdleRoam {
    private WorkerIdleRoam(){}
    private static final int RADIUS=10;

    public static void tick(ServerLevel level,PathfinderMob mob,BlockPos home){
        if(home==null||!level.hasChunkAt(home))return;
        double hx=home.getX()+.5, hy=home.getY()+1, hz=home.getZ()+.5;
        double dx=mob.getX()-hx, dz=mob.getZ()-hz;
        if(dx*dx+dz*dz>RADIUS*RADIUS
                ||Math.abs(mob.getY()-hy)>4){
            WorkerNavigation.routeTo(level,mob,home,.75);
            return;
        }
        if(!mob.getNavigation().isDone()||mob.getRandom().nextInt(3)!=0)
            return;
        for(int attempt=0;attempt<12;attempt++){
            int x=mob.getRandom().nextInt(17)-8;
            int z=mob.getRandom().nextInt(17)-8;
            if(x*x+z*z>64||x*x+z*z<4)continue;
            int y=mob.getRandom().nextInt(3)-1;
            BlockPos target=home.offset(x,y+1,z);
            if(!level.hasChunkAt(target)
                    ||!level.getBlockState(target).isAir()
                    ||!level.getBlockState(target.above()).isAir()
                    ||level.getBlockState(target.below())
                            .getCollisionShape(level,target.below()).isEmpty())continue;
            if(WorkerNavigation.routeTo(level,mob,target,.65))break;
        }
    }
}
