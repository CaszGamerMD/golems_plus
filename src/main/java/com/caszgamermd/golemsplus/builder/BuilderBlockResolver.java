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
        if(id.equals("minecraft:water"))item=WorksiteContent.INFINITE_WATER_BUCKET;
        // Bubble columns have no item. A Builder supplies SOURCE WATER;
        // vanilla soul-sand/magma physics creates the requested column.
        if(id.equals("minecraft:bubble_column"))item=WorksiteContent.INFINITE_WATER_BUCKET;
        if(id.equals("minecraft:lava"))item=WorksiteContent.INFINITE_LAVA_BUCKET;
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
        // Chests, hoppers, dispensers, droppers, comparators, and
        // decorative heads are legitimate item-backed EntityBlocks.
        // Placing the real block creates an EMPTY block entity. The
        // Litematica importer deliberately does not restore tile-entity
        // NBT, and this does NOT fake stored inventories or text.
        // Reject only blocks with no obtainable placement item.
        if(item==Items.AIR)return null;
        // Door halves are represented by two successive schematic cells.
        // Lower block consumes the single physical door item; upper half
        // is placed after its lower support, without duplicating materials.
        // Redstone dust and beds are real inventory items. Wiring
        // power and connectivity are neighbor-derived (already handled
        // in BuilderPlacementState); both halves of a bed are physical
        // blocks but its item is consumed only for the foot.
        // Fire/portals/crops remain generated or special-use results.
        if(id.endsWith("_tall_flower")
                ||id.endsWith("_double_plant")||id.endsWith("_crop")
                ||id.contains("fire")||id.contains("portal"))
            return null;
        // A normal/sticky piston is an ordinary one-item block while
        // retracted. Its extended state and detached moving/head blocks
        // are REDSTONE RESULTS and cannot be placed as inventory blocks.
        if(id.equals("minecraft:piston_head")
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
        // Flowing WATER has a readable expected world state but is not a
        // bucket placement: BuilderConstructionPlan assigns no item and
        // BuilderBuildService waits for actual fluid simulation. Lava
        // still requires explicit support before non-source states can
        // enter the construction plan. Never set a flowing block directly.
        if(id.equals("minecraft:lava")
                &&state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL)
                &&state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL)!=0)
            return null;
        if((id.equals("minecraft:piston")||id.equals("minecraft:sticky_piston"))
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
                ||"golems_plus:infinite_lava_bucket".equals(itemId);
    }
    private static <T extends Comparable<T>> BlockState readProperty(
            BlockState state,Property<T> property,String text){
        return property.getValue(text).map(value->state.setValue(property,value)).orElse(null);
    }
}
