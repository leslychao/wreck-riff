package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.presentation.VehicleVisual.Builder;

/** Five original bodies authored in metres inside the registered native chassis envelope. */
final class BossVehicleVisual {
    private final VehicleProfile profile;
    private final float w,y,l;
    private final Builder paint=new Builder(),metal=new Builder(),trim=new Builder(),glass=new Builder(),lights=new Builder(),rear=new Builder(),panels=new Builder();
    private BossVehicleVisual(VehicleProfile profile) {
        this.profile=profile;w=profile.width()/2;y=profile.height()/1.07f;l=profile.length()/2;
    }
    static Node create(AssetManager assets,VehicleProfile profile,int liveryId) {
        if(liveryId<0)throw new IllegalArgumentException("Nonnegative livery required");
        var author=new BossVehicleVisual(profile);
        ColorRGBA color=switch(profile.id()) {
            case "boss_foreman" -> {author.foreman();yield new ColorRGBA(.88f,.53f,.065f,1);}
            case "boss_prefect" -> {author.prefect();yield new ColorRGBA(.055f,.12f,.19f,1);}
            case "boss_emcee" -> {author.emcee();yield new ColorRGBA(.52f,.10f,.60f,1);}
            case "boss_ash_shepherd" -> {author.shepherd();yield new ColorRGBA(.31f,.34f,.29f,1);}
            case "boss_director" -> {author.director();yield new ColorRGBA(.13f,.17f,.24f,1);}
            default -> throw new IllegalArgumentException("Unknown boss silhouette "+profile.id());
        };
        Node root=new Node(profile.id());author.runningGear(root);
        var surfaces=new SurfaceMaterials(assets);
        Material steel=surfaces.material("steel"),rubber=surfaces.rubber();
        author.paint.attach(root,"paint",surfaces.paint(color));
        author.metal.attach(root,"steel",steel);author.trim.attach(root,"rubber-trim",rubber);
        author.glass.attach(root,"glass",SurfaceMaterials.lit(assets,new ColorRGBA(.025f,.09f,.12f,1),110,.25f));
        author.lights.attach(root,"headlights",VehicleVisual.unlit(assets,profile.id().equals("boss_emcee")?new ColorRGBA(1,.66f,.16f,1):new ColorRGBA(.63f,.88f,1,1)));
        author.rear.attach(root,"taillights",VehicleVisual.unlit(assets,new ColorRGBA(1,.11f,.04f,1)));
        author.panels.attach(root,"boss-panels",surfaces.paint(color.mult(.7f)));
        for(int wheel=0;wheel<4;wheel++) {
            Node node=VehicleVisual.wheel(wheel,steel,rubber);
            // The existing axle mesh stays independent; only its tyre size follows
            // the actual four-wheel profile. The chassis itself is never scaled.
            node.setLocalScale(profile.wheelRadius()/.38f);
            node.setLocalTranslation(profile.wheelConnection(wheel).add(0,-profile.suspensionRestLength(),0));
            root.attachChild(node);
        }
        root.setUserData("profileId",profile.id());root.setUserData("livery",liveryId);
        root.setUserData("assetOrigin","original-java-procedural");
        root.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        VehicleDamageVisual.install(assets,root,profile);return root;
    }
    private void foreman() {
        hull(new float[][]{{-1,.84f,-.08f,.29f},{-.84f,.97f,-.1f,.42f},{.56f,.98f,-.1f,.43f},{.94f,.9f,-.08f,.30f}});
        // Low broad blade, raised central cab, and a visibly open cargo bed.
        box(paint,0,.12f,.945f,.98f,.15f,.05f);
        for(int tooth=-3;tooth<=3;tooth++)box(metal,tooth*.27f,-.025f,.985f,.08f,.035f,.015f);
        cabin(-.23f,.23f,.72f,.91f);
        box(paint,0,.28f,-.63f,.89f,.045f,.31f);
        for(int side:new int[]{-1,1}) {
            box(paint,side*.91f,.38f,-.63f,.055f,.11f,.31f);
            for(int rib=0;rib<4;rib++)box(metal,side*.968f,.38f,-.89f+rib*.17f,.012f,.115f,.016f);
            box(metal,side*.70f,.80f,.215f,.10f,.09f,.023f);
            box(lights,side*.70f,.81f,.24f,.075f,.055f,.011f);
            for(int rod=0;rod<3;rod++)box(metal,side*(.12f+rod*.16f),.475f,-.63f,.025f,.022f,.29f);
        }
        box(paint,0,.39f,-.945f,.92f,.10f,.025f);
        box(trim,0,.26f,-.978f,.40f,.10f,.012f);
        for(int stripe=-3;stripe<=3;stripe++)box(trim,stripe*.25f,.16f,.998f,.045f,.09f,.002f);
        box(panels,0,.37f,-.979f,.40f,.055f,.008f);
    }
    private void prefect() {
        hull(new float[][]{{-1,.68f,-.08f,.22f},{-.85f,.92f,-.1f,.39f},{-.40f,.95f,-.1f,.47f},{.4f,.93f,-.1f,.40f},{.85f,.82f,-.08f,.28f},{1,.65f,-.03f,.20f}});
        cabin(-.43f,.22f,.73f,.76f);
        for(int side:new int[]{-1,1}) {
            box(metal,side*.943f,.18f,-.05f,.024f,.035f,.63f);
            box(metal,side*.93f,.38f,-.13f,.02f,.015f,.53f);
            for(int door=0;door<3;door++)box(trim,side*.94f,.27f,-.48f+door*.31f,.012f,.12f,.01f);
            box(lights,side*.40f,.785f,-.08f,.25f,.025f,.027f);
            // Original district crest: an interrupted ring crossed by three roads.
            for(int bar=0;bar<3;bar++)box(metal,side*.966f,.21f+bar*.045f,-.1f,.006f,.012f,.085f-bar*.019f);
            box(metal,side*.57f,.84f,-.31f,.018f,.09f,.009f);
            box(lights,side*.67f,.245f,.975f,.19f,.028f,.012f);
        }
        box(trim,0,.14f,.99f,.39f,.055f,.01f);
        for(int grille=-4;grille<=4;grille++)box(metal,grille*.082f,.15f,1,.012f,.045f,.002f);
        box(panels,.957f,.25f,.38f,.006f,.11f,.18f);
    }
    private void emcee() {
        hull(new float[][]{{-.96f,.72f,-.08f,.22f},{-.66f,.91f,-.1f,.40f},{.56f,.9f,-.1f,.42f},{.87f,.68f,-.03f,.32f}});
        cabin(-.39f,.20f,.62f,.76f);
        // A mechanical speaking trumpet replaces a face: stepped flared metal,
        // dark acoustic centre, and grille ribs below the real weapon barrels.
        loft(metal,new float[][]{{.67f,.22f,.10f,.32f},{.91f,.42f,.035f,.45f},{.97f,.43f,.03f,.46f}});
        metal.cylinderZ(0,.24f*y,.982f*l,.36f*w,.018f*l,18);
        trim.cylinderZ(0,.24f*y,.994f*l,.29f*w,.008f*l,18);
        for(int rib=-2;rib<=2;rib++)box(metal,rib*.10f,.24f,.998f,.02f,.115f,.001f);
        // Asymmetric lighting tower and upright serrated ticket boards.
        box(metal,-.50f,.74f,-.23f,.03f,.21f,.035f);
        box(metal,-.39f,.94f,-.23f,.22f,.025f,.035f);
        for(int spot=0;spot<3;spot++) {
            float x=-.57f+spot*.19f;
            metal.cylinderZ(x*w,.90f*y,-.16f*l,.12f*w,.08f*l,10);
            lights.cylinderZ(x*w,.90f*y,-.116f*l,.088f*w,.006f*l,10);
        }
        box(panels,.57f,.73f,-.22f,.12f,.20f,.025f);
        for(int tooth=0;tooth<4;tooth++)box(panels,.43f+tooth*.085f,.94f,-.22f,.022f,.025f,.025f);
        for(int side:new int[]{-1,1}) {
            box(metal,side*.90f,.37f,-.68f,.025f,.07f,.18f);
            for(int bulb=0;bulb<5;bulb++)box(lights,side*.92f,.40f,-.82f+bulb*.07f,.016f,.022f,.018f);
        }
        box(trim,0,.28f,-.96f,.55f,.12f,.015f);
    }
    private void shepherd() {
        hull(new float[][]{{-1,.82f,-.08f,.41f},{-.87f,.97f,-.1f,.49f},{.43f,.97f,-.1f,.49f},{.81f,.87f,-.08f,.35f},{1,.77f,-.04f,.25f}});
        cabin(-.18f,.22f,.72f,.76f);
        for(int side:new int[]{-1,1}) {
            for(int slab=0;slab<4;slab++) {
                box(panels,side*.975f,.30f,-.75f+slab*.22f,.014f,.15f,.082f);
                box(metal,side*.99f,.32f,-.75f+slab*.22f,.008f,.017f,.058f);
            }
            // Fixed chain links remain inside the width; they do not simulate ropes.
            for(int link=0;link<10;link++) {
                float z=-.80f+link*.08f;
                box(metal,side*.99f,.095f+(link%2)*.018f,z,.007f,.025f,.015f);
            }
            box(metal,side*.65f,.71f,-.35f,.04f,.24f,.035f);
            box(lights,side*.68f,.265f,.968f,.07f,.075f,.009f);
        }
        box(metal,0,.94f,-.35f,.69f,.03f,.042f);
        // A faceted bell suspended in the high rear frame, not a new weapon.
        box(metal,0,.86f,-.35f,.025f,.055f,.025f);
        box(metal,0,.79f,-.35f,.14f,.06f,.065f);
        box(metal,0,.73f,-.35f,.23f,.027f,.11f);
        box(trim,0,.707f,-.35f,.20f,.004f,.09f);
        box(metal,0,.685f,-.35f,.022f,.04f,.022f);
        box(trim,0,.29f,-1,.31f,.115f,.006f);
        for(int bar=-3;bar<=3;bar++)box(metal,bar*.09f,.285f,.993f,.012f,.13f,.006f);
    }
    private void director() {
        hull(new float[][]{{-1,.90f,-.08f,.34f},{-.80f,.98f,-.1f,.47f},{.46f,.99f,-.1f,.45f},{.78f,.94f,-.08f,.37f},{1,.84f,-.05f,.32f}});
        cabin(-.20f,.24f,.75f,.94f);
        // A single short stage is part of the same chassis: no trailer or joints.
        box(paint,0,.47f,-.68f,.95f,.025f,.27f);
        for(int side:new int[]{-1,1}) {
            box(metal,side*.69f,.73f,-.37f,.036f,.22f,.026f);
            box(metal,side*.69f,.94f,-.09f,.036f,.026f,.28f);
            for(int lamp=0;lamp<4;lamp++)box(lights,side*.70f,.89f,-.32f+lamp*.14f,.037f,.027f,.025f);
            // Recessed side screens carry a geometric broadcast test pattern.
            box(trim,side*.986f,.29f,-.52f,.009f,.155f,.25f);
            for(int bar=0;bar<6;bar++)box(lights,side*.998f,.31f,-.72f+bar*.08f,.001f,.10f-(bar%3)*.019f,.024f);
            box(panels,side*.99f,.46f,-.52f,.008f,.026f,.26f);
            box(metal,side*.67f,.21f,.983f,.05f,.23f,.013f);
        }
        box(metal,0,-.015f,.983f,.89f,.04f,.017f);
        box(metal,0,.38f,.981f,.91f,.04f,.019f);
        box(trim,0,.24f,-.988f,.52f,.16f,.008f);
        for(int vent=-4;vent<=4;vent++)box(metal,vent*.1f,.24f,-.998f,.018f,.13f,.002f);
    }
    private void cabin(float back,float front,float width,float roof) {
        loft(paint,new float[][]{{back,width,.43f,.55f},{back+.065f,width*.91f,.43f,roof},{front-.065f,width*.90f,.43f,roof},{front,width,.43f,.55f}});
        glass.quad(point(-width*.81f,.57f,front+.004f),point(width*.81f,.57f,front+.004f),
                point(width*.77f,roof-.045f,front-.062f),point(-width*.77f,roof-.045f,front-.062f));
        for(int side:new int[]{-1,1})box(glass,side*width*.917f,(roof+.56f)/2,(back+front)/2,.003f,(roof-.56f)/2,(front-back)*.32f);
    }
    private void runningGear(Node root) {
        for(int wheel=0;wheel<4;wheel++) {
            Vector3f connection=profile.wheelConnection(wheel);
            float side=wheel%2==0?-1:1;
            metal.box(connection.x*.75f,connection.y-.03f,connection.z,w*.23f,.06f,.08f*l);
            paint.arch(side*(w-.07f),connection.y-profile.suspensionRestLength(),connection.z,profile.wheelRadius()*1.04f,profile.wheelRadius()*1.18f,.05f,14);
        }
        for(int barrel=0;barrel<2;barrel++) {
            Vector3f muzzle=profile.machineGunMuzzle(barrel);float radius=.063f*w/1.05f;
            metal.cylinderZ(muzzle.x,muzzle.y,muzzle.z-.13f*l,.12f*w,.26f*l,10);
            trim.cylinderZ(muzzle.x,muzzle.y,muzzle.z,radius*.68f,.005f,10);
            Node mount=new Node("machine-gun-muzzle-"+barrel);mount.setLocalTranslation(muzzle);root.attachChild(mount);
        }
        Node weapon=new Node("weapon-base");weapon.setLocalTranslation(profile.weaponBase());root.attachChild(weapon);
        Node muzzle=new Node("weapon-muzzle");muzzle.setLocalTranslation(profile.muzzle());root.attachChild(muzzle);
        for(int side:new int[]{-1,1}) {
            float ex=side*.68f*w,ey=-.04f*y,ez=-.99f*l;
            metal.cylinderZ(ex,ey,ez,.085f*w,.09f*l,10);
            trim.cylinderZ(ex,ey,ez-.05f*l,.059f*w,.008f*l,10);
            Node exhaust=new Node("exhaust-"+(side<0?"left":"right"));exhaust.setLocalTranslation(ex,ey,ez-.055f*l);root.attachChild(exhaust);
            box(rear,side*.70f,.15f,-1,.14f,.034f,.006f);
            box(lights,side*.66f,.20f,.945f,.11f,.047f,.014f);
        }
    }
    private void hull(float[][] rings) {loft(paint,rings);}
    private void loft(Builder builder,float[][] rings) {
        float[][] metres=new float[rings.length][4];
        for(int i=0;i<rings.length;i++)metres[i]=new float[]{rings[i][0]*l,rings[i][1]*w,rings[i][2]*y,rings[i][3]*y};
        builder.loft(metres);
    }
    private void box(Builder builder,float x,float height,float z,float hx,float hy,float hz) {
        builder.box(x*w,height*y,z*l,hx*w,hy*y,hz*l);
    }
    private Vector3f point(float x,float height,float z) {return new Vector3f(x*w,height*y,z*l);}
}
