package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Reconciles multi-region Litematica blocks with real game item IDs. */
public final class BuilderSchematicAnalysis {
    public record Result(List<BuildSite.WorldPlacement> positions,Map<String,Long> materials,
                         Set<String> unsupported, int blockCount){
        public Result {
            positions=List.copyOf(positions);
            materials=Map.copyOf(materials);
            unsupported=Set.copyOf(unsupported);
        }
        public boolean ready(){return unsupported.isEmpty();}
    }
    private BuilderSchematicAnalysis(){}
    public static Result analyze(LitematicFileReader.Schematic schem,BuildSite site,
                                  Function<String,String> blockToItem){
        List<BuildSite.WorldPlacement> placements=new ArrayList<>();
        Map<String,Long> requirements=new HashMap<>();
        java.util.Set<String> unresolved=new java.util.HashSet<>();
        for(var block:schem.blocks()){
            String name=block.state().split("\\[",2)[0];
            String item=blockToItem.apply(name);
            placements.add(site.locate(new BuilderMaterialPlan.Placement(
                    block.x(),block.y(),block.z(),block.state(),item)));
            if(item==null||item.isBlank())unresolved.add(block.state());
            else requirements.merge(item,1L,Long::sum);
        }
        return new Result(placements,requirements,unresolved,placements.size());
    }
}
