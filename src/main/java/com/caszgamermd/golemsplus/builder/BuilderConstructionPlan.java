package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable verified plan from a file and exact placed Litematica anchor. */
public final class BuilderConstructionPlan {
    public record Step(int x,int y,int z,String encodedState,String requiredItem,
            boolean ignorePlant){
        public Step(int x,int y,int z,String encodedState,String requiredItem){
            this(x,y,z,encodedState,requiredItem,false);
        }
    }
    public record Result(List<Step> steps,Map<String,Long> required,
                         Set<String> unsupported){
        public Result{
            steps=List.copyOf(steps);required=Map.copyOf(required);
            unsupported=Set.copyOf(unsupported);
        }
    }
    private BuilderConstructionPlan(){}
    public static Result create(LitematicFileReader.Schematic schematic,BuildSite site){
        return create(schematic,site,false);
    }
    public static Result create(LitematicFileReader.Schematic schematic,
            BuildSite site,boolean ignorePlants){
        if(schematic.blocks().size()>100_000)
            throw new IllegalArgumentException("At most 100,000 blocks per build in this version");
        List<Step> steps=new ArrayList<>();
        Map<String,Long> quantities=new LinkedHashMap<>();
        java.util.Set<String> unsupported=new java.util.LinkedHashSet<>();
        // Low-to-high, with a SECOND water phase on each Y. Even another
        // Builder's water source must wait until every dry cell is finished.
        var blocks=new ArrayList<>(schematic.blocks());
        blocks.sort(java.util.Comparator.comparingInt(LitematicFileReader.Block::y));
        for(var b:blocks){
            boolean ignored=ignorePlants&&BuilderVegetation.ignoreSchematicState(b.state());
            boolean waterlogged=!ignored&&BuilderWaterPass.isWaterlogged(b.state());
            String dry=waterlogged?BuilderWaterPass.dryState(b.state()):b.state();
            var resolved=ignored?null:
                    BuilderBlockResolver.resolve(dry,site.rotation(),site.mirror());
            if(waterlogged&&BuilderBlockResolver.resolve(b.state(),
                    site.rotation(),site.mirror())==null)resolved=null;
            var world=site.locate(new BuilderMaterialPlan.Placement(
                    b.x(),b.y(),b.z(),b.state(),resolved==null?null:
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resolved.item()).toString()));
            if(world.y()<-64||world.y()>511)
                throw new IllegalArgumentException("Placement exceeds supported world height");
            if(resolved==null&&!ignored)unsupported.add(b.state());
            // A door consumes ONE item for its lower half. Both door
            // states are present in Litematica, so counting the upper
            // half would request and spend twice the correct materials.
            boolean upperDoor=b.state().contains("_door[")
                    &&b.state().contains("half=upper");
            // Both red-bed parts exist as distinct schematic cells, but
            // one actual bed item builds the two-part assembly. Only the
            // foot pays for the physical item; never double-charge it.
            boolean headBed=b.state().contains("_bed[")
                    &&b.state().contains("part=head");
            // Flowing water is an expected PHYSICAL result of sources
            // supplied elsewhere. Keep its exact cell in the blueprint,
            // but demand neither a bucket nor an impossible paid block.
            boolean naturalFlow=BuilderWaterPass.isNaturalFlowingWater(b.state());
            String item=resolved==null||upperDoor||headBed||naturalFlow||ignored?null:
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resolved.item()).toString();
            // Retain the original coordinate index for existing saved job
            // cursors and disjoint ten-position work orders. Ignored plants
            // require no placement, no tools and no warehouse materials.
            steps.add(new Step(world.x(),world.y(),world.z(),dry,item,ignored));
            if(item!=null)quantities.merge(item,1L,Long::sum);
            if(waterlogged&&resolved!=null){
                steps.add(new Step(world.x(),world.y(),world.z(),
                        BuilderWaterPass.waterlog(b.state()),BuilderWaterPass.WATER_BUCKET));
                quantities.merge(BuilderWaterPass.WATER_BUCKET,1L,Long::sum);
            }
        }
        steps.sort(java.util.Comparator.comparingInt(Step::y)
                .thenComparingInt(s->BuilderWaterPass.layerOrder(s.encodedState())));
        if(steps.size()>100_000)
            throw new IllegalArgumentException("At most 100,000 construction steps per build");
        for(String id:java.util.List.copyOf(quantities.keySet()))
            if(BuilderBlockResolver.reusableSupply(id))quantities.put(id,1L);
        return new Result(steps,quantities,unsupported);
    }
}
