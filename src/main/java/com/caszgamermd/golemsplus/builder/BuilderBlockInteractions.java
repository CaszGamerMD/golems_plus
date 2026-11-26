package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Manual settings are not item/block variants: a Builder must click the
 * already-placed block like a player, not fabricate the final state.
 *
 * Only reversible, stable manual interactions are supported here. Buttons
 * and pressure plates have momentary outputs; powered iron doors, piston
 * heads, flowing water, and bubble columns require WORLD PHYSICS instead.
 * Those must never be forced by this manual-action handler.
 */
public final class BuilderBlockInteractions {
    private BuilderBlockInteractions(){}

    /** Name of the setting changed by a right click, if supported. */
    static String manualProperty(BlockState state){
        if(state==null)return null;
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if(id.equals("lever"))return "powered";
        if(id.equals("repeater"))return "delay";
        if(id.equals("comparator"))return "mode";
        // Tuning a note block requires as many as 24 real right-clicks.
        // Its instrument comes from the block underneath, NOT the item.
        if(id.equals("note_block"))return "note";
        // The power signal tracks the sun; only the inverse-daylight
        // mode is a stable user-selected switch.
        if(id.equals("daylight_detector"))return "inverted";
        if(id.endsWith("_fence_gate"))
            return hasValue(state,"powered","true")?null:"open";
        // Iron trapdoors need a redstone signal, not a player's hand.
        // Copper and wood trapdoors are manually operable when unpowered.
        if(id.endsWith("_trapdoor")&&!id.equals("iron_trapdoor"))
            return hasValue(state,"powered","true")?null:"open";
        return null;
    }

    private static boolean hasValue(BlockState state,String key,String wanted){
        return state.getValues().anyMatch(value->
                value.property().getName().equals(key)
                &&value.valueName().equals(wanted));
    }

    private static String neutralValue(String property){
        return switch(property){
            case "powered","open" -> "false";
            case "delay" -> "1";
            case "mode" -> "compare";
            case "note" -> "0";
            case "inverted" -> "false";
            default -> throw new IllegalArgumentException(property);
        };
    }

    private static <T extends Comparable<T>> BlockState setValue(
            BlockState state,Property<T> property,String value){
        return property.getValue(value)
                .map(resolved->state.setValue(property,resolved)).orElse(state);
    }

    private static BlockState set(BlockState state,String name,String value){
        Property<?> property=state.getBlock().getStateDefinition().getProperty(name);
        return property==null?state:setValue(state,property,value);
    }

    /** A planned closed upper wooden trapdoor can support a pressure plate
     * even when adjacent redstone has opened it during construction.
     * Only offer this repair for the exact schematic host, never an iron
     * trapdoor, an intentional open trapdoor, or an unrelated block. */
    static boolean canRestorePlateSupport(ServerLevel level,BlockPos plate,
            BlockState desired,java.util.Map<BlockPos,BlockState> planned){
        if(!BuiltInRegistries.BLOCK.getKey(desired.getBlock()).getPath().endsWith("_pressure_plate")
                ||!level.hasChunkAt(plate.below()))return false;
        BlockPos at=plate.below();
        BlockState actual=level.getBlockState(at),expected=planned.get(at);
        if(expected==null||actual.getBlock()!=expected.getBlock())return false;
        String id=BuiltInRegistries.BLOCK.getKey(actual.getBlock()).getPath();
        if(!id.endsWith("_trapdoor")||id.equals("iron_trapdoor")
                ||!hasValue(actual,"open","true")||!hasValue(expected,"open","false")
                ||!hasValue(actual,"half","top")||!hasValue(expected,"half","top"))return false;
        return BuilderPlacementState.matches(set(actual,"open","false"),expected);
    }

