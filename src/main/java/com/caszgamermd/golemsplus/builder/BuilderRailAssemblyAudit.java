package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only physical viability audit of an imported .litematic.
 *
 * An ascending rail points to its raised end at (dx,+1,dz).
 * A removable raised rail jig requires sturdy support at (dx,0,dz).
 * If that footing is itself a rail and the raised cell is a non-rail,
 * the exact saved final geometry has a conflict. The physical Matrix
 * GameTest confirmed that temporarily replacing the footing with stone
 * and later restoring the final rail BREAKS the incline.
 *
 * Do not silently edit, remove, auto-complete, or rewrite source cells.
 */
public final class BuilderRailAssemblyAudit {
    public record Position(int x,int y,int z){
        Position offset(int dx,int dy,int dz){
            return new Position(x+dx,y+dy,z+dz);
        }
    }
    public record Report(int ascending,int conflictingRaisedEndpoints,
            int upperNonRail,int railFootings,List<String> examples){
        public Report { examples=List.copyOf(examples); }
        public boolean hasConflicts(){return conflictingRaisedEndpoints>0;}
    }
    private BuilderRailAssemblyAudit(){}

    public static Report inspect(LitematicFileReader.Schematic schematic){
        Map<Position,String> source=new HashMap<>();
        for(var block:schematic.blocks())
            source.put(new Position(block.x(),block.y(),block.z()),
                    block.state());
        int total=0,nonRailUpper=0,railFootings=0,conflicts=0;
        List<String> examples=new ArrayList<>();
        for(var entry:source.entrySet()){
            String state=entry.getValue();
            if(!isRail(state)||!state.contains("shape=ascending_"))continue;
            int[] delta=ascendingOffset(state);
            if(delta==null)continue;
            total++;
            Position pos=entry.getKey();
            Position upper=pos.offset(delta[0],1,delta[1]);
            Position base=pos.offset(delta[0],0,delta[1]);
            String savedUpper=source.getOrDefault(upper,"minecraft:air");
            String savedFooting=source.getOrDefault(base,"minecraft:air");
            boolean upperIsNotRail=!isRail(savedUpper);
            boolean footingIsRail=isRail(savedFooting);
            if(upperIsNotRail)nonRailUpper++;
            if(footingIsRail)railFootings++;
            if(upperIsNotRail&&footingIsRail){
                conflicts++;
                if(examples.size()<16)examples.add(
                        "rail="+pos+" expected="+state
                        +" raisedEnd="+upper+" finalUpper="+savedUpper
                        +" raisedFooting="+base+" finalFooting="+savedFooting);
            }
        }
        return new Report(total,conflicts,nonRailUpper,railFootings,examples);
    }
    private static int[] ascendingOffset(String state){
        if(state.contains("shape=ascending_west"))return new int[]{-1,0};
        if(state.contains("shape=ascending_east"))return new int[]{1,0};
        if(state.contains("shape=ascending_north"))return new int[]{0,-1};
        if(state.contains("shape=ascending_south"))return new int[]{0,1};
        return null;
    }
    private static boolean isRail(String state){
        if(state==null)return false;
        String id=state.split("\\[",2)[0];
        return id.equals("minecraft:rail")
                ||id.equals("minecraft:powered_rail")
                ||id.equals("minecraft:detector_rail")
                ||id.equals("minecraft:activator_rail");
    }
}
