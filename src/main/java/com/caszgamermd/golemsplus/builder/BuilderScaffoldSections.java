package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Two perpendicular X/Y and Z/Y sections of a planned build.
 *
 * A dome's outline is not a vertical wall: high courses can project farther
 * than low courses, leaving a ledge above a Builder's tower. Each section
 * records those projecting cells and looks for real scaffold-floor positions
 * outside the planned solid silhouette. Both slices are considered so a
 * catwalk may approach a corner from either direction rather than repeatedly
 * choosing an obstructed straight-up column.
 *
 * This is planning geometry ONLY. The bridge controller still validates
 * loaded chunks, actual world collision, ownership, access permissions,
 * grounded posts, real item consumption and physical worker motion.
 */
public final class BuilderScaffoldSections {
    private BuilderScaffoldSections(){}

    public enum Axis { X, Z }

    public record Cell(BlockPos block,boolean projectsOverLowerCourse){}
    public record Section(Axis axis,int fixed,List<Cell> cells){
        public Section { cells=List.copyOf(cells); }
        public long overhangCount(){
            return cells.stream().filter(Cell::projectsOverLowerCourse).count();
        }
    }
    public record Station(BlockPos scaffold,int workCoverage,
                          boolean xSection,boolean zSection,int rimScore){}

    public static Set<BlockPos> solids(List<BuilderConstructionPlan.Step> steps){
        Set<BlockPos> solid=new HashSet<>();
        for(var step:steps)
            if(!step.ignorePlant())
                solid.add(new BlockPos(step.x(),step.y(),step.z()));
        return solid;
    }

    /** Every solid schematic cell on the chosen vertical plane.
     * "Projects" means that this course extends over an empty planned
     * cell directly below it, the silhouette shown in dome cross-sections.
     */
    public static Section slice(Set<BlockPos> planned,Axis axis,int fixed){
        List<Cell> cells=new ArrayList<>();
        for(BlockPos at:planned){
            if((axis==Axis.X?at.getZ():at.getX())!=fixed)continue;
            cells.add(new Cell(at,!planned.contains(at.below())));
        }
        cells.sort(Comparator.comparingInt((Cell c)->c.block().getY())
                .thenComparingInt(c->axis==Axis.X
                        ?c.block().getX():c.block().getZ()));
        return new Section(axis,fixed,cells);
    }

    /** Plan scaffold FLOOR cells, from which the Builder can safely stand
     * one block higher and place nearby schematic cells. Resolve work zones
     * using BOTH perpendicular sections; also sample the two neighboring
     * planes so an exposed rim can be approached around a corner.
     *
     * No candidate may replace an intended solid block or put a Builder's
     * feet/head inside the finished model. This is intentionally stricter
     * than inspecting only the current world's air blocks.
     */
    public static List<Station> stations(Set<BlockPos> planned,
            BlockPos target,int scaffoldY){
        if(Math.abs(scaffoldY+1-target.getY())>1)return List.of();
        List<Station> stations=new ArrayList<>();
        Set<BlockPos> seen=new HashSet<>();
        for(Axis axis:Axis.values())
            for(int other:new int[]{0,1,-1,2,-2})
                for(int along=-4;along<=4;along++){
                    int x=axis==Axis.X?target.getX()+along
                            :target.getX()+other;
                    int z=axis==Axis.Z?target.getZ()+along
                            :target.getZ()+other;
                    BlockPos floor=new BlockPos(x,scaffoldY,z);
                    int dx=x-target.getX(),dz=z-target.getZ();
                    int vertical=scaffoldY+1-target.getY();
                    if(dx*dx+dz*dz+vertical*vertical>12
                            ||(dx==0&&dz==0)||!seen.add(floor)
                            ||planned.contains(floor)
                            ||planned.contains(floor.above())
                            ||planned.contains(floor.above().above()))continue;
                    int coverage=0;
                    for(BlockPos block:planned){
                        int dX=block.getX()-x,dZ=block.getZ()-z;
                        int dY=block.getY()-(scaffoldY+1);
                        if(dX*dX+dZ*dZ+dY*dY<=12)coverage++;
                    }
                    // The preferred staging side is OUTSIDE the edge of
                    // the wall at this course. An overhanging lip is
                    // detected by comparing the two consecutive profiles.
                    int rim=0;
                    if(axis==Axis.X){
                        boolean wall=planned.contains(new BlockPos(
                                target.getX(),target.getY(),z));
                        boolean under=planned.contains(new BlockPos(
                                target.getX(),target.getY()-1,z));
                        if(wall&&!under)rim+=3;
                    }else{
                        boolean wall=planned.contains(new BlockPos(
                                x,target.getY(),target.getZ()));
                        boolean under=planned.contains(new BlockPos(
                                x,target.getY()-1,target.getZ()));
                        if(wall&&!under)rim+=3;
                    }
                    boolean xSlice=Math.abs(z-target.getZ())<=2;
                    boolean zSlice=Math.abs(x-target.getX())<=2;
                    stations.add(new Station(floor,coverage,
                            xSlice,zSlice,rim));
                }
        stations.sort(Comparator.comparingInt(Station::rimScore).reversed()
                .thenComparing(Comparator.comparingInt(
                        Station::workCoverage).reversed())
                .thenComparingInt(s->s.scaffold().distManhattan(target)));
        return List.copyOf(stations);
    }
}