    /** Use the actual wooden trapdoor's player interaction. Do not
     * synthesize power or bypass the subsequent survival check. */
    static boolean restorePlateSupport(ServerLevel level,BlockPos plate,
            BlockState desired,java.util.Map<BlockPos,BlockState> planned,ServerPlayer actor){
        if(desired.canSurvive(level,plate))return true;
        BlockPos at=plate.below();
        if(actor==null||!canRestorePlateSupport(level,plate,desired,planned)
                ||!level.mayInteract(actor,at)
                ||!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        new net.minecraft.world.phys.AABB(at)).isEmpty())return false;
        BlockState actual=level.getBlockState(at);
        actual.useWithoutItem(level,actor,new BlockHitResult(
                Vec3.atCenterOf(at),Direction.UP,at,false));
        boolean supported=desired.canSurvive(level,plate);
        if(supported)System.out.println("[BUILDER PLATE SUPPORT RESTORED] "+at);
        return supported;
    }

    /** Whether the desired target has a stable player-click control. */
    public static boolean hasManualControl(BlockState desired){
        return manualProperty(desired)!=null;
    }

    /** Delay only manually switched power, never the physical piston or
     * a powered block that serves as structural support. Other builds
     * retain immediate interaction semantics. */
    public static boolean delayPoweredLever(BlockState desired,java.util.List<BuilderConstructionPlan.Step> steps){
        if(desired==null||!desired.is(net.minecraft.world.level.block.Blocks.LEVER)
                ||!hasValue(desired,"powered","true"))return false;
        for(var step:steps)if(BuilderPistonWork.verification(step.encodedState()))return true;
        return false;
    }

    /** Shape/orientation/attachment stay exact; click-settings start in
     * their normal unactivated state, with real items paid separately. */
    public static BlockState placementState(BlockState desired){
        String id=BuiltInRegistries.BLOCK.getKey(desired.getBlock()).getPath();
        BlockState neutral=desired;
        // Live redstone energy is NOT a property a Builder can choose by
        // holding a powered item. Start with an unpowered wire/rail/device
        // and let real placed neighbors propagate signals afterward.
        if(id.equals("redstone_wire")){
            neutral=set(neutral,"power","0");
            // A placed wire starts as a cross unless the schematic is an
            // intentional dot. Starting every wire as a dot suppresses
            // horizontal power to nearby pistons. Native neighbor updates
            // determine the actual arms and signal strength.
            boolean dot=true;
            for(String side:new String[]{"north","south","east","west"})
                dot&=hasValue(desired,side,"none");
            for(String side:new String[]{"north","south","east","west"})
                neutral=set(neutral,side,dot?"none":"side");
        }else if(id.equals("note_block")){
            // Instrument and power are determined by real supporting
            // blocks and redstone. The note is subsequently tuned with
            // real clicks, not copied from a fake schematic instrument.
            neutral=desired.getBlock().defaultBlockState();
        }else if(id.equals("daylight_detector")){
            // Solar output is calculated on the actual world tick; only
            // the user's inverted switch is changed by right-click.
            neutral=set(neutral,"power","0");
        }else if(id.equals("observer")||id.equals("tripwire_hook")
                ||id.equals("tripwire")){
            neutral=set(neutral,"powered","false");
            neutral=set(neutral,"attached","false");
        }else if(id.equals("dispenser")||id.equals("dropper")){
            neutral=set(neutral,"triggered","false");
        }else if(id.equals("hopper")){
            neutral=set(neutral,"enabled","true");
        }else if(id.equals("target")){
            neutral=set(neutral,"power","0");
        }else if(id.equals("redstone_lamp")){
            neutral=set(neutral,"lit","false");
        }else if(id.equals("redstone_torch")
                ||id.equals("redstone_wall_torch")){
            neutral=set(neutral,"lit","true");
        }else if(id.equals("powered_rail")||id.equals("detector_rail")
                ||id.equals("activator_rail")||id.equals("repeater")
                ||id.equals("comparator")){
            neutral=set(neutral,"powered","false");
        }
        String key=manualProperty(desired);
        return key==null?neutral:set(neutral,key,neutralValue(key));
    }

    /** True only when clicking can fix the current same-block mismatch
     * without changing orientation, placement half, fluids or any other
     * non-interactive property. Never click a player's unrelated block. */
    public static boolean needsInteraction(BlockState current,
            BlockState desired){
        String key=manualProperty(desired);
        if(key==null||current==null||current.getBlock()!=desired.getBlock()
                ||BuilderPlacementState.matches(current,desired))return false;
        Property<?> property=current.getBlock().getStateDefinition().getProperty(key);
        if(property==null)return false;
        BlockState withCurrentSetting=set(desired,key,
                current.getValues().filter(value->value.property()==property)
                        .map(value->value.valueName()).findFirst().orElse(""));
        return BuilderPlacementState.matches(current,withCurrentSetting);
    }

    /** Up to four real block-use interactions in the same safe Builder
     * stance. Use the block's behavior (including sounds and redstone
     * neighbor updates), NOT setBlock(desired). If another component
     * changes the setting or the block disappears, defer and re-evaluate.
     */
    public static boolean interact(ServerLevel level,BlockPos pos,
            BlockState desired,ServerPlayer actor){
        if(actor==null)return false;
        BlockHitResult hit=new BlockHitResult(
                new Vec3(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5),
                Direction.UP,pos,false);
        int maxClicks=manualProperty(desired)!=null
                &&manualProperty(desired).equals("note")?25:4;
        for(int clicks=0;clicks<maxClicks;clicks++){
            BlockState actual=level.getBlockState(pos);
            if(BuilderPlacementState.matches(actual,desired))return true;
            if(!needsInteraction(actual,desired))return false;
            actual.useWithoutItem(level,actor,hit);
        }
        return BuilderPlacementState.matches(level.getBlockState(pos),desired);
    }
}
