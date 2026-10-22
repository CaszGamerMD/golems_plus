package com.caszgamermd.golemsplus.cooking;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Visual-only food-of-the-day display on the 2-high order kiosk screen.
 * No item is generated or granted; the display rotates every 15 seconds.
 */
public final class CookingKioskRenderer implements
        BlockEntityRenderer<WorkerStationBlockEntity,CookingKioskRenderer.State>{
    public static final class State extends BlockEntityRenderState{
        public final ItemStackRenderState featured=new ItemStackRenderState();
    }
    private static final net.minecraft.world.item.Item[] FEATURES={
        Items.COOKED_BEEF,Items.PUMPKIN_PIE,Items.BREAD,Items.COOKED_SALMON,
        Items.COOKIE,Items.CAKE,Items.BAKED_POTATO,Items.GOLDEN_CARROT
    };
    private final ItemModelResolver resolver;
    public CookingKioskRenderer(BlockEntityRendererProvider.Context context){
        resolver=context.itemModelResolver();
    }
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(WorkerStationBlockEntity block,State state,
            float partial,Vec3 camera,ModelFeatureRenderer.@Nullable CrumblingOverlay overlay){
        BlockEntityRenderer.super.extractRenderState(block,state,partial,camera,overlay);
        int index=block.getLevel()==null?0:(int)((block.getLevel().getGameTime()/300)
                %FEATURES.length);
        resolver.updateForTopItem(state.featured,block.isCooking()
                        ?new ItemStack(FEATURES[index]):ItemStack.EMPTY,
                ItemDisplayContext.FIXED,block.getLevel(),null,
                (int)block.getBlockPos().asLong());
    }
    @Override public void submit(State state,PoseStack pose,SubmitNodeCollector collector,
            CameraRenderState camera){
        if(state.featured.isEmpty())return;
        pose.pushPose();
        pose.translate(.50,1.43,.12);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.scale(.43f,.43f,.43f);
        state.featured.submit(pose,collector,state.lightCoords,OverlayTexture.NO_OVERLAY,0);
        pose.popPose();
    }
}
