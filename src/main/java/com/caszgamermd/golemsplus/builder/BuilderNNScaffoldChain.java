package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only physical scaffold dependency hint for normal schematic cells.
 * Never authorizes unsupported scaffolding: survival, route, real inventory
 * and a post-placement exit remain mandatory. */
public final class BuilderNNScaffoldChain {
    private BuilderNNScaffoldChain(){}
    public static boolean enabled(){
        return Boolean.parseBoolean(System.getProperty("golems.nn.scaffoldChain",
                System.getenv().getOrDefault("GOLEMS_NN_SCAFFOLD_CHAIN","false")));
    }
    /** No static palette distance assumption: Minecraft recomputes distance
     * and bottom from the actual world's present neighbors. */
    public static int priority(boolean scaffold,boolean survives,
            boolean belowSupport,boolean sideScaffold){
        if(!scaffold||!survives)return 0;
        // First establish vertical anchors, then extend an already-paid
        // horizontal chain. An unsupported horizontal cell stays deferred.
        return belowSupport?3:sideScaffold?2:1;
    }
    static int contact(ServerLevel world,BlockPos at,BlockState expected){
        if(!enabled()||!expected.is(Blocks.SCAFFOLDING))return 0;
        boolean survives=expected.canSurvive(world,at);
        if(!survives)return 0;
        boolean below=!world.getBlockState(at.below()).isAir();
        boolean side=false;
        for(Direction direction:Direction.Plane.HORIZONTAL){
            if(world.getBlockState(at.relative(direction)).is(Blocks.SCAFFOLDING)){
                side=true;break;
            }
        }
        return priority(true,survives,below,side);
    }
    static double costBonus(ServerLevel world,BlockPos at,BlockState state){
        return contact(world,at,state)*80.0;
    }
}
