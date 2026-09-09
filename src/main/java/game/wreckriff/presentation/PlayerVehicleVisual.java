package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.presentation.VehicleVisual.Builder;

/** Original heavy industrial truck and light interceptor, authored against their native hulls. */
final class PlayerVehicleVisual {
    private final Builder paint=new Builder(),steel=new Builder(),rubber=new Builder(),glass=new Builder(),head=new Builder(),tail=new Builder();
    private final VehicleProfile profile;
    private PlayerVehicleVisual(VehicleProfile profile) {this.profile=profile;}

    static Node create(AssetManager assets,VehicleProfile profile,int livery) {
        if(livery<0)throw new IllegalArgumentException("Nonnegative livery required");
        var author=new PlayerVehicleVisual(profile);Node root=new Node(profile.id());
        ColorRGBA color=switch(profile.id()) {
            case "grinder" -> {author.grinder(root);yield new ColorRGBA(.71f,.42f,.12f,1);}
            case "spark" -> {author.spark();yield new ColorRGBA(.10f,.62f,.63f,1);}
            default -> throw new IllegalArgumentException("Unknown alternate player chassis "+profile.id());
        };
        var surfaces=new SurfaceMaterials(assets);Material steel=surfaces.material("steel"),rubber=surfaces.rubber();
        author.mounts(root);
        author.paint.attach(root,"paint",surfaces.paint(color));author.steel.attach(root,"steel",steel);author.rubber.attach(root,"rubber-trim",rubber);
        author.glass.attach(root,"glass",SurfaceMaterials.lit(assets,new ColorRGBA(.018f,.045f,.055f,1),110,.28f));
        author.head.attach(root,"headlights",VehicleVisual.unlit(assets,new ColorRGBA(.95f,.77f,.43f,1)));
        author.tail.attach(root,"taillights",VehicleVisual.unlit(assets,new ColorRGBA(.92f,.10f,.025f,1)));
        for(int wheel=0;wheel<4;wheel++) {
            Node axle=VehicleVisual.wheel(wheel,steel,rubber);axle.setLocalScale(profile.wheelRadius()/.38f);
            axle.setLocalTranslation(profile.wheelConnection(wheel).add(0,-profile.suspensionRestLength(),0));root.attachChild(axle);
        }
        root.setUserData("livery",livery);return root;
    }
    private void grinder(Node root) {
        // The actual tall front cab and low rear machine bed follow Grinder's three native hull boxes.
        paint.loft(new float[][]{{-2.92f,1.03f,-.07f,.65f},{-2.62f,1.12f,-.09f,.80f},
                {2.25f,1.12f,-.09f,.77f},{2.55f,1.10f,.02f,.68f}});
        paint.loft(new float[][]{{-.38f,.97f,.64f,2.31f},{-.14f,1.01f,.64f,2.48f},
                {1.52f,1.01f,.64f,2.48f},{1.79f,.92f,.64f,2.24f}});
        glass.quad(v(-.80f,1.40f,1.81f),v(.80f,1.40f,1.81f),v(.80f,2.18f,1.76f),v(-.80f,2.18f,1.76f));
        steel.box(0,1.83f,1.82f,.026f,.41f,.027f);
        for(int side:new int[]{-1,1}) {
            glass.box(side*1.017f,1.84f,.69f,.008f,.34f,.68f);
            steel.box(side*.96f,2.30f,1.66f,.045f,.15f,.045f);
            steel.box(side*1.07f,1.06f,.56f,.04f,.025f,.25f);
            steel.box(side*1.16f,.46f,.54f,.08f,.032f,.68f);
            rubber.box(side*1.03f,.98f,1.80f,.08f,.085f,.045f);
            head.box(side*.79f,1.14f,1.828f,.125f,.070f,.012f);
            tail.box(side*.80f,.39f,-2.93f,.16f,.050f,.018f);
            paint.box(side*1.09f,.94f,-1.77f,.056f,.20f,.85f);
            steel.box(side*1.15f,1.13f,-1.77f,.030f,.026f,.88f);
            steel.box(side*.74f,1.16f,-2.15f,.037f,.40f,.037f);
            for(int rib=0;rib<4;rib++)steel.box(side*1.153f,.89f,-2.39f+rib*.47f,.020f,.20f,.022f);
        }
        rubber.box(0,.91f,-1.80f,.75f,.12f,.72f);
        steel.box(0,1.565f,-2.15f,.78f,.025f,.045f);
        // Counter-rotating intake shafts are mechanical barrels with coarse teeth, not a painted bumper.
        for(float height:new float[]{.25f,.60f}) {
            steel.cylinderX(0,height,2.78f,.17f,2.38f,16);
            for(int tooth=-6;tooth<=6;tooth++) {
                float x=tooth*.17f,phase=tooth%2==0?-.09f:.09f;
                steel.box(x,height+phase,2.945f,.037f,.046f,.041f);
            }
        }
        for(int side:new int[]{-1,1})paint.box(side*1.23f,.43f,2.76f,.040f,.34f,.17f);
        Node intake=new Node("grinder-intake");intake.setLocalTranslation(profile.grinderIntake());root.attachChild(intake);
    }
    private void spark() {
        // Compact wheelbase, clipped nose, steep rear glass and tapered roll-over cell identify the light car.
        paint.loft(new float[][]{{-1.55f,.62f,-.07f,.23f},{-1.30f,.79f,-.10f,.43f},
                {-.42f,.80f,-.10f,.47f},{.63f,.80f,-.08f,.40f},{1.55f,.61f,.01f,.16f}});
        paint.loft(new float[][]{{-.86f,.68f,.40f,.65f},{-.59f,.62f,.40f,1.06f},
                {.18f,.59f,.39f,1.06f},{.68f,.66f,.39f,.46f}});
        glass.quad(v(-.61f,.475f,.65f),v(.61f,.475f,.65f),v(.54f,1.025f,.18f),v(-.54f,1.025f,.18f));
        glass.quad(v(.60f,.64f,-.858f),v(-.60f,.64f,-.858f),v(-.56f,1.025f,-.59f),v(.56f,1.025f,-.59f));
        for(int side:new int[]{-1,1}) {
            glass.quad(v(side*.674f,.48f,-.77f),v(side*.626f,.99f,-.56f),v(side*.60f,.99f,.15f),v(side*.668f,.48f,.53f));
            steel.box(side*.79f,.04f,0,.033f,.04f,.94f);
            rubber.box(side*.82f,-.055f,0,.036f,.095f,.67f);
            paint.box(side*.66f,.48f,-1.26f,.105f,.14f,.13f);
            steel.box(side*.64f,.63f,-1.30f,.036f,.09f,.045f);
            head.box(side*.44f,.207f,1.43f,.15f,.028f,.016f);
            tail.box(side*.59f,.28f,-1.53f,.065f,.075f,.010f);
            for(int slat=0;slat<3;slat++)rubber.box(side*.48f,.459f,-.94f+slat*.11f,.10f,.015f,.023f);
        }
        steel.box(0,.73f,-1.30f,.70f,.020f,.14f);
        rubber.box(0,.13f,1.525f,.29f,.045f,.012f);
        for(int rib=-2;rib<=2;rib++)steel.box(rib*.10f,.14f,1.54f,.012f,.033f,.012f);
        steel.box(0,.466f,.78f,.028f,.012f,.15f);
    }
    private void mounts(Node root) {
        for(int wheel=0;wheel<4;wheel++) {
            Vector3f center=profile.wheelConnection(wheel).add(0,-profile.suspensionRestLength(),0);
            paint.arch(center.x,center.y,center.z,profile.wheelRadius()*1.04f,profile.wheelRadius()*1.20f,.065f,14);
        }
        for(int barrel=0;barrel<2;barrel++) {
            Vector3f tip=profile.machineGunMuzzle(barrel);
            steel.cylinderZ(tip.x,tip.y,tip.z-.23f,.068f,.44f,10);
            rubber.cylinderZ(tip.x,tip.y,tip.z-.005f,.045f,.01f,10);
            Node anchor=new Node("machine-gun-muzzle-"+barrel);anchor.setLocalTranslation(tip);root.attachChild(anchor);
        }
        Node muzzle=new Node("weapon-muzzle"),base=new Node("weapon-base");
        muzzle.setLocalTranslation(profile.muzzle());base.setLocalTranslation(profile.weaponBase());root.attachChild(muzzle);root.attachChild(base);
        for(int side:new int[]{-1,1}) {
            float x=side*profile.width()*.34f,y=-.04f*profile.height()/1.07f,z=-profile.length()*.5225f;
            steel.cylinderZ(x,y,z+.11f,.075f,.19f,10);rubber.cylinderZ(x,y,z+.006f,.053f,.012f,10);
            Node exhaust=new Node("exhaust-"+(side<0?"left":"right"));exhaust.setLocalTranslation(x,y,z);root.attachChild(exhaust);
        }
    }
    private static Vector3f v(float x,float y,float z) {return new Vector3f(x,y,z);}
}
