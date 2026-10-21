package com.caszgamermd.golemsplus.builder;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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
        int open=encoded.indexOf('[');
        String id=open<0?encoded:encoded.substring(0,open);
        Identifier identifier=Identifier.tryParse(id);
        if(identifier==null||!BuiltInRegistries.BLOCK.containsKey(identifier))return null;
        Block block=BuiltInRegistries.BLOCK.getValue(identifier);
        Item item=block.asItem();
        if(item==Items.AIR || block instanceof EntityBlock)return null;
        if(id.endsWith("_door")||id.endsWith("_bed")||id.endsWith("_tall_flower")
                ||id.endsWith("_double_plant")||id.endsWith("_crop")
                ||id.equals("minecraft:water")||id.equals("minecraft:lava")
                ||id.contains("redstone_wire")||id.contains("piston")
                ||id.contains("fire")||id.contains("portal"))
            return null;
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
        if(state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                &&state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))
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
    private static <T extends Comparable<T>> BlockState readProperty(
            BlockState state,Property<T> property,String text){
        return property.getValue(text).map(value->state.setValue(property,value)).orElse(null);
    }
}
