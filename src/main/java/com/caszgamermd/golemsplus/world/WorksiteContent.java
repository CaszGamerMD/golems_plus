package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.GolemsPlus;
import com.caszgamermd.golemsplus.worker.WorkerSpecialty;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import com.caszgamermd.golemsplus.trader.TraderGolem;
import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.beekeeper.BeeToolboxBlock;
import com.caszgamermd.golemsplus.beekeeper.BeeToolboxBlockEntity;
import com.caszgamermd.golemsplus.beekeeper.BeekeeperGolem;
import com.caszgamermd.golemsplus.cooking.ChefGolem;
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
    public static WorkerStationBlock BUILDER_TABLE, FISHING_STATION, TRADER_COUNTER, BEEKEEPER_STATION, COOKING_STATION;
    public static BeeToolboxBlock BEE_TOOLBOX;
    public static Item BUILDER_TABLE_ITEM, FISHING_STATION_ITEM, TRADER_COUNTER_ITEM, BEEKEEPER_STATION_ITEM, BEE_TOOLBOX_ITEM, COOKING_STATION_ITEM;
    public static BlockEntityType<BeeToolboxBlockEntity> BEE_TOOLBOX_ENTITY;
    public static BlockEntityType<WorkerStationBlockEntity> STATION_ENTITY;
    public static MenuType<WorkerStationMenu> STATION_MENU;
    public static EntityType<TraderGolem> TRADER_GOLEM;
    public static EntityType<BuilderGolem> BUILDER_GOLEM;
    public static EntityType<BeekeeperGolem> BEEKEEPER_GOLEM;
    public static EntityType<ChefGolem> CHEF_GOLEM;
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
        BEEKEEPER_STATION=station("beekeeper_station",WorkerSpecialty.BEEKEEPER);
        COOKING_STATION=station("cooking_station",WorkerSpecialty.COOKING);
        ResourceKey<net.minecraft.world.level.block.Block> toolboxKey=
                ResourceKey.create(Registries.BLOCK,GolemsPlus.id("beekeeper_toolbox"));
        BEE_TOOLBOX=Registry.register(BuiltInRegistries.BLOCK,toolboxKey,
                new BeeToolboxBlock(BlockBehaviour.Properties
                        .ofFullCopy(Blocks.BARREL).strength(2.5f).setId(toolboxKey)));
        BUILDER_TABLE_ITEM = blockItem("builder_table", BUILDER_TABLE);
        FISHING_STATION_ITEM = blockItem("fishing_station", FISHING_STATION);
        TRADER_COUNTER_ITEM = blockItem("trader_counter", TRADER_COUNTER);
        BEEKEEPER_STATION_ITEM=blockItem("beekeeper_station",BEEKEEPER_STATION);
        COOKING_STATION_ITEM=blockItem("cooking_station",COOKING_STATION);
        ResourceKey<Item> toolboxItemKey=ResourceKey.create(Registries.ITEM,
                GolemsPlus.id("beekeeper_toolbox"));
        BEE_TOOLBOX_ITEM=Registry.register(BuiltInRegistries.ITEM,toolboxItemKey,
                new BlockItem(BEE_TOOLBOX,new Item.Properties()
                        .setId(toolboxItemKey).useBlockDescriptionPrefix()));
        STATION_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                GolemsPlus.id("worker_station"),
                FabricBlockEntityTypeBuilder.create(WorkerStationBlockEntity::new,
                        BUILDER_TABLE, FISHING_STATION, TRADER_COUNTER,
                        BEEKEEPER_STATION,COOKING_STATION).build());
        BEE_TOOLBOX_ENTITY=Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                GolemsPlus.id("beekeeper_toolbox_entity"),
                FabricBlockEntityTypeBuilder.create(BeeToolboxBlockEntity::new,
                        BEE_TOOLBOX).build());
        STATION_MENU=Registry.register(BuiltInRegistries.MENU,GolemsPlus.id("worker_station_menu"),
                new ExtendedMenuType<>(
                        (menuId,inv,pos)->new WorkerStationMenu(menuId,inv,pos),
                        BlockPos.STREAM_CODEC));
        ResourceKey<EntityType<?>> traderKey=ResourceKey.create(Registries.ENTITY_TYPE,
                GolemsPlus.id("trader_golem"));
        TRADER_GOLEM=Registry.register(BuiltInRegistries.ENTITY_TYPE,traderKey,
                EntityType.Builder.<TraderGolem>of(TraderGolem::new,MobCategory.MISC)
                        .sized(.76f,1.62f).build(traderKey));
        FabricDefaultAttributeRegistry.register(TRADER_GOLEM,TraderGolem.attributes());
        ResourceKey<EntityType<?>> builderKey=ResourceKey.create(Registries.ENTITY_TYPE,
                GolemsPlus.id("builder_golem"));
        BUILDER_GOLEM=Registry.register(BuiltInRegistries.ENTITY_TYPE,builderKey,
                EntityType.Builder.<BuilderGolem>of(BuilderGolem::new,MobCategory.MISC)
                        .sized(.76f,1.62f).build(builderKey));
        FabricDefaultAttributeRegistry.register(BUILDER_GOLEM,BuilderGolem.attributes());
        ResourceKey<EntityType<?>> beekeeperKey=ResourceKey.create(Registries.ENTITY_TYPE,
                GolemsPlus.id("beekeeper_golem"));
        BEEKEEPER_GOLEM=Registry.register(BuiltInRegistries.ENTITY_TYPE,beekeeperKey,
                EntityType.Builder.<BeekeeperGolem>of(BeekeeperGolem::new,MobCategory.MISC)
                        .sized(.70f,1.45f).build(beekeeperKey));
        FabricDefaultAttributeRegistry.register(BEEKEEPER_GOLEM,
                BeekeeperGolem.attributes());
        ResourceKey<EntityType<?>> chefKey=ResourceKey.create(Registries.ENTITY_TYPE,
                GolemsPlus.id("chef_golem"));
        CHEF_GOLEM=Registry.register(BuiltInRegistries.ENTITY_TYPE,chefKey,
                EntityType.Builder.<ChefGolem>of(ChefGolem::new,MobCategory.MISC)
                        .sized(.76f,1.85f).build(chefKey));
        FabricDefaultAttributeRegistry.register(CHEF_GOLEM,ChefGolem.attributes());
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB,
                FabricCreativeModeTab.builder()
                        .title(Component.literal("Golems Plus"))
                        .icon(() -> new ItemStack(BUILDER_TABLE_ITEM))
                        .displayItems((parameters, output) -> {
                            output.accept(BUILDER_TABLE_ITEM);
                            output.accept(FISHING_STATION_ITEM);
                            output.accept(TRADER_COUNTER_ITEM);
                            output.accept(BEEKEEPER_STATION_ITEM);
                            output.accept(BEE_TOOLBOX_ITEM);
                            output.accept(COOKING_STATION_ITEM);
                        }).build());
    }
}
