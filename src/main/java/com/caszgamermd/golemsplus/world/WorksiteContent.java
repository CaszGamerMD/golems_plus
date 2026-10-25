package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.GolemsPlus;
import com.caszgamermd.golemsplus.worker.WorkerSpecialty;
import com.caszgamermd.golemsplus.worker.WorkerGolemItem;
import com.caszgamermd.golemsplus.cooking.MealCounterBlock;
import com.caszgamermd.golemsplus.cooking.CookingKioskTopBlock;
import com.caszgamermd.golemsplus.cooking.MealCounterBlockEntity;
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
import com.caszgamermd.golemsplus.fishing.FisherGolem;
import com.caszgamermd.golemsplus.fishing.FishingSpotBlock;
import com.caszgamermd.golemsplus.fishing.FishingSpotMarkerItem;
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
    public static UnemployedCenterBlock UNEMPLOYED_CENTER;
    public static MealCounterBlock MEAL_COUNTER;
    public static CookingKioskTopBlock COOKING_KIOSK_TOP;
    public static Item MEAL_COUNTER_ITEM;
    public static BlockEntityType<MealCounterBlockEntity> MEAL_COUNTER_ENTITY;
    public static Item BUILDER_TABLE_ITEM, FISHING_STATION_ITEM, TRADER_COUNTER_ITEM, BEEKEEPER_STATION_ITEM, BEE_TOOLBOX_ITEM, COOKING_STATION_ITEM;
    public static BlockEntityType<BeeToolboxBlockEntity> BEE_TOOLBOX_ENTITY;
    public static BlockEntityType<UnemployedCenterBlockEntity> UNEMPLOYED_CENTER_ENTITY;
    public static Item UNEMPLOYED_CENTER_ITEM;
    public static BlockEntityType<WorkerStationBlockEntity> STATION_ENTITY;
    public static MenuType<WorkerStationMenu> STATION_MENU;
    public static EntityType<TraderGolem> TRADER_GOLEM;
    public static EntityType<BuilderGolem> BUILDER_GOLEM;
    public static EntityType<BeekeeperGolem> BEEKEEPER_GOLEM;
    public static EntityType<ChefGolem> CHEF_GOLEM;
    public static EntityType<FisherGolem> FISHER_GOLEM;
    public static Item FISHING_BAIT,FISHING_MARKER_ITEM;
    public static FishingSpotBlock FISHING_SPOT;
    public static Item BUILDER_WORKER_ITEM, TRADER_WORKER_ITEM, BEEKEEPER_WORKER_ITEM, CHEF_WORKER_ITEM, FISHER_WORKER_ITEM;
    private static final ResourceKey<CreativeModeTab> TAB = ResourceKey.create(
            BuiltInRegistries.CREATIVE_MODE_TAB.key(), GolemsPlus.id("main"));

    private WorksiteContent() {}

    private static WorkerStationBlock station(String name, WorkerSpecialty specialty) {
        ResourceKey<net.minecraft.world.level.block.Block> key =
                ResourceKey.create(Registries.BLOCK, GolemsPlus.id(name));
        BlockBehaviour.Properties properties=BlockBehaviour.Properties
                .ofFullCopy(Blocks.CRAFTING_TABLE).strength(2.5F)
                .noOcclusion().setId(key);
        // The banker lamp on the trading desk is a real constant light
        // source; other workstations remain unlit.
        if(specialty==WorkerSpecialty.TRADER)
            properties.lightLevel(state->12);
        return Registry.register(BuiltInRegistries.BLOCK,key,
                new WorkerStationBlock(specialty,properties));
    }

    private static Item blockItem(String name, WorkerStationBlock block) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, GolemsPlus.id(name));
        return Registry.register(BuiltInRegistries.ITEM, key,
                new BlockItem(block, new Item.Properties()
                        .setId(key).useBlockDescriptionPrefix()));
    }

    private static Item golemItem(String name,WorkerSpecialty profession){
        ResourceKey<Item> key=ResourceKey.create(Registries.ITEM,GolemsPlus.id(name));
        return Registry.register(BuiltInRegistries.ITEM,key,
                new WorkerGolemItem(profession,new Item.Properties().setId(key)));
    }

    public static void initialize() {
        BUILDER_TABLE = station("builder_table", WorkerSpecialty.BUILDER);
        FISHING_STATION = station("fishing_station", WorkerSpecialty.FISHING);
        TRADER_COUNTER = station("trader_counter", WorkerSpecialty.TRADER);
        BEEKEEPER_STATION=station("beekeeper_station",WorkerSpecialty.BEEKEEPER);
        ResourceKey<net.minecraft.world.level.block.Block> flagKey=
                ResourceKey.create(Registries.BLOCK,GolemsPlus.id("fishing_spot"));
        FISHING_SPOT=Registry.register(BuiltInRegistries.BLOCK,flagKey,
                new FishingSpotBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_FENCE)
                        .noCollision().noOcclusion().strength(.4f).setId(flagKey)));
        COOKING_STATION=station("cooking_station",WorkerSpecialty.COOKING);
        ResourceKey<net.minecraft.world.level.block.Block> upperKioskKey=
                ResourceKey.create(Registries.BLOCK,GolemsPlus.id("cooking_kiosk_top"));
        COOKING_KIOSK_TOP=Registry.register(BuiltInRegistries.BLOCK,upperKioskKey,
                new CookingKioskTopBlock(BlockBehaviour.Properties
                        .ofFullCopy(Blocks.IRON_BLOCK).strength(2.5f).noOcclusion()
                        .setId(upperKioskKey)));
        ResourceKey<net.minecraft.world.level.block.Block> plateKey=
                ResourceKey.create(Registries.BLOCK,GolemsPlus.id("meal_counter"));
        MEAL_COUNTER=Registry.register(BuiltInRegistries.BLOCK,plateKey,
                new MealCounterBlock(BlockBehaviour.Properties
                        .ofFullCopy(Blocks.QUARTZ_BLOCK).strength(2.0f).noOcclusion().setId(plateKey)));
        ResourceKey<net.minecraft.world.level.block.Block> toolboxKey=
                ResourceKey.create(Registries.BLOCK,GolemsPlus.id("beekeeper_toolbox"));
        BEE_TOOLBOX=Registry.register(BuiltInRegistries.BLOCK,toolboxKey,
                new BeeToolboxBlock(BlockBehaviour.Properties
                        .ofFullCopy(Blocks.BARREL).strength(2.5f).noOcclusion().setId(toolboxKey)));
        ResourceKey<net.minecraft.world.level.block.Block> unemployedBlockKey=
                ResourceKey.create(Registries.BLOCK,GolemsPlus.id("unemployed_center"));
        UNEMPLOYED_CENTER=Registry.register(BuiltInRegistries.BLOCK,
                unemployedBlockKey,new UnemployedCenterBlock(BlockBehaviour.Properties
                        .ofFullCopy(Blocks.BARREL).strength(2.5f)
                        .setId(unemployedBlockKey)));
        ResourceKey<Item> unemployedItemKey=ResourceKey.create(Registries.ITEM,
                GolemsPlus.id("unemployed_center"));
        UNEMPLOYED_CENTER_ITEM=Registry.register(BuiltInRegistries.ITEM,
                unemployedItemKey,new BlockItem(UNEMPLOYED_CENTER,
                        new Item.Properties().setId(unemployedItemKey)
                                .useBlockDescriptionPrefix()));
        BUILDER_TABLE_ITEM = blockItem("builder_table", BUILDER_TABLE);
        FISHING_STATION_ITEM = blockItem("fishing_station", FISHING_STATION);
        ResourceKey<Item> baitKey=ResourceKey.create(Registries.ITEM,GolemsPlus.id("fishing_bait"));
        FISHING_BAIT=Registry.register(BuiltInRegistries.ITEM,baitKey,
                new Item(new Item.Properties().setId(baitKey)));
        ResourceKey<Item> markerKey=ResourceKey.create(Registries.ITEM,
                GolemsPlus.id("fishing_spot_marker"));
        FISHING_MARKER_ITEM=Registry.register(BuiltInRegistries.ITEM,markerKey,
                new FishingSpotMarkerItem(new Item.Properties()
                        .stacksTo(1).setId(markerKey)));
        TRADER_COUNTER_ITEM = blockItem("trader_counter", TRADER_COUNTER);
        BEEKEEPER_STATION_ITEM=blockItem("beekeeper_station",BEEKEEPER_STATION);
        COOKING_STATION_ITEM=blockItem("cooking_station",COOKING_STATION);
        ResourceKey<Item> counterItemKey=ResourceKey.create(Registries.ITEM,
                GolemsPlus.id("meal_counter"));
        MEAL_COUNTER_ITEM=Registry.register(BuiltInRegistries.ITEM,counterItemKey,
                new BlockItem(MEAL_COUNTER,new Item.Properties()
                        .setId(counterItemKey).useBlockDescriptionPrefix()));
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
        MEAL_COUNTER_ENTITY=Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                GolemsPlus.id("meal_counter_entity"),
                FabricBlockEntityTypeBuilder.create(MealCounterBlockEntity::new,
                        MEAL_COUNTER).build());
        BEE_TOOLBOX_ENTITY=Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                GolemsPlus.id("beekeeper_toolbox_entity"),
                FabricBlockEntityTypeBuilder.create(BeeToolboxBlockEntity::new,
                        BEE_TOOLBOX).build());
        UNEMPLOYED_CENTER_ENTITY=Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                GolemsPlus.id("unemployed_center_entity"),
                FabricBlockEntityTypeBuilder.create(UnemployedCenterBlockEntity::new,
                        UNEMPLOYED_CENTER).build());
        STATION_MENU=Registry.register(BuiltInRegistries.MENU,GolemsPlus.id("worker_station_menu"),
                new ExtendedMenuType<>(
                        (menuId,inv,pos)->new WorkerStationMenu(menuId,inv,pos),
                        BlockPos.STREAM_CODEC));
        ResourceKey<EntityType<?>> traderKey=ResourceKey.create(Registries.ENTITY_TYPE,
                GolemsPlus.id("trader_golem"));
        TRADER_GOLEM=Registry.register(BuiltInRegistries.ENTITY_TYPE,traderKey,
                EntityType.Builder.<TraderGolem>of(TraderGolem::new,MobCategory.MISC)
                        .sized(.85f,1.62f).build(traderKey));
        FabricDefaultAttributeRegistry.register(TRADER_GOLEM,TraderGolem.attributes());
        ResourceKey<EntityType<?>> builderKey=ResourceKey.create(Registries.ENTITY_TYPE,
                GolemsPlus.id("builder_golem"));
        BUILDER_GOLEM=Registry.register(BuiltInRegistries.ENTITY_TYPE,builderKey,
                EntityType.Builder.<BuilderGolem>of(BuilderGolem::new,MobCategory.MISC)
                        .sized(.65f,1.62f).build(builderKey));
        FabricDefaultAttributeRegistry.register(BUILDER_GOLEM,BuilderGolem.attributes());
        ResourceKey<EntityType<?>> beekeeperKey=ResourceKey.create(Registries.ENTITY_TYPE,
                GolemsPlus.id("beekeeper_golem"));
        BEEKEEPER_GOLEM=Registry.register(BuiltInRegistries.ENTITY_TYPE,beekeeperKey,
                EntityType.Builder.<BeekeeperGolem>of(BeekeeperGolem::new,MobCategory.MISC)
                        .sized(.85f,1.45f).build(beekeeperKey));
        FabricDefaultAttributeRegistry.register(BEEKEEPER_GOLEM,
                BeekeeperGolem.attributes());
        ResourceKey<EntityType<?>> chefKey=ResourceKey.create(Registries.ENTITY_TYPE,
                GolemsPlus.id("chef_golem"));
        CHEF_GOLEM=Registry.register(BuiltInRegistries.ENTITY_TYPE,chefKey,
                EntityType.Builder.<ChefGolem>of(ChefGolem::new,MobCategory.MISC)
                        .sized(.85f,1.85f).build(chefKey));
        FabricDefaultAttributeRegistry.register(CHEF_GOLEM,ChefGolem.attributes());
        ResourceKey<EntityType<?>> fisherKey=ResourceKey.create(Registries.ENTITY_TYPE,
                GolemsPlus.id("fisher_golem"));
        FISHER_GOLEM=Registry.register(BuiltInRegistries.ENTITY_TYPE,fisherKey,
                EntityType.Builder.<FisherGolem>of(FisherGolem::new,MobCategory.MISC)
                        .sized(.85f,1.60f).build(fisherKey));
        FabricDefaultAttributeRegistry.register(FISHER_GOLEM,FisherGolem.attributes());
        FISHER_WORKER_ITEM=golemItem("fisher_worker",WorkerSpecialty.FISHING);
        BUILDER_WORKER_ITEM=golemItem("builder_worker",WorkerSpecialty.BUILDER);
        TRADER_WORKER_ITEM=golemItem("trader_worker",WorkerSpecialty.TRADER);
        BEEKEEPER_WORKER_ITEM=golemItem("beekeeper_worker",WorkerSpecialty.BEEKEEPER);
        CHEF_WORKER_ITEM=golemItem("chef_worker",WorkerSpecialty.COOKING);
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB,
                FabricCreativeModeTab.builder()
                        .title(Component.literal("Golems Plus"))
                        .icon(() -> new ItemStack(BUILDER_TABLE_ITEM))
                        .displayItems((parameters, output) -> {
                            output.accept(BUILDER_TABLE_ITEM);
                            output.accept(UNEMPLOYED_CENTER_ITEM);
                            output.accept(FISHING_STATION_ITEM);
                            output.accept(TRADER_COUNTER_ITEM);
                            output.accept(BEEKEEPER_STATION_ITEM);
                            // Legacy toolbox is kept registered for existing worlds,
                            // but the bag workstation replaces it in new kitchens.
                            output.accept(FISHING_MARKER_ITEM);
                            output.accept(FISHING_BAIT);
                            output.accept(COOKING_STATION_ITEM);
                            output.accept(MEAL_COUNTER_ITEM);
                            output.accept(BUILDER_WORKER_ITEM);
                            output.accept(TRADER_WORKER_ITEM);
                            output.accept(BEEKEEPER_WORKER_ITEM);
                            output.accept(CHEF_WORKER_ITEM);
                            output.accept(FISHER_WORKER_ITEM);
                        }).build());
    }
}

