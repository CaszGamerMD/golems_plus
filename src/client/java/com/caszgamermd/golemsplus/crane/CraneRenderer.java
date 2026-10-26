package com.caszgamermd.golemsplus.crane;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.*;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Minecraft block materials, a telescopic boom, cabin and visible interchangeable basket. */
public final class CraneRenderer extends EntityRenderer<BuildingCrane,CraneRenderer.State> {
    public static final class State extends EntityRenderState {
        Vec3 anchor=Vec3.ZERO;boolean supply,box;
        final ItemStackRenderState yellow=new ItemStackRenderState(),black=new ItemStackRenderState(),glass=new ItemStackRenderState(),iron=new ItemStackRenderState(),wood=new ItemStackRenderState();
    }
    private static Item blockItem(String path){
        // Minecraft 26.2 no longer exposes every colored block as a
        // constant in Blocks/Items; resolve known vanilla block IDs.
        return BuiltInRegistries.BLOCK.getValue(
                Identifier.tryParse("minecraft:"+path)).asItem();
    }
    private final ItemModelResolver resolver;
    public CraneRenderer(EntityRendererProvider.Context context){super(context);resolver=context.getItemModelResolver();}
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(BuildingCrane crane,State s,float partial){
        super.extractRenderState(crane,s,partial);s.anchor=crane.anchorPoint().subtract(crane.position());s.supply=crane.supplyMode();s.box=crane.hasBox();
        ItemStackRenderState[] states={s.yellow,s.black,s.glass,s.iron,s.wood};
        Item[] items={blockItem("yellow_concrete"),blockItem("black_concrete"),blockItem("cyan_stained_glass"),Blocks.IRON_BLOCK.asItem(),Blocks.OAK_PLANKS.asItem()};
        for(int i=0;i<items.length;i++)resolver.updateForTopItem(states[i],new ItemStack(items[i]),ItemDisplayContext.NONE,crane.level(),null,crane.getId());
    }
    private static void box(ItemStackRenderState material,State s,PoseStack pose,SubmitNodeCollector collector,double x,double y,double z,float w,float h,float d){
        pose.pushPose();pose.translate(x,y,z);pose.scale(w,h,d);material.submit(pose,collector,s.lightCoords,OverlayTexture.NO_OVERLAY,0);pose.popPose();
    }
    @Override public void submit(State s,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){
        super.submit(s,pose,collector,camera);
        Vec3 a=s.anchor;
        // Chassis: exactly 2 × 2 × 2 Minecraft blocks. The dark solid
        // cubic shell, lower yellow hazard band and narrow glass windows
        // all stay within the same physical 2-block bounds.
        box(s.black,s,pose,collector,a.x,a.y+1,a.z,2f,2f,2f);
        box(s.yellow,s,pose,collector,a.x,a.y+.25,a.z,1.92f,.38f,1.92f);
        box(s.yellow,s,pose,collector,a.x,a.y+1.88,a.z,1.92f,.2f,1.92f);
        box(s.glass,s,pose,collector,a.x,a.y+1.27,a.z+.947,1.54f,.72f,.075f);
        box(s.glass,s,pose,collector,a.x,a.y+1.27,a.z-.947,1.54f,.72f,.075f);
        box(s.glass,s,pose,collector,a.x+.947,a.y+1.27,a.z,.075f,.72f,1.54f);
        box(s.glass,s,pose,collector,a.x-.947,a.y+1.27,a.z,.075f,.72f,1.54f);
        Vec3 root=a.add(0,2.05,0),tip=new Vec3(0,2.8,0),delta=tip.subtract(root);double length=delta.length();
        if(length>.01){
            Vec3 mid=root.lerp(tip,.5),unit=delta.normalize();pose.pushPose();pose.translate(mid.x,mid.y,mid.z);
            pose.mulPose(new Quaternionf().rotationTo(0,1,0,(float)unit.x,(float)unit.y,(float)unit.z));
            pose.scale(.23f,(float)length,.23f);s.yellow.submit(pose,collector,s.lightCoords,OverlayTexture.NO_OVERLAY,0);pose.popPose();
        }
        if(!s.box)return;
        float width=s.supply?4f:2.2f;
        box(s.yellow,s,pose,collector,0,.1,0,width,.2f,s.supply?2.2f:2.2f);
        for(int x:new int[]{-1,1})for(int z:new int[]{-1,1})
            box(s.iron,s,pose,collector,x*(width/2-.08),1.3,z, .12f,2.4f,.12f);
        box(s.yellow,s,pose,collector,0,2.5,0,width,.14f,2.2f);
        if(s.supply){
            for(int x:new int[]{-1,1}){
                box(s.wood,s,pose,collector,x,.4,0,1.8f,.35f,1.8f);
                box(s.black,s,pose,collector,x,1.1,0,1.5f,1f,1.5f);
            }
        }else{
            for(int side:new int[]{-1,1}){
                box(s.iron,s,pose,collector,side,1,0,.12f,.12f,2);
                box(s.iron,s,pose,collector,0,1,side,2,.12f,.12f);
            }
        }
    }
    @Override protected boolean affectedByCulling(BuildingCrane crane){return false;}
}
