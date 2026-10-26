package com.caszgamermd.golemsplus.breakroom;
import com.caszgamermd.golemsplus.GolemsPlus;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
public final class BreakRoomContent {
 public static Block CHAIR,TABLE,SNACK_MACHINE,ARCADE;
 private static Block register(String id){
  var key=ResourceKey.create(Registries.BLOCK,GolemsPlus.id(id));
  var block=Registry.register(BuiltInRegistries.BLOCK,key,new BreakRoomBlock(Block.Properties.ofFullCopy(Blocks.OAK_PLANKS).strength(2).noOcclusion().setId(key),id));
  var itemKey=ResourceKey.create(Registries.ITEM,GolemsPlus.id(id));
  Registry.register(BuiltInRegistries.ITEM,itemKey,new BlockItem(block,new Item.Properties().setId(itemKey)));
  return block;
 }
 public static boolean furniture(Block block){return block instanceof BreakRoomBlock;}
 public static void initialize(){
  CHAIR=register("break_room_chair");TABLE=register("break_room_table");SNACK_MACHINE=register("golem_snack_machine");ARCADE=register("golem_arcade");
  CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,GolemsPlus.id("main"))).register(out->{for(var block:new Block[]{CHAIR,TABLE,SNACK_MACHINE,ARCADE})out.accept(block);});
  GolemBreakService.initialize();
 }
}
