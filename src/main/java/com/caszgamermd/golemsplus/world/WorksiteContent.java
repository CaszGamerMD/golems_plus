package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.GolemsPlus;
import com.caszgamermd.golemsplus.worker.WorkerSpecialty;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class WorksiteContent {
    public static WorkerStationBlock BUILDER_TABLE, FISHING_STATION, TRADER_COUNTER;
    public static Item BUILDER_TABLE_ITEM, FISHING_STATION_ITEM, TRADER_COUNTER_ITEM;
    public static BlockEntityType<WorkerStationBlockEntity> STATION_ENTITY;
    private static final ResourceKey<CreativeModeTab> TAB = ResourceKey.create(
            BuiltInRegistries.CREATIVE_MODE_TAB.key(), GolemsPlus.id("main"));

    private WorksiteContent() {}

    private static WorkerStationBlock station(String name, WorkerSpecialty specialty) {
        ResourceKey<net.minecraft.world.level.block.Block> key =
                ResourceKey.create(Registries.BLOCK, GolemsPlus.id(name));
        return Registry.register(BuiltInRegistries.BLOCK, key,
                new WorkerStationBlock(specialty, BlockBehaviour.Properties
                        .ofFullCopy(Blocks.CRAFTING_TABLE).strength(2.5F).setId(key)));
    }

    private static Item blockItem(String name, WorkerStationBlock block) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, GolemsPlus.id(name));
        return Registry.register(BuiltInRegistries.ITEM, key,
                new BlockItem(block, new Item.Properties()
                        .setId(key).useBlockDescriptionPrefix()));
    }

    public static void initialize() {
        BUILDER_TABLE = station("builder_table", WorkerSpecialty.BUILDER);
        FISHING_STATION = station("fishing_station", WorkerSpecialty.FISHING);
        TRADER_COUNTER = station("trader_counter", WorkerSpecialty.TRADER);
        BUILDER_TABLE_ITEM = blockItem("builder_table", BUILDER_TABLE);
        FISHING_STATION_ITEM = blockItem("fishing_station", FISHING_STATION);
        TRADER_COUNTER_ITEM = blockItem("trader_counter", TRADER_COUNTER);
        STATION_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                GolemsPlus.id("worker_station"),
                FabricBlockEntityTypeBuilder.create(WorkerStationBlockEntity::new,
                        BUILDER_TABLE, FISHING_STATION, TRADER_COUNTER).build());
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB,
                FabricCreativeModeTab.builder()
                        .title(Component.literal("Golems Plus"))
                        .icon(() -> new ItemStack(BUILDER_TABLE_ITEM))
                        .displayItems((parameters, output) -> {
                            output.accept(BUILDER_TABLE_ITEM);
                            output.accept(FISHING_STATION_ITEM);
                            output.accept(TRADER_COUNTER_ITEM);
                        }).build());
    }
}
