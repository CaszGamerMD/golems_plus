package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;

/** Pure, deterministic scaffold-site comparison for the live Builder AI.
 * Hard safety checks (solid support, clear entire physical column, reach,
 * owner permissions, and owned tower exclusivity) happen BEFORE scoring.
 * A low score wins. The score stores no fixed path or schematic route.
 */
public final class BuilderScaffoldSiteScore {
    private BuilderScaffoldSiteScore(){}

    /** Step costs approximate walking (10), climbing (13) and materials
     * (2). A reusable existing column avoids the material/ownership cost.
     * Coverage rewards an aisle-side elevator serving multiple REAL work
     * cells on the current or next two layers, without permitting towers
     * to overwrite those cells. */
    public static double score(BlockPos base,BlockPos worker,BlockPos work,
            int newBlocks,int nearbyWork,int futureConflict,
            boolean reused){
        int dx=base.getX()-work.getX(), dz=base.getZ()-work.getZ();
        int reach=dx*dx+dz*dz;
        int travel=Math.abs(base.getX()-worker.getX())
                +Math.abs(base.getZ()-worker.getZ());
        int climb=Math.max(0,work.getY()-base.getY());
        return reach*3.0 + travel*1.4 + climb*.65
                +newBlocks*2.0 +futureConflict*1000
                -(reused?70:0)-Math.min(12,nearbyWork)*3;
    }

    /** At the top of a tower, the Builder must have 14 squared blocks
     * of true placement reach, including the vertical component. */
    public static boolean withinReach(BlockPos base,BlockPos work){
        int dx=base.getX()-work.getX(),dz=base.getZ()-work.getZ();
        return dx*dx+dz*dz<=13
                &&work.getY()>=base.getY();
    }
}
