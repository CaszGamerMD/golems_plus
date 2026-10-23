package com.caszgamermd.golemsplus.render;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * One compact 3D worker skeleton for all Caszual job uniforms. All safety
 * gear, tailor details and accessories are actual cuboids, not flat overlays.
 * Profession-specific parts occupy different atlas areas and never z-fight.
 *
 * UV resolution: 128x128. Model origin and limb pivots match the Beekeeper.
 */
public final class ProfessionGolemModel
        extends EntityModel<ProfessionGolemRenderState> {
    public enum Profession { BUILDER, TRADER, CHEF, FISHER }

    private final ModelPart head,leftArm,rightArm,leftLeg,rightLeg;
    private final ModelPart traderClosed,traderOpen,fisherRod,fisherFish,foldingChair;
    private final Profession profession;

    public ProfessionGolemModel(ModelPart root,Profession profession){
        super(root);
        this.profession=profession;
        head=root.getChild("head");
        leftArm=root.getChild("left_arm");
        rightArm=root.getChild("right_arm");
        leftLeg=root.getChild("left_leg");
        rightLeg=root.getChild("right_leg");
        ModelPart torso=root.getChild("body");
        traderClosed=profession==Profession.TRADER?torso.getChild("closed_case"):null;
        traderOpen=profession==Profession.TRADER?torso.getChild("open_case"):null;
        fisherRod=profession==Profession.FISHER?rightArm.getChild("fishing_rod"):null;
        fisherFish=profession==Profession.FISHER?fisherRod.getChild("caught_fish"):null;
        foldingChair=profession==Profession.FISHER?root.getChild("folding_chair"):null;
    }

    public static LayerDefinition builderLayer(){return layer(Profession.BUILDER);}
    public static LayerDefinition traderLayer(){return layer(Profession.TRADER);}
    public static LayerDefinition chefLayer(){return layer(Profession.CHEF);}
    public static LayerDefinition fisherLayer(){return layer(Profession.FISHER);}

    private static LayerDefinition layer(Profession type){
        MeshDefinition mesh=new MeshDefinition();
        PartDefinition root=mesh.getRoot();

        PartDefinition torso=root.addOrReplaceChild("body",CubeListBuilder.create()
                .texOffs(0,0).addBox(-5,-6,-3.5f,10,11,7),
                PartPose.offset(0,13,0));
        PartDefinition head=root.addOrReplaceChild("head",CubeListBuilder.create()
                .texOffs(0,26).addBox(-4,-4,-3,8,7,6),
                PartPose.offset(0,6,0));
        PartDefinition left=root.addOrReplaceChild("left_arm",CubeListBuilder.create()
                .texOffs(46,0).addBox(-2.5f,-1,-2.5f,5,10,5),
                PartPose.offset(7,11,0));
        PartDefinition right=root.addOrReplaceChild("right_arm",CubeListBuilder.create()
                .texOffs(46,0).addBox(-2.5f,-1,-2.5f,5,10,5),
                PartPose.offset(-7,11,0));
        PartDefinition leftLeg=root.addOrReplaceChild("left_leg",CubeListBuilder.create()
                .texOffs(71,0).addBox(-2,0,-2,4,8,4),
                PartPose.offset(3,16,0));
        PartDefinition rightLeg=root.addOrReplaceChild("right_leg",CubeListBuilder.create()
                .texOffs(71,0).addBox(-2,0,-2,4,8,4),
                PartPose.offset(-3,16,0));

        switch(type){
            case BUILDER -> {
                // Raised hard-hat bowl, brow brim, rear flap and center rib.
                head.addOrReplaceChild("helmet_crown",CubeListBuilder.create()
                        .texOffs(75,24).addBox(-4.7f,-7.2f,-3.8f,9.4f,4,7.6f),
                        PartPose.ZERO);
                head.addOrReplaceChild("helmet_brim",CubeListBuilder.create()
                        .texOffs(0,59).addBox(-6,-3.9f,-5,12,1.1f,10),
                        PartPose.ZERO);
                head.addOrReplaceChild("helmet_ridge",CubeListBuilder.create()
                        .texOffs(53,76).addBox(-.7f,-7.7f,-4.2f,1.4f,1,8.4f),
                        PartPose.ZERO);
                torso.addOrReplaceChild("hi_vis_vest",CubeListBuilder.create()
                        .texOffs(42,34).addBox(-5.45f,-6.25f,-3.95f,
                                10.9f,10.7f,7.9f),PartPose.ZERO);
                // Reflective bands sit outside the vest, not inside it.
                torso.addOrReplaceChild("vest_strap_l",CubeListBuilder.create()
                        .texOffs(86,52).addBox(-4.1f,-6.45f,-4.14f,
                                1.6f,8.4f,.45f),PartPose.ZERO);
                torso.addOrReplaceChild("vest_strap_r",CubeListBuilder.create()
                        .texOffs(86,52).addBox(2.5f,-6.45f,-4.14f,
                                1.6f,8.4f,.45f),PartPose.ZERO);
                torso.addOrReplaceChild("vest_belt",CubeListBuilder.create()
                        .texOffs(94,52).addBox(-5.6f,1.6f,-4.2f,
                                11.2f,1.4f,.5f),PartPose.ZERO);
                torso.addOrReplaceChild("utility_belt",CubeListBuilder.create()
                        .texOffs(72,67).addBox(-5.4f,4.3f,-3.95f,
                                10.8f,2.0f,7.9f),PartPose.ZERO);
                torso.addOrReplaceChild("tool_pouch",CubeListBuilder.create()
                        .texOffs(109,80).addBox(4.2f,3.0f,-4.3f,
                                2.3f,3.7f,3.2f),PartPose.ZERO);
                left.addOrReplaceChild("work_glove",CubeListBuilder.create()
                        .texOffs(108,0).addBox(-2.6f,6.2f,-2.6f,
                                5.2f,3.2f,5.2f),PartPose.ZERO);
                right.addOrReplaceChild("work_glove",CubeListBuilder.create()
                        .texOffs(108,0).addBox(-2.6f,6.2f,-2.6f,
                                5.2f,3.2f,5.2f),PartPose.ZERO);
                leftLeg.addOrReplaceChild("work_boot",CubeListBuilder.create()
                        .texOffs(107,15).addBox(-2.2f,5.6f,-2.8f,
                                4.4f,2.4f,5.6f),PartPose.ZERO);
                rightLeg.addOrReplaceChild("work_boot",CubeListBuilder.create()
                        .texOffs(107,15).addBox(-2.2f,5.6f,-2.8f,
                                4.4f,2.4f,5.6f),PartPose.ZERO);
            }
            case TRADER -> {
                // Tailored overcoat, lapels, crisp white shirt and red tie.
                torso.addOrReplaceChild("suit_jacket",CubeListBuilder.create()
                        .texOffs(42,34).addBox(-5.3f,-6.35f,-3.95f,
                                10.6f,11,7.9f),PartPose.ZERO);
                torso.addOrReplaceChild("shirt",CubeListBuilder.create()
                        .texOffs(86,33).addBox(-1.75f,-6.35f,-4.16f,
                                3.5f,7.8f,.48f),PartPose.ZERO);
                torso.addOrReplaceChild("lapel_l",CubeListBuilder.create()
                        .texOffs(93,33).addBox(-3.4f,-6.35f,-4.3f,
                                1.75f,6.6f,.47f),PartPose.ZERO);
                torso.addOrReplaceChild("lapel_r",CubeListBuilder.create()
                        .texOffs(93,33).addBox(1.65f,-6.35f,-4.3f,
                                1.75f,6.6f,.47f),PartPose.ZERO);
                torso.addOrReplaceChild("tie",CubeListBuilder.create()
                        .texOffs(100,33).addBox(-.65f,-5.7f,-4.46f,
                                1.3f,6.1f,.44f),PartPose.ZERO);
                torso.addOrReplaceChild("waistcoat",CubeListBuilder.create()
                        .texOffs(107,34).addBox(-4.7f,2.5f,-4.2f,
                                9.4f,2.0f,.44f),PartPose.ZERO);
                head.addOrReplaceChild("styled_hair",CubeListBuilder.create()
                        .texOffs(76,25).addBox(-4.2f,-4.9f,-3.3f,
                                8.4f,1.4f,6.6f),PartPose.ZERO);
                left.addOrReplaceChild("shirt_cuff",CubeListBuilder.create()
                        .texOffs(108,0).addBox(-2.6f,7.2f,-2.6f,
                                5.2f,1.3f,5.2f),PartPose.ZERO);
                right.addOrReplaceChild("shirt_cuff",CubeListBuilder.create()
                        .texOffs(108,0).addBox(-2.6f,7.2f,-2.6f,
                                5.2f,1.3f,5.2f),PartPose.ZERO);
                leftLeg.addOrReplaceChild("formal_shoe",CubeListBuilder.create()
                        .texOffs(107,15).addBox(-2.15f,6.3f,-2.8f,
                                4.3f,1.7f,5.5f),PartPose.ZERO);
                rightLeg.addOrReplaceChild("formal_shoe",CubeListBuilder.create()
                        .texOffs(107,15).addBox(-2.15f,6.3f,-2.8f,
                                4.3f,1.7f,5.5f),PartPose.ZERO);
                // Two-handed, front-facing closed leather briefcase.
                // It is attached to the body to stay steady while walking.
                PartDefinition closed=torso.addOrReplaceChild("closed_case",
                        CubeListBuilder.create().texOffs(0,79)
                        .addBox(-5.1f,-.6f,-8.0f,10.2f,7,3.5f),
                        PartPose.ZERO);
                closed.addOrReplaceChild("handle",CubeListBuilder.create()
                        .texOffs(31,80).addBox(-1.7f,-2.5f,-7.3f,3.4f,2.2f,1.3f),
                        PartPose.ZERO);
                closed.addOrReplaceChild("locks",CubeListBuilder.create()
                        .texOffs(40,82).addBox(-3.7f,1.2f,-8.35f,1.5f,1.3f,.4f)
                        .texOffs(40,82).addBox(2.2f,1.2f,-8.35f,1.5f,1.3f,.4f),
                        PartPose.ZERO);

                // Open attache case: the tray faces up and the lid faces
                // the customer, with raised business-card/pocket detailing.
                PartDefinition open=torso.addOrReplaceChild("open_case",
                        CubeListBuilder.create().texOffs(0,79)
                        .addBox(-6.5f,-1.1f,-10.3f,13,2.3f,8.5f),
                        PartPose.offset(0,0,-3.2f));
                open.addOrReplaceChild("lid",CubeListBuilder.create()
                        .texOffs(49,80).addBox(-6.5f,-10.4f,-2.6f,13,9.3f,1.6f),
                        PartPose.ZERO);
                open.addOrReplaceChild("lining",CubeListBuilder.create()
                        .texOffs(79,78).addBox(-5.9f,-.98f,-9.7f,11.8f,.35f,7.3f),
                        PartPose.ZERO);
                open.addOrReplaceChild("pockets",CubeListBuilder.create()
                        .texOffs(108,82).addBox(-5.4f,-8.2f,-3.08f,4.1f,4.5f,.5f)
                        .texOffs(108,82).addBox(1.3f,-8.2f,-3.08f,4.1f,4.5f,.5f),
                        PartPose.ZERO);
            }
            case FISHER -> {
                // High-detail red folding campsite chair modeled after the
                // user's real-world reference: suspended canvas, reinforced
                // seams, tubular X-frame, armrests, cup holder and feet.
                // Coordinates use the same 24-pixel ground plane as the Golem.
                // This group is hidden while walking, and follows the
                // Fisher's torso position only when seated/waiting.
                PartDefinition chair=root.addOrReplaceChild("folding_chair",
                        CubeListBuilder.create(),PartPose.ZERO);

                // Bright red canvas: a wide, lightly tilted seat and a
                // high fabric back, with separately modeled top binding.
                chair.addOrReplaceChild("red_canvas_seat",CubeListBuilder.create()
                        .texOffs(190,0).addBox(-8.1f,16.4f,-7.2f,
                                16.2f,1.0f,13.8f),PartPose.ZERO);
                chair.addOrReplaceChild("red_canvas_back",CubeListBuilder.create()
                        .texOffs(190,24).addBox(-8.0f,4.0f,6.0f,
                                16.0f,12.3f,1.0f),PartPose.ZERO);
                chair.addOrReplaceChild("red_headrest",CubeListBuilder.create()
                        .texOffs(190,42).addBox(-8.4f,3.4f,5.7f,
                                16.8f,2.4f,1.35f),PartPose.ZERO);
                // Top and seat binding are black nylon webbing rather than
                // black texture painted onto the middle of the fabric.
                chair.addOrReplaceChild("seat_front_binding",CubeListBuilder.create()
                        .texOffs(190,63).addBox(-8.7f,16.15f,-7.7f,
                                17.4f,.65f,1.0f),PartPose.ZERO);
                chair.addOrReplaceChild("seat_rear_binding",CubeListBuilder.create()
                        .texOffs(190,63).addBox(-8.7f,16.15f,6.25f,
                                17.4f,.65f,.8f),PartPose.ZERO);
                chair.addOrReplaceChild("seat_side_binding_left",CubeListBuilder.create()
                        .texOffs(190,63).addBox(7.85f,16.15f,-7.2f,
                                .8f,.65f,13.7f),PartPose.ZERO);
                chair.addOrReplaceChild("seat_side_binding_right",CubeListBuilder.create()
                        .texOffs(190,63).addBox(-8.65f,16.15f,-7.2f,
                                .8f,.65f,13.7f),PartPose.ZERO);
                chair.addOrReplaceChild("back_binding_top",CubeListBuilder.create()
                        .texOffs(190,63).addBox(-8.65f,3.20f,5.65f,
                                17.3f,.8f,1.45f),PartPose.ZERO);
                chair.addOrReplaceChild("back_binding_left",CubeListBuilder.create()
                        .texOffs(190,63).addBox(-8.65f,4.0f,5.7f,
                                .7f,12.4f,1.4f),PartPose.ZERO);
                chair.addOrReplaceChild("back_binding_right",CubeListBuilder.create()
                        .texOffs(190,63).addBox(7.95f,4.0f,5.7f,
                                .7f,12.4f,1.4f),PartPose.ZERO);
                // Woven nylon arms extend forward past the canvas body.
                chair.addOrReplaceChild("left_red_armrest",CubeListBuilder.create()
                        .texOffs(190,45).addBox(8.6f,12.5f,-8.4f,
                                3.05f,1.1f,13.8f),PartPose.ZERO);
                chair.addOrReplaceChild("right_red_armrest",CubeListBuilder.create()
                        .texOffs(190,45).addBox(-11.65f,12.5f,-8.4f,
                                3.05f,1.1f,13.8f),PartPose.ZERO);
                chair.addOrReplaceChild("arm_binding_left",CubeListBuilder.create()
                        .texOffs(190,63).addBox(8.4f,12.35f,-8.6f,
                                3.35f,.34f,14.2f),PartPose.ZERO);
                chair.addOrReplaceChild("arm_binding_right",CubeListBuilder.create()
                        .texOffs(190,63).addBox(-11.75f,12.35f,-8.6f,
                                3.35f,.34f,14.2f),PartPose.ZERO);

                // Tube frame: four splayed upright posts, two back supports,
                // folding pivots and double crossed diagonal braces.
                CubeListBuilder metal=CubeListBuilder.create().texOffs(190,92);
                chair.addOrReplaceChild("front_steel_post_left",
                        metal.addBox(8.2f,13.35f,-6.7f,1.05f,10.2f,1.05f),
                        PartPose.ZERO);
                chair.addOrReplaceChild("front_steel_post_right",CubeListBuilder.create()
                        .texOffs(190,92).addBox(-9.25f,13.35f,-6.7f,
                                1.05f,10.2f,1.05f),PartPose.ZERO);
                chair.addOrReplaceChild("rear_steel_post_left",CubeListBuilder.create()
                        .texOffs(190,92).addBox(8.2f,3.65f,6.0f,
                                1.05f,20.0f,1.05f),PartPose.ZERO);
                chair.addOrReplaceChild("rear_steel_post_right",CubeListBuilder.create()
                        .texOffs(190,92).addBox(-9.25f,3.65f,6.0f,
                                1.05f,20.0f,1.05f),PartPose.ZERO);
                chair.addOrReplaceChild("arm_support_left",CubeListBuilder.create()
                        .texOffs(190,92).addBox(8.4f,13.3f,-7.55f,
                                .8f,6.4f,.85f),PartPose.ZERO);
                chair.addOrReplaceChild("arm_support_right",CubeListBuilder.create()
                        .texOffs(190,92).addBox(-9.2f,13.3f,-7.55f,
                                .8f,6.4f,.85f),PartPose.ZERO);
                // Real crossed braces are rotated rods, not an opaque
                // tabletop or solid triangular polygons.
                for(int side=0;side<2;side++){
                    float z=side==0?-6.4f:6.5f;
                    for(int diagonal=0;diagonal<2;diagonal++){
                        float angle=diagonal==0?.71f:-.71f;
                        chair.addOrReplaceChild("cross_"+side+"_"+diagonal,
                                CubeListBuilder.create().texOffs(190,92)
                                        .addBox(-.43f,-9.5f,-.45f,
                                                .86f,19.0f,.9f),
                                PartPose.offsetAndRotation(0,18.25f,z,0,0,angle));
                    }
                }
                // Triangular side-fold braces and metal rivet blocks.
                for(int side=0;side<2;side++){
                    float x=side==0?-8.55f:8.55f;
                    for(int diagonal=0;diagonal<2;diagonal++){
                        float tilt=diagonal==0?.49f:-.49f;
                        chair.addOrReplaceChild("side_cross_"+side+"_"+diagonal,
                                CubeListBuilder.create().texOffs(190,92)
                                        .addBox(-.38f,-7.1f,-.38f,
                                                .76f,14.2f,.76f),
                                PartPose.offsetAndRotation(x,18.8f,0,
                                        tilt,0,0));
                    }
                    chair.addOrReplaceChild("frame_hinge_"+side,
                            CubeListBuilder.create().texOffs(190,65)
                                    .addBox(x-1.05f,16.5f,-.65f,
                                            2.1f,2.1f,1.3f),PartPose.ZERO);
                }
                // Individually modeled plastic foot caps anchor each post.
                for(int side=0;side<2;side++)
                    for(int back=0;back<2;back++){
                        float x=side==0?-9.1f:8.1f;
                        float z=back==0?-7.4f:5.5f;
                        chair.addOrReplaceChild("rubber_foot_"+side+"_"+back,
                                CubeListBuilder.create().texOffs(190,119)
                                        .addBox(x,22.2f,z,2.2f,1.8f,2.0f),
                                PartPose.ZERO);
                    }
                // Cup-holder: black sewn rim, open top (not a solid cube),
                // and a shallow dark mesh basket hanging off the left arm.
                chair.addOrReplaceChild("cup_holder_front",CubeListBuilder.create()
                        .texOffs(190,65).addBox(9.0f,12.05f,-5.8f,
                                2.7f,.75f,.45f),PartPose.ZERO);
                chair.addOrReplaceChild("cup_holder_back",CubeListBuilder.create()
                        .texOffs(190,65).addBox(9.0f,12.05f,-2.25f,
                                2.7f,.75f,.45f),PartPose.ZERO);
                chair.addOrReplaceChild("cup_holder_left",CubeListBuilder.create()
                        .texOffs(190,65).addBox(8.9f,12.05f,-5.8f,
                                .45f,.75f,4.0f),PartPose.ZERO);
                chair.addOrReplaceChild("cup_holder_right",CubeListBuilder.create()
                        .texOffs(190,65).addBox(11.2f,12.05f,-5.8f,
                                .45f,.75f,4.0f),PartPose.ZERO);
                chair.addOrReplaceChild("mesh_cup_pouch",CubeListBuilder.create()
                        .texOffs(212,75).addBox(9.35f,12.7f,-5.25f,
                                1.9f,3.4f,2.9f),PartPose.ZERO);
                // Broad soft fishing hat, woven band and raised brim.
                head.addOrReplaceChild("hat_crown",CubeListBuilder.create()
                        .texOffs(75,24).addBox(-4.5f,-7.4f,-3.5f,9,4.1f,7),
                        PartPose.ZERO);
                head.addOrReplaceChild("hat_brim",CubeListBuilder.create()
                        .texOffs(0,59).addBox(-6.5f,-3.7f,-5.5f,13,1.0f,11),
                        PartPose.ZERO);
                head.addOrReplaceChild("hat_band",CubeListBuilder.create()
                        .texOffs(53,76).addBox(-4.8f,-4.3f,-3.8f,9.6f,.9f,7.6f),
                        PartPose.ZERO);
                // Thick olive utility vest, pockets and tan fishing shorts.
                torso.addOrReplaceChild("fishing_vest",CubeListBuilder.create()
                        .texOffs(42,34).addBox(-5.4f,-6.25f,-3.95f,10.8f,8.8f,7.9f),
                        PartPose.ZERO);
                torso.addOrReplaceChild("vest_zip",CubeListBuilder.create()
                        .texOffs(94,52).addBox(-.3f,-5.8f,-4.36f,.6f,7.5f,.5f),
                        PartPose.ZERO);
                torso.addOrReplaceChild("pocket_l",CubeListBuilder.create()
                        .texOffs(72,67).addBox(-4.6f,-.2f,-4.7f,3.3f,3.4f,.9f),
                        PartPose.ZERO);
                torso.addOrReplaceChild("pocket_r",CubeListBuilder.create()
                        .texOffs(72,67).addBox(1.3f,-.2f,-4.7f,3.3f,3.4f,.9f),
                        PartPose.ZERO);
                leftLeg.addOrReplaceChild("flip_flop_l",CubeListBuilder.create()
                        .texOffs(107,15).addBox(-2.2f,7.2f,-3.5f,4.4f,.65f,6.8f)
                        .texOffs(108,0).addBox(-.55f,6.45f,-3.0f,1.1f,.8f,3.1f),
                        PartPose.ZERO);
                rightLeg.addOrReplaceChild("flip_flop_r",CubeListBuilder.create()
                        .texOffs(107,15).addBox(-2.2f,7.2f,-3.5f,4.4f,.65f,6.8f)
                        .texOffs(108,0).addBox(-.55f,6.45f,-3.0f,1.1f,.8f,3.1f),
                        PartPose.ZERO);
                // Long tapered rod leaves the hand toward the water instead
                // of clipping backward through the Golem's shoulder.
                PartDefinition rod=right.addOrReplaceChild("fishing_rod",
                        CubeListBuilder.create().texOffs(130,4)
                        .addBox(-.65f,-7.0f,-.65f,1.3f,13.0f,1.3f)
                        .texOffs(134,4).addBox(-.43f,-23.0f,-.43f,.86f,17.0f,.86f)
                        .texOffs(139,4).addBox(-.25f,-33.0f,-.25f,.5f,10.5f,.5f),
                        PartPose.offsetAndRotation(0,7.6f,-2.7f,1.97f,0,-.14f));
                rod.addOrReplaceChild("reel",CubeListBuilder.create()
                        .texOffs(145,5).addBox(-1.8f,1.0f,-1.8f,3.6f,2.6f,2.8f),
                        PartPose.ZERO);
                // The hooked fish is animated only during the actual reel-in,
                // never indefinitely because fish exists in pocket storage.
                PartDefinition fish=rod.addOrReplaceChild("caught_fish",
                        CubeListBuilder.create().texOffs(151,18)
                        .addBox(-.17f,-32.5f,-.25f,.35f,12.0f,.35f)
                        .texOffs(160,4).addBox(-2.0f,-20.5f,-1.0f,4,3.6f,3),
                        PartPose.ZERO);
                fish.addOrReplaceChild("tail",CubeListBuilder.create()
                        .texOffs(176,4).addBox(-.9f,-2.7f,-.8f,1.8f,2.2f,1.7f),
                        PartPose.ZERO);
            }
            case CHEF -> {
                // Extra-tall soft toque with distinguishable crown pleats.
                head.addOrReplaceChild("toque_band",CubeListBuilder.create()
                        .texOffs(75,24).addBox(-4.65f,-5.2f,-3.75f,
                                9.3f,3.0f,7.5f),PartPose.ZERO);
                head.addOrReplaceChild("toque_crown",CubeListBuilder.create()
                        .texOffs(38,87).addBox(-5.4f,-10.6f,-4.55f,
                                10.8f,5.7f,9.1f),PartPose.ZERO);
                head.addOrReplaceChild("toque_fold",CubeListBuilder.create()
                        .texOffs(86,89).addBox(-5.15f,-6.1f,-4.55f,
                                10.3f,1.2f,9.1f),PartPose.ZERO);
                torso.addOrReplaceChild("chef_coat",CubeListBuilder.create()
                        .texOffs(42,34).addBox(-5.35f,-6.25f,-3.95f,
                                10.7f,10.9f,7.9f),PartPose.ZERO);
                torso.addOrReplaceChild("front_apron",CubeListBuilder.create()
                        .texOffs(86,33).addBox(-3.8f,-3.7f,-4.17f,
                                7.6f,9.7f,.47f),PartPose.ZERO);
                torso.addOrReplaceChild("apron_strap_l",CubeListBuilder.create()
                        .texOffs(95,54).addBox(-4.0f,-6.3f,-4.27f,
                                1.1f,4.0f,.5f),PartPose.ZERO);
                torso.addOrReplaceChild("apron_strap_r",CubeListBuilder.create()
                        .texOffs(95,54).addBox(2.9f,-6.3f,-4.27f,
                                1.1f,4.0f,.5f),PartPose.ZERO);
                torso.addOrReplaceChild("apron_tie",CubeListBuilder.create()
                        .texOffs(112,57).addBox(-5.5f,2.8f,-4.15f,
                                11,1.2f,.4f),PartPose.ZERO);
                left.addOrReplaceChild("rolled_cuff",CubeListBuilder.create()
                        .texOffs(108,0).addBox(-2.6f,6.2f,-2.6f,
                                5.2f,2.4f,5.2f),PartPose.ZERO);
                right.addOrReplaceChild("rolled_cuff",CubeListBuilder.create()
                        .texOffs(108,0).addBox(-2.6f,6.2f,-2.6f,
                                5.2f,2.4f,5.2f),PartPose.ZERO);
                leftLeg.addOrReplaceChild("non_slip_shoe",CubeListBuilder.create()
                        .texOffs(107,15).addBox(-2.15f,6.1f,-2.65f,
                                4.3f,1.9f,5.3f),PartPose.ZERO);
                rightLeg.addOrReplaceChild("non_slip_shoe",CubeListBuilder.create()
                        .texOffs(107,15).addBox(-2.15f,6.1f,-2.65f,
                                4.3f,1.9f,5.3f),PartPose.ZERO);
            }
        }
        return LayerDefinition.create(mesh,256,128);
    }

    @Override public void setupAnim(ProfessionGolemRenderState state){
        super.setupAnim(state);
        head.xRot=state.xRot*Mth.DEG_TO_RAD;
        head.yRot=state.yRot*Mth.DEG_TO_RAD;
        float travel=state.walkAnimationPos*.58f;
        float swing=Mth.clamp(state.walkAnimationSpeed,0,1);
        if(swing<.035f)swing=0;
        leftLeg.xRot=Mth.cos(travel)*1.02f*swing;
        rightLeg.xRot=Mth.cos(travel+Mth.PI)*1.02f*swing;
        leftArm.xRot=rightLeg.xRot*.52f;
        rightArm.xRot=leftLeg.xRot*.52f;
        leftArm.zRot=rightArm.zRot=0;
        if(profession==Profession.BUILDER){
            // A construction worker carries a shulker using both hands.
            if(state.carrying){
                leftArm.xRot=rightArm.xRot=-1.10f;
                leftArm.zRot=-.10f;rightArm.zRot=.10f;
            }else if(state.working&&swing==0){
                rightArm.xRot=-.72f;
            }
        }else if(profession==Profession.TRADER){
            traderClosed.visible=!state.presenting;
            traderOpen.visible=state.presenting;
            // Both hands support the case. During an offer they spread out
            // to display its open document compartments.
            leftArm.xRot=rightArm.xRot=state.presenting?-1.20f:-.90f;
            leftArm.zRot=state.presenting?.48f:.65f;
            rightArm.zRot=state.presenting?-.48f:-.65f;
        }else if(profession==Profession.FISHER){
            foldingChair.visible=state.seated;
            if(state.seated){
                leftLeg.xRot=rightLeg.xRot=-1.25f;
                leftArm.xRot=-.60f;rightArm.xRot=-1.0f;
                leftArm.zRot=-.20f;rightArm.zRot=.15f;
            }
            fisherRod.visible=state.hasRod;
            fisherFish.visible=state.reeling&&state.carryingFish;
            if(state.hasRod){
                float pulse=Mth.sin(state.ageInTicks*.17f);
                // Casting: arm and long rod point over the water. A strike
                // lifts the rod and wriggles the just-caught fish for 2s.
                rightArm.xRot=state.reeling?-1.65f+.19f*pulse:
                        state.casting?-1.12f+.045f*pulse:
                        state.seated?-1.02f:-.44f;
                rightArm.zRot=-.19f;
                if(state.reeling)fisherFish.xRot=.24f*pulse;
                else fisherFish.xRot=0;
            }
            leftArm.xRot*=.6f;
        }else if(profession==Profession.CHEF){
            // Deliberately calm kitchen stance.
            leftArm.xRot*=.38f;rightArm.xRot*=.38f;
            leftArm.zRot=-.06f;rightArm.zRot=.06f;
            if(state.working){
                float stir=Mth.sin(state.ageInTicks*.18f);
                rightArm.xRot=-.95f+.25f*stir;
                leftArm.xRot=-.55f-.12f*stir;
            }
        }
    }
}
