package com.caszgamermd.golemsplus.worker;

import java.util.List;

/**
 * Exactly thirty possible personal names per specialist profession.
 * Names are chosen once when a golem is created and the chosen name is
 * saved through Minecraft's standard custom-name entity data.
 */
public final class ProfessionGolemNamePool {
    private ProfessionGolemNamePool(){}

    public enum Profession {
        BUILDER, CHEF, FISHER, BEEKEEPER, TRADER
    }

    private static final List<String> BUILDERS=List.of(
            "Brickley", "Mortar Mike", "Trowel Tina", "Scaffold Sam",
            "Blueprint Bea", "Mason Max", "Nailton", "Level Larry",
            "Plumb Bob", "Blockson", "Beam Benny", "Sawyer",
            "Rivet Rosie", "Grout Gus", "Cobble Carl", "Brickette",
            "Pillar Pete", "Dovetail Dot", "Roofus", "Timber Tim",
            "Chisel Chip", "Morticia", "Hammer Hank", "Quarry Quinn",
            "Dusty Doug", "Martha Mortar", "Stone Stan", "Joist Joy",
            "Plank Pat", "Archie Arch"
    );
    private static final List<String> CHEFS=List.of(
            "Basil", "Saffron", "Pepper Pete", "Skillet Sid",
            "Biscuit Bea", "Soupy Sue", "Gordon Golem", "Whiskers",
            "Roux", "Sizzle", "Pancake Pat", "Miso",
            "Cinnamon", "Tater Tot", "Noodle Ned", "Gravy Grace",
            "Dumpling Dot", "Chef Chive", "Parsley", "Butters",
            "Crouton Carl", "Fondue Fran", "Waffle Will", "Custard",
            "Rosemary", "Toasty", "Oregano", "Truffle",
            "Apron Annie", "Sprinkle"
    );
    private static final List<String> FISHERS=List.of(
            "Gillbert", "Reel Rita", "Captain Cod", "Baitley",
            "Finley", "Bobber Bob", "Trout Trevor", "Hook Hank",
            "Tackle Tina", "Marlin", "Minnow Molly", "Salty Sam",
            "Anchovy", "Pike Pete", "Wader Wade", "Carp Carl",
            "Splash", "Perch Percy", "Fisher Finn", "Nibbles",
            "Castor", "Tuna Tony", "Guppy Gus", "Ripple",
            "Bass Betty", "Chum Charlie", "Snapper", "Lure Lou",
            "Driftwood", "Kipper"
    );
    private static final List<String> BEEKEEPERS=List.of(
            "Buzzby", "Honey Hank", "Beatrice", "Nectar Ned",
            "Pollen Polly", "Hive Harold", "Waxy Wendy", "Stinger",
            "Clover", "Bumble Bert", "Daisy", "Beeatrice",
            "Golden Gus", "Queenie", "Petal Pete", "Amber",
            "Blossom", "Zippy", "Lavender Lou", "Meadow Mae",
            "Honeycomb", "Dandelion Dan", "Buzzy Bella", "Poppy",
            "Apiary Annie", "Thistle", "Sunny Sue", "Waxwell",
            "Marigold", "Sweetpea"
    );
    private static final List<String> TRADERS=List.of(
            "Barter Bart", "Coin Connie", "Merchant Mel", "Penny",
            "Goldie", "Dealbert", "Ledger Larry", "Emerald Ed",
            "Bargain Bea", "Tally Tina", "Ducat", "Trinket",
            "Silvers", "Profit Pete", "Haggler Hank", "Minty",
            "Quill Quinn", "Market Max", "Copper Carl", "Dime",
            "Cashmere", "Velvet Vic", "Stamp Stan", "Nickel Ned",
            "Invoice Ivy", "Swap Sam", "Shilling", "Receipt Rita",
            "Jasper", "Purse Percy"
    );

    public static List<String> names(Profession profession){
        return switch(profession){
            case BUILDER -> BUILDERS;
            case CHEF -> CHEFS;
            case FISHER -> FISHERS;
            case BEEKEEPER -> BEEKEEPERS;
            case TRADER -> TRADERS;
        };
    }

    public static String select(Profession profession,int index){
        return names(profession).get(index);
    }

    public static String legacyName(Profession profession){
        return switch(profession){
            case BUILDER -> "Builder Golem";
            case CHEF -> "Chef Golem";
            case FISHER -> "Fisher Golem";
            case BEEKEEPER -> "Beekeeper Golem";
            case TRADER -> "Trader Golem";
        };
    }
}
