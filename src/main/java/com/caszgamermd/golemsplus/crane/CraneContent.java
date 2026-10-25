package com.caszgamermd.golemsplus.crane;

import com.caszgamermd.golemsplus.GolemsPlus;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;

public final class CraneContent {
    public static EntityType<BuildingCrane> CRANE;
    public static Block RAIL;
    public static Item CHASSIS,BOOM,BUILDER_BOX,SUPPLY_BOX,CONTROLLER,RAIL_ITEM;
    private static Item part(String name){var key=ResourceKey.create(Registries.ITEM,GolemsPlus.id(name));
        return Registry.register(BuiltInRegistries.ITEM,key,new Item(new Item.Properties().setId(key)));}
    public static void initialize(){
        var entityKey=ResourceKey.create(Registries.ENTITY_TYPE,GolemsPlus.id("building_crane"));
        CRANE=Registry.register(BuiltInRegistries.ENTITY_TYPE,entityKey,
                EntityType.Builder.<BuildingCrane>of(BuildingCrane::new,MobCategory.MISC)
                        .sized(2.2f,2.5f).clientTrackingRange(128).updateInterval(1).build(entityKey));
        var railKey=ResourceKey.create(Registries.BLOCK,GolemsPlus.id("crane_rail"));
        RAIL=Registry.register(BuiltInRegistries.BLOCK,railKey,new CraneRailBlock(
                Block.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion().strength(4).setId(railKey)));
        var itemKey=ResourceKey.create(Registries.ITEM,GolemsPlus.id("crane_rail"));
        RAIL_ITEM=Registry.register(BuiltInRegistries.ITEM,itemKey,new BlockItem(RAIL,new Item.Properties().setId(itemKey)));
        var chassisKey=ResourceKey.create(Registries.ITEM,GolemsPlus.id("crane_chassis"));
        CHASSIS=Registry.register(BuiltInRegistries.ITEM,chassisKey,new CraneControlItem(true,new Item.Properties().setId(chassisKey).stacksTo(1)));
        var controlKey=ResourceKey.create(Registries.ITEM,GolemsPlus.id("crane_controller"));
        CONTROLLER=Registry.register(BuiltInRegistries.ITEM,controlKey,new CraneControlItem(false,new Item.Properties().setId(controlKey).stacksTo(1)));
        BOOM=part("crane_boom_extension");BUILDER_BOX=part("crane_builder_box");SUPPLY_BOX=part("crane_supply_box");
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,GolemsPlus.id("main")))
                .register(output->{for(Item i:new Item[]{CHASSIS,BOOM,BUILDER_BOX,SUPPLY_BOX,CONTROLLER,RAIL_ITEM})output.accept(i);});
        CraneService.initialize();
    }
}
