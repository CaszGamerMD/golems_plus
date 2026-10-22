package com.caszgamermd.golemsplus.cooking;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Renders the actual meal on a physical white plate on the service counter. */
public final class MealCounterRenderer implements BlockEntityRenderer<MealCounterBlockEntity,MealCounterRenderer.State>{
    public static final class State extends BlockEntityRenderState{
        public final ItemStackRenderState item=new ItemStackRenderState();
    }
    private final ItemModelResolver resolver;
    public MealCounterRenderer(BlockEntityRendererProvider.Context context){
        resolver=context.itemModelResolver();
    }
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(MealCounterBlockEntity be,State state,float partial,
            Vec3 camera,ModelFeatureRenderer.@Nullable CrumblingOverlay overlay){
        BlockEntityRenderer.super.extractRenderState(be,state,partial,camera,overlay);
        resolver.updateForTopItem(state.item,be.food(),ItemDisplayContext.FIXED,
                be.getLevel(),null,(int)be.getBlockPos().asLong());
    }
    @Override public void submit(State state,PoseStack pose,SubmitNodeCollector collector,
            CameraRenderState camera){
        if(state.item.isEmpty())return;
        pose.pushPose();
        pose.translate(.5,.84,.5);
        pose.scale(.46f,.46f,.46f);
        state.item.submit(pose,collector,state.lightCoords,OverlayTexture.NO_OVERLAY,0);
        pose.popPose();
    }
}
