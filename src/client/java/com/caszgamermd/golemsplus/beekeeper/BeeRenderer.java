package com.caszgamermd.golemsplus.beekeeper;

import com.caszgamermd.golemsplus.GolemsPlus;
import com.caszgamermd.golemsplus.breakroom.GolemBreakService;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/** Small worker with a purpose-built beekeeper suit UV texture. */
public final class BeeRenderer
        extends MobRenderer<BeekeeperGolem,BeeRenderState,BeeModel> {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(GolemsPlus.id("beekeeper_golem"),"main");
    private static final Identifier TEXTURE =
            GolemsPlus.id("textures/entity/beekeeper_golem.png");
    private final ItemModelResolver itemModelResolver;

    public BeeRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,new BeeModel(ctx.bakeLayer(LAYER)),.35f);
        itemModelResolver=ctx.getItemModelResolver();
    }

    @Override public void extractRenderState(BeekeeperGolem entity,
            BeeRenderState render,float partialTick) {
        super.extractRenderState(entity,render,partialTick);
        render.carrying=entity.job()!=0;
        render.breakRoomSeated=GolemBreakService.breakRoomSeated(entity);
        render.breakRoomEating=GolemBreakService.eating(entity);
        render.breakRoomPlayingArcade=GolemBreakService.playingArcade(entity);
        itemModelResolver.updateForTopItem(render.breakRoomSnack,
                GolemBreakService.snackForRender(entity),ItemDisplayContext.FIXED,
                entity.level(),null,entity.getId());
    }

    @Override public BeeRenderState createRenderState() { return new BeeRenderState(); }

    @Override public Identifier getTextureLocation(BeeRenderState state) { return TEXTURE; }

    @Override public void submit(BeeRenderState state,PoseStack pose,
            SubmitNodeCollector collector,CameraRenderState camera) {
        super.submit(state,pose,collector,camera);
        if(state.breakRoomSnack.isEmpty()) return;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180f-state.bodyRot));
        pose.translate(.30,.78,-.34);
        pose.scale(.27f,.27f,.27f);
        state.breakRoomSnack.submit(pose,collector,state.lightCoords,OverlayTexture.NO_OVERLAY,0);
        pose.popPose();
    }
}
