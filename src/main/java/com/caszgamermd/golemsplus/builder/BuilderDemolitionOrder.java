package com.caszgamermd.golemsplus.builder;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Physical demolition dependency ordering. Never mine a support when doing
 * so would leave a SINGLE adjacent planned block floating alone. Remove
 * the dependent leaf first, then re-evaluate its former support.
 *
 * Adjacency is face-to-face (six directions). The world, not just the
 * schematic map, determines whether other support exists: terrain, a
 * surviving scaffold, and unscheduled construction count as connections.
 * Empty air, water, and plants without collision do not support a block.
 *
 * Returns null when there is no orphaned neighbor. An isolated two-block
 * pair has no ordering that avoids a briefly isolated final block, so use
 * a deterministic highest-Y-first order rather than deadlocking forever.
 */
public final class BuilderDemolitionOrder {
    private BuilderDemolitionOrder(){}

    private static boolean exists(ServerLevel level,BlockPos p){
        return level.hasChunkAt(p)&&!level.getBlockState(p).isAir();
    }

    private static boolean supports(ServerLevel level,BlockPos p){
        if(!exists(level,p))return false;
        BlockState state=level.getBlockState(p);
        return !state.getCollisionShape(level,p).isEmpty();
    }

    private static boolean otherConnection(ServerLevel level,
            BlockPos block,BlockPos excluding){
        for(Direction side:Direction.values()){
            BlockPos near=block.relative(side);
            if(!near.equals(excluding)&&supports(level,near))return true;
        }
        return false;
    }

    /** True if a block currently has no physical face-connected support.
     * In particular, it is NOT called "floating" just because it is
     * absent from the remaining schematic map: permanent world terrain
     * and placed scaffold count.
     */
    public static boolean floatingAlone(ServerLevel level,BlockPos block){
        return exists(level,block)&&!otherConnection(level,block,null);
    }

    /**
     * The first lone dependent that MUST be mined before candidate.
     * The result is another still-present demolition target, never a
     * permanent landscape block or a schematic cell already removed.
     */
    public static BlockPos loneBlockFirst(ServerLevel level,
            Map<BlockPos,BlockState> remaining,BlockPos candidate){
        if(level==null||remaining==null||candidate==null
                ||!remaining.containsKey(candidate)||!exists(level,candidate))
            return null;
        for(Direction side:Direction.values()){
            BlockPos dependent=candidate.relative(side);
            if(!remaining.containsKey(dependent)
                    ||!exists(level,dependent)
                    ||otherConnection(level,dependent,candidate))
                continue;

            // No legal solution to the "leaf before support" rule in a
            // disconnected two-block pair: each is the other's only
            // support. Resolve it deterministically from top to bottom
            // instead of bouncing back and forth forever.
            if(!otherConnection(level,candidate,dependent)){
                boolean candidateFirst=candidate.getY()>dependent.getY()
                        ||candidate.getY()==dependent.getY()
                        &&(candidate.getX()<dependent.getX()
                        ||candidate.getX()==dependent.getX()
                        &&candidate.getZ()<dependent.getZ());
                if(candidateFirst)continue;
            }
            return dependent.immutable();
        }
        return null;
    }
}
