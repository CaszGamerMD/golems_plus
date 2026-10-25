package com.caszgamermd.golemsplus.builder;

import java.util.List;

/**
 * Rectangular, non-overlapping X/Z partitions for active Builder Golems.
 * A crew of four normally gets a 2x2 grid. Prime crew sizes use strips.
 * Layout is based on the whole schematic footprint, so completed blocks
 * never move zone boundaries while a job is running.
 */
public final class BuilderZones {
    private BuilderZones(){}

    public record Layout(int minX,int maxX,int minZ,int maxZ) {
        public int columns(int crew){
            int bestX=1;
            double best=Double.POSITIVE_INFINITY;
            long width=(long)maxX-minX+1,depth=(long)maxZ-minZ+1;
            for(int x=1;x<=crew;x++){
                if(crew%x!=0)continue;
                int z=crew/x;
                // Preserve contiguous rectangular zones even for extremely
                // narrow builds; avoid needlessly creating empty strips.
                double penalty=Math.abs(Math.log(
                        ((double)width/x)/((double)depth/z)));
                if(x>width||z>depth)penalty+=100;
                if(penalty<best){best=penalty;bestX=x;}
            }
            return bestX;
        }
        public int zone(int x,int z,int crew){
            if(crew<=1)return 0;
            int columns=columns(crew),rows=crew/columns;
            long width=(long)maxX-minX+1,depth=(long)maxZ-minZ+1;
            int xi=(int)Math.clamp(((long)x-minX)*columns/width,0,columns-1);
            int zi=(int)Math.clamp(((long)z-minZ)*rows/depth,0,rows-1);
            return zi*columns+xi;
        }
        public boolean contains(BuilderConstructionPlan.Step step,
                                int workerZone,int crew){
            return zone(step.x(),step.z(),crew)==workerZone;
        }
    }
    public static Layout footprint(List<BuilderConstructionPlan.Step> steps){
        if(steps.isEmpty())return new Layout(0,0,0,0);
        int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE;
        int minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
        for(var step:steps){
            minX=Math.min(minX,step.x());maxX=Math.max(maxX,step.x());
            minZ=Math.min(minZ,step.z());maxZ=Math.max(maxZ,step.z());
        }
        return new Layout(minX,maxX,minZ,maxZ);
    }
}
