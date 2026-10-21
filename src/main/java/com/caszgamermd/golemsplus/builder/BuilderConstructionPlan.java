package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable verified plan from a file and exact placed Litematica anchor. */
public final class BuilderConstructionPlan {
    public record Step(int x,int y,int z,String encodedState,String requiredItem){}
    public record Result(List<Step> steps,Map<String,Long> required,
                         Set<String> unsupported){
        public Result{
            steps=List.copyOf(steps);required=Map.copyOf(required);
            unsupported=Set.copyOf(unsupported);
        }
    }
    private BuilderConstructionPlan(){}
    public static Result create(LitematicFileReader.Schematic schematic,BuildSite site){
        if(schematic.blocks().size()>100_000)
            throw new IllegalArgumentException("At most 100,000 blocks per build in this version");
        List<Step> steps=new ArrayList<>();
        Map<String,Long> quantities=new LinkedHashMap<>();
        java.util.Set<String> unsupported=new java.util.LinkedHashSet<>();
        // Sort low-to-high to reduce unsupported floating placements. Physics
        // is validated again in-world before any actual material is spent.
        var blocks=new ArrayList<>(schematic.blocks());
        blocks.sort(java.util.Comparator.comparingInt(LitematicFileReader.Block::y));
        for(var b:blocks){
            var resolved=BuilderBlockResolver.resolve(b.state(),site.rotation(),site.mirror());
            var world=site.locate(new BuilderMaterialPlan.Placement(
                    b.x(),b.y(),b.z(),b.state(),resolved==null?null:
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resolved.item()).toString()));
            if(world.y()<-64||world.y()>511)
                throw new IllegalArgumentException("Placement exceeds supported world height");
            if(resolved==null)unsupported.add(b.state());
            String item=resolved==null?null:
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resolved.item()).toString();
            steps.add(new Step(world.x(),world.y(),world.z(),b.state(),item));
            if(item!=null)quantities.merge(item,1L,Long::sum);
        }
        return new Result(steps,quantities,unsupported);
    }
}
