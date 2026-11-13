package com.caszgamermd.golemsplus.builder;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Real block-state parser; transforms orientation along with coordinates.
 * Only safe single-block inventory-backed states are eligible for placement.
 */
public final class BuilderBlockResolver {
    private BuilderBlockResolver(){}
    public record Resolved(BlockState state,Item item){}
    public static Resolved resolve(String encoded,BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        return resolveInternal(encoded,rotation,mirror,false);
    }
    /** Read-only schematic comparison. Derived piston states have no item placement. */
    public static net.minecraft.world.level.block.state.BlockState expectedState(
            String encoded,BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        var resolved=resolveInternal(encoded,rotation,mirror,true);
        return resolved==null?null:resolved.state();
    }
    private static Resolved resolveInternal(String encoded,BuildSite.Rotation rotation,
            BuildSite.Mirror mirror,boolean expectedOnly){
        if(encoded==null||encoded.isBlank())return null;
        if(BuilderWaterPass.isWaterlogPass(encoded)){
            String original=BuilderWaterPass.originalState(encoded);
            if(!BuilderWaterPass.isWaterlogged(original))return null;
            var wet=resolve(original,rotation,mirror);
            return wet==null?null:new Resolved(wet.state(),WorksiteContent.INFINITE_WATER_BUCKET);
        }
        int open=encoded.indexOf('[');
        String id=open<0?encoded:encoded.substring(0,open);
        Identifier identifier=Identifier.tryParse(id);
        if(identifier==null||!BuiltInRegistries.BLOCK.containsKey(identifier))return null;
        Block block=BuiltInRegistries.BLOCK.getValue(identifier);
        Item item=block.asItem();
        // Redstone wire is placed by the REDSTONE dust item, not by a
        // conventional BlockItem. Its power and side connections are
        // world-derived and already compared dynamically by
        // BuilderPlacementState; support and physical item cost still apply.
        if(id.equals("minecraft:redstone_wire"))item=Items.REDSTONE;
        if(id.equals("minecraft:water"))item=WorksiteContent.INFINITE_WATER_BUCKET;
        // Bubble columns have no item. A Builder supplies SOURCE WATER;
        // vanilla soul-sand/magma physics creates the requested column.
        if(id.equals("minecraft:bubble_column"))item=WorksiteContent.INFINITE_WATER_BUCKET;
        if(id.equals("minecraft:lava"))item=WorksiteContent.INFINITE_LAVA_BUCKET;
        // Soul fire is an ignition action with an actual reusable tool.
        if(id.equals("minecraft:soul_fire"))item=Items.FLINT_AND_STEEL;
        // Wall signs have standing-sign inventory items, and can be
        // placed as blank physical signs. The test does not fake text NBT.
        boolean sign=id.endsWith("_sign")||id.endsWith("_hanging_sign");
        if(id.endsWith("_wall_sign")||id.endsWith("_wall_hanging_sign")){
            String itemName=id.replace("_wall_hanging_sign","_hanging_sign")
                    .replace("_wall_sign","_sign");
            Identifier itemId=Identifier.tryParse(itemName);
            if(itemId==null||!BuiltInRegistries.ITEM.containsKey(itemId))
                return null;
            item=BuiltInRegistries.ITEM.getValue(itemId);
        }
        // Vanilla comparators have a small runtime block entity, but
        // their orientation and subtraction mode are ordinary paid-item
        // construction plus a native right-click. Never exclude them
        // alongside inventory/NBT-bearing containers and machines.
        boolean vanillaComparator=id.equals("minecraft:comparator");
        // Vanilla daylight detectors store no player inventory or custom
        // schematic NBT. One paid detector item is sufficient; toggling
        // inverted mode is a separate right-click Builder action.
        boolean vanillaDaylightDetector=id.equals("minecraft:daylight_detector");
        // A vanilla bed's block entity contains no external inventory or
        // custom source NBT. It is one ordinary bed item, with the head
        // created and validated as part of the same physical placement.
        boolean vanillaBed=block instanceof BedBlock;
        // Empty vanilla containers are paid blocks; never import inventory NBT.
        boolean vanillaContainer=id.equals("minecraft:chest")||id.equals("minecraft:trapped_chest")
                ||id.equals("minecraft:hopper")||id.equals("minecraft:dispenser")
                ||id.equals("minecraft:dropper");
        if((item==Items.AIR && !(expectedOnly&&id.equals("minecraft:piston_head")))
                || block instanceof EntityBlock
                &&!sign&&!vanillaComparator&&!vanillaDaylightDetector
                &&!vanillaBed&&!vanillaContainer)return null;
        // The constructor groups each valid double-plant, door and bed
        // pair into one claimed item-backed job. Neither companion is a
        // stand-alone material, and crops/fire/portals still require
        // their own future world-interaction handlers.
        if(id.endsWith("_crop")
                ||id.contains("fire")&&!id.equals("minecraft:soul_fire")||id.contains("portal"))
            return null;
        // A normal/sticky piston is an ordinary one-item block while
        // retracted. Its extended state and detached moving/head blocks
        // are REDSTONE RESULTS and cannot be placed as inventory blocks.
        if(id.equals("minecraft:piston_head")&&!expectedOnly
                ||id.equals("minecraft:moving_piston"))return null;
        BlockState state=block.defaultBlockState();
        if(open>=0){
            if(!encoded.endsWith("]"))return null;
            String properties=encoded.substring(open+1,encoded.length()-1);
            if(!properties.isEmpty())
                for(String pair:properties.split(",")){
                    String[] split=pair.split("=",2);
                    if(split.length!=2)return null;
                    Property<?> property=block.getStateDefinition().getProperty(split[0]);
                    if(property==null)return null;
                    state=readProperty(state,property,split[1]);
                    if(state==null)return null;
                }
        }
        // Only source fluids are schematic placements. Flowing levels must
        // emerge from vanilla physics, not be issued as infinite bucket jobs.
        if(!expectedOnly&&(id.equals("minecraft:water")||id.equals("minecraft:lava"))
                &&state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL)
                &&state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL)!=0)
            return null;
        if(!expectedOnly&&(id.equals("minecraft:piston")||id.equals("minecraft:sticky_piston"))
                &&state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EXTENDED))
            return null;
        Mirror m=switch(mirror){
            case NONE->Mirror.NONE;
            case FLIP_X->Mirror.FRONT_BACK;
            case FLIP_Z->Mirror.LEFT_RIGHT;
        };
        Rotation r=switch(rotation){
            case NONE->Rotation.NONE;
            case CLOCKWISE_90->Rotation.CLOCKWISE_90;
            case CLOCKWISE_180->Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90->Rotation.COUNTERCLOCKWISE_90;
        };
        return new Resolved(state.mirror(m).rotate(r),item);
    }
    public static boolean reusableSupply(String itemId){
        return "golems_plus:infinite_water_bucket".equals(itemId)
                ||"golems_plus:infinite_lava_bucket".equals(itemId)
                ||"minecraft:flint_and_steel".equals(itemId);
    }
    private static <T extends Comparable<T>> BlockState readProperty(
            BlockState state,Property<T> property,String text){
        return property.getValue(text).map(value->state.setValue(property,value)).orElse(null);
    }
}
