package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.Block;

/** Classifies non-solid vegetation ONLY for the optional schematic
 * placement filter. Never apply this to the site-clearance target set:
 * flowers, crops and snow obstructing the construction site are still
 * physically removed using the normal authorized clearing process.
 *
 * Keep solid building materials such as leaves, logs, hay, moss blocks,
 * melons, pumpkins and mushroom blocks buildable.
 */
public final class BuilderVegetation {
    private BuilderVegetation(){}

    public static boolean ignoreSchematicState(String encoded){
        if(encoded==null||encoded.isBlank())return false;
        String name=encoded.split("\\[",2)[0];
        Identifier id=Identifier.tryParse(name);
        if(id==null||!BuiltInRegistries.BLOCK.containsKey(id))return false;
        Block block=BuiltInRegistries.BLOCK.getValue(id);
        if(block instanceof BushBlock)return true;
        String path=id.getPath();
        return path.equals("azalea")||path.equals("flowering_azalea")
                ||path.endsWith("_tulip")||path.equals("poppy")
                ||path.equals("dandelion")||path.equals("blue_orchid")
                ||path.equals("allium")||path.equals("azure_bluet")
                ||path.equals("oxeye_daisy")||path.equals("cornflower")
                ||path.equals("lily_of_the_valley")||path.equals("wither_rose")
                ||path.equals("torchflower")||path.equals("open_eyeblossom")
                ||path.equals("closed_eyeblossom")
                ||path.equals("wheat")||path.equals("carrots")
                ||path.equals("potatoes")||path.equals("beetroots")
                ||path.equals("nether_wart")||path.equals("cocoa")
                ||path.equals("sugar_cane")||path.equals("bamboo")
                ||path.equals("bamboo_sapling")||path.equals("kelp")
                ||path.equals("kelp_plant")||path.equals("seagrass")
                ||path.equals("tall_seagrass")||path.equals("vine")
                ||path.equals("cave_vines")||path.equals("cave_vines_plant")
                ||path.equals("twisting_vines")||path.equals("weeping_vines")
                ||path.equals("lily_pad")||path.equals("short_grass")
                ||path.equals("tall_grass")||path.equals("fern")
                ||path.equals("large_fern")||path.equals("peony")
                ||path.equals("rose_bush")||path.equals("lilac")
                ||path.equals("sunflower")||path.equals("pitcher_crop")
                ||path.equals("torchflower_crop")
                // Low-profile vegetation also blocks or changes schematic
                // placement, even when its block class is not BushBlock.
                // Preserve solid leaves, logs, moss blocks and other actual
                // building materials.
                ||path.equals("moss_carpet")
                ||path.equals("pale_moss_carpet")
                ||path.equals("hanging_roots")
                ||path.equals("glow_lichen")
                ||path.equals("spore_blossom")
                ||path.equals("small_dripleaf")
                ||path.equals("big_dripleaf")
                ||path.equals("big_dripleaf_stem")
                ||path.equals("sea_pickle")
                ||path.equals("nether_sprouts")
                ||path.equals("crimson_roots")
                ||path.equals("warped_roots")
                ||path.equals("dead_bush")
                ||path.equals("short_dry_grass")
                ||path.equals("tall_dry_grass")
                ||path.equals("leaf_litter")
                ||path.endsWith("_crop")||path.endsWith("_flower")
                ||path.endsWith("_flower_bush")||path.endsWith("_flowerbed")
                ||path.endsWith("_sapling")||path.endsWith("_sprouts")
                ||path.endsWith("_petals")||path.endsWith("_roots")
                ||path.endsWith("_vines")||path.endsWith("_coral_fan")
                ||path.endsWith("_coral_plant")||path.startsWith("potted_");
    }
}
