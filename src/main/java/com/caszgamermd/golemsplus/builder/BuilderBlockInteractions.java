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

    /** Whether the desired target has a stable player-click control. */
    public static boolean hasManualControl(BlockState desired){
        return manualProperty(desired)!=null;
    }

    /** Shape/orientation/attachment stay exact; click-settings start in
     * their normal unactivated state, with real items paid separately. */
    public static BlockState placementState(BlockState desired){
        String key=manualProperty(desired);
        return key==null?desired:set(desired,key,neutralValue(key));
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
        for(int clicks=0;clicks<4;clicks++){
            BlockState actual=level.getBlockState(pos);
            if(BuilderPlacementState.matches(actual,desired))return true;
            if(!needsInteraction(actual,desired))return false;
            actual.useWithoutItem(level,actor,hit);
        }
        return BuilderPlacementState.matches(level.getBlockState(pos),desired);
    }
}
