package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;

/** Per-job material substitutions and vertical scope. Only unbuilt cells
 * consume the resulting required items; source schematic files never change.
 */
public final class BuilderMaterialRules {
    public static final String IGNORE="!";
    public static final int ALL=0,FROM_Y_UP=1,FROM_Y_DOWN=2;
    public static final int WINDOW_LAYERS=8;
    private BuilderMaterialRules(){}

    public static boolean includesY(int worldY,int startY,int mode){
        return mode==ALL||(mode==FROM_Y_UP&&worldY>=startY)
                ||(mode==FROM_Y_DOWN&&worldY<=startY);
    }
    public static boolean isBuildableReplacement(String id){
        Identifier key=Identifier.tryParse(id);
        if(key==null||!BuiltInRegistries.ITEM.containsKey(key))return false;
        Item item=BuiltInRegistries.ITEM.getValue(key);
        return item instanceof BlockItem
                ||id.equals("golems_plus:infinite_water_bucket")
                ||id.equals("golems_plus:infinite_lava_bucket");
    }
    public static String originalItem(String encoded,BuildSite.Rotation rotation,
            BuildSite.Mirror mirror){
        var original=BuilderBlockResolver.resolve(encoded,rotation,mirror);
        return original==null?null:
                BuiltInRegistries.ITEM.getKey(original.item()).toString();
    }
    public static boolean ignoredMaterial(String encoded,Map<String,String> rules,
            BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        String id=originalItem(encoded,rotation,mirror);
        return id!=null&&IGNORE.equals(rules.get(id));
    }
    private static String replacementBlockId(String itemId){
        if(itemId.equals("golems_plus:infinite_water_bucket"))return "minecraft:water";
        if(itemId.equals("golems_plus:infinite_lava_bucket"))return "minecraft:lava";
        Identifier key=Identifier.tryParse(itemId);
        if(key==null||!BuiltInRegistries.ITEM.containsKey(key))return null;
        Item item=BuiltInRegistries.ITEM.getValue(key);
        if(!(item instanceof BlockItem blockItem))return null;
        Block block=blockItem.getBlock();
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
    /** Carry across shared block-state properties (e.g. stair facing/half)
     * while allowing a cube replacement to discard inapplicable properties.
     */
    private static String rewrittenState(String original,String replacement){
        String blockId=replacementBlockId(replacement);
        if(blockId==null)return null;
        Identifier key=Identifier.tryParse(blockId);
        if(key==null)return null;
        Block block=BuiltInRegistries.BLOCK.getValue(key);
        int open=original.indexOf('[');
        if(open<0||!original.endsWith("]"))return blockId;
        List<String> compatible=new ArrayList<>();
        for(String pair:original.substring(open+1,original.length()-1).split(",")){
            String[] pieces=pair.split("=",2);
            if(pieces.length!=2)continue;
            Property<?> property=block.getStateDefinition().getProperty(pieces[0]);
            if(property!=null&&property.getValue(pieces[1]).isPresent())
                compatible.add(pair);
        }
        return compatible.isEmpty()?blockId:blockId+"["+String.join(",",compatible)+"]";
    }
    public static List<BuilderConstructionPlan.Step> apply(
            List<BuilderConstructionPlan.Step> source,Map<String,String> rules,
            int startY,int mode,BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        if(rules.isEmpty()&&mode==ALL)return source;
        List<BuilderConstructionPlan.Step> result=new ArrayList<>(source.size());
        for(var step:source){
            boolean outside=!includesY(step.y(),startY,mode);
            String original=rules.isEmpty()?null:
                    originalItem(step.encodedState(),rotation,mirror);
            String rule=original==null?null:rules.get(original);
            boolean ignored=step.ignorePlant()||outside||IGNORE.equals(rule);
            String state=step.encodedState();
            String item=step.requiredItem();
            if(!ignored&&rule!=null&&!rule.isBlank()){
                String rewritten=rewrittenState(state,rule);
                if(rewritten!=null){
                    var resolved=BuilderBlockResolver.resolve(rewritten,rotation,mirror);
                    if(resolved!=null){
                        state=rewritten;
                        if(item!=null)
                            item=BuiltInRegistries.ITEM.getKey(resolved.item()).toString();
                    }
                }
            }
            result.add(new BuilderConstructionPlan.Step(step.x(),step.y(),
                    step.z(),state,ignored?null:item,ignored));
        }
        // Any downward-only job starts at its uppermost included layer.
        // Settings cannot reorder a running job's saved index space.
        if(mode==FROM_Y_DOWN)
            result.sort(Comparator.comparingInt(
                    BuilderConstructionPlan.Step::y).reversed());
        return List.copyOf(result);
    }
}
