package com.caszgamermd.golemsplus.beekeeper;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Same small silhouette as the Caszual Storage Golem, but with a thick
 * ivory beekeeper jacket, brimmed protective hood, dark mesh veil,
 * mittens, trousers, and work boots, all modeled rather than face-textured.
 */
public final class BeeModel extends EntityModel<BeeRenderState> {
    private final ModelPart head,leftArm,rightArm,leftLeg,rightLeg;
    public BeeModel(ModelPart root){
        super(root);
        head=root.getChild("head");
        leftArm=root.getChild("left_arm");
        rightArm=root.getChild("right_arm");
        leftLeg=root.getChild("left_leg");
        rightLeg=root.getChild("right_leg");
    }
    public static LayerDefinition layer(){
        MeshDefinition mesh=new MeshDefinition();
        PartDefinition root=mesh.getRoot();
        root.addOrReplaceChild("body",CubeListBuilder.create()
                .texOffs(0,0).addBox(-5,-6,-3.5f,10,11,7)
                .texOffs(36,34).addBox(-5.5f,-6.5f,-4,11,5,8)
                .texOffs(36,46).addBox(-5.5f,3,-4,11,3,8),
                PartPose.offset(0,13,0));
        root.addOrReplaceChild("head",CubeListBuilder.create()
                .texOffs(0,20).addBox(-4,-5,-3,8,7,6)
                .texOffs(0,34).addBox(-5,-6,-4,10,9,8)
                // Projecting rigid brim and a forward mesh veil.
                .texOffs(0,52).addBox(-5.5f,-2,-5,11,1,10)
                .texOffs(38,12).addBox(-4.3f,-1,-5.2f,8.6f,5.5f,.7f),
                PartPose.offset(0,6,0));
        root.addOrReplaceChild("left_arm",CubeListBuilder.create()
                .texOffs(0,40).addBox(-2.5f,-1,-2.5f,5,10,5)
                .texOffs(40,22).addBox(-2.7f,6.5f,-2.7f,5.4f,3,5.4f),
                PartPose.offset(7,11,0));
        root.addOrReplaceChild("right_arm",CubeListBuilder.create()
                .texOffs(0,40).addBox(-2.5f,-1,-2.5f,5,10,5)
                .texOffs(40,22).addBox(-2.7f,6.5f,-2.7f,5.4f,3,5.4f),
                PartPose.offset(-7,11,0));
        root.addOrReplaceChild("left_leg",CubeListBuilder.create()
                .texOffs(44,0).addBox(-2,0,-2,4,8,4)
                .texOffs(48,52).addBox(-2.3f,5.5f,-2.7f,4.6f,3,5.4f),
                PartPose.offset(3,16,0));
        root.addOrReplaceChild("right_leg",CubeListBuilder.create()
                .texOffs(44,0).addBox(-2,0,-2,4,8,4)
                .texOffs(48,52).addBox(-2.3f,5.5f,-2.7f,4.6f,3,5.4f),
                PartPose.offset(-3,16,0));
        return LayerDefinition.create(mesh,64,64);
    }
    @Override public void setupAnim(BeeRenderState state){
        super.setupAnim(state);
        head.xRot=state.xRot*Mth.DEG_TO_RAD;
        head.yRot=state.yRot*Mth.DEG_TO_RAD;
        float stride=state.walkAnimationPos*.55f;
        float swing=Mth.clamp(state.walkAnimationSpeed,0,1);
        if(swing<.045f)swing=0;
        leftLeg.xRot=Mth.cos(stride)*1.1f*swing;
        rightLeg.xRot=Mth.cos(stride+Mth.PI)*1.1f*swing;
        leftArm.xRot=rightLeg.xRot*.55f;
        rightArm.xRot=leftLeg.xRot*.55f;
        leftArm.zRot=rightArm.zRot=0;
        if(state.carrying){
            leftArm.xRot=rightArm.xRot=-1.12f;
            leftArm.zRot=-.11f;rightArm.zRot=.11f;
        }
    }
}
