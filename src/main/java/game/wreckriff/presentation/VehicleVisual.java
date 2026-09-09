package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapText;
import jme3tools.optimize.GeometryBatchFactory;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.util.BufferUtils;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleProfile;
import java.util.*;

/** Authored Rivet coupe, built from local parameterized surfaces; +Z front, +X right. */
public final class VehicleVisual {
    private static final ColorRGBA[] PAINT = {
        new ColorRGBA(.85f,.19f,.065f,1), new ColorRGBA(.62f,.54f,.84f,1),
        new ColorRGBA(.92f,.65f,.11f,1), new ColorRGBA(.11f,.69f,.7f,1),
        new ColorRGBA(.35f,.77f,.19f,1)
    };
    private VehicleVisual() {}

    public static Node create(AssetManager assets,VehicleProfile profile,int liveryId) {
        Objects.requireNonNull(profile);
        return profile.id().equals("rivet")?create(assets,liveryId):BossVehicleVisual.create(assets,profile,liveryId);
    }

    /** Profile-sized geometry lives at unit root scale, so wheel poses and sockets share native metres. */
    public static Node create(AssetManager assets, VehicleProfile profile, int livery) {
        Objects.requireNonNull(profile,"Vehicle profile required");
        Node root=profile.id().equals("rivet")?rivet(assets,livery):boss(assets,profile,livery);
        root.setUserData("profileId",profile.id());
        root.setUserData("assetOrigin","original-java-procedural");
        root.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        VehicleDamageVisual.install(assets,root,profile);
        return root;
    }

    private static Node rivet(AssetManager assets, int livery) {
        if (livery<0 || livery>=5) throw new IllegalArgumentException("Rivet livery 0..4 required");
        Node root=new Node("Rivet-"+livery);
        SurfaceMaterials surfaces=new SurfaceMaterials(assets);
        Material paint=surfaces.paint(PAINT[livery]), steel=surfaces.material("steel");
        Material dark=surfaces.rubber();
        Material glass=SurfaceMaterials.lit(assets,new ColorRGBA(.018f,.055f,.085f,1),110,.24f);
        Material markings=SurfaceMaterials.lit(assets,new ColorRGBA(.88f,.84f,.72f,1),15,.08f);
        Material lamps=unlit(assets,new ColorRGBA(1,.77f,.33f,1));
        Material tail=unlit(assets,new ColorRGBA(.95f,.09f,.025f,1));
        Builder body=new Builder(), metal=new Builder(), black=new Builder(), windows=new Builder(), ink=new Builder();
        Builder frontLights=new Builder(), rearLights=new Builder();
        // The long swept bonnet, short raked cab and kicked rear distinguish this from a box chassis.
        body.loft(new float[][] {
            {-2.3f,.79f,-.19f,.19f}, {-1.85f,1.02f,-.21f,.33f},
            {-.9f,1.03f,-.21f,.38f}, {.5f,1.00f,-.21f,.39f},
            {1.65f,.96f,-.19f,.26f}, {2.3f,.79f,-.13f,.14f}
        });
        body.loft(new float[][] {
            {-1.36f,.78f,.31f,.42f}, {-.91f,.67f,.33f,.99f},
            {-.2f,.64f,.33f,1.01f}, {.47f,.77f,.33f,.43f}
        });
        // Windshield and rear glass follow the raked roof, with deliberately thick pillars.
        windows.quad(v(-.70f,.456f,.43f),v(.70f,.456f,.43f),v(.59f,1.018f,-.17f),v(-.59f,1.018f,-.17f));
        windows.quad(v(.72f,.464f,-1.31f),v(-.72f,.464f,-1.31f),v(-.62f,.956f,-.935f),v(.62f,.956f,-.935f));
        for (int side:new int[]{-1,1}) {
            float x=side;
            if(side>0) windows.quad(v(x*.786f,.44f,-1.23f),v(x*.687f,.94f,-.88f),
                    v(x*.66f,.94f,-.24f),v(x*.77f,.455f,.35f));
            else windows.quad(v(x*.77f,.455f,.35f),v(x*.66f,.94f,-.24f),
                    v(x*.687f,.94f,-.88f),v(x*.786f,.44f,-1.23f));
            // Armoured sill and curved wheel-arch lips follow the actual wheel silhouette.
            black.box(x*1.012f,-.03f,-.15f,.038f,.14f,.87f);
            metal.box(x*1.055f,-.09f,-.1f,.035f,.045f,1.93f);
            for (float z:new float[]{-1.4f,1.4f}) {
                body.arch(x*1.06f,-.20f,z,.43f,.56f,.16f,20);
                metal.arch(x*1.22f,-.20f,z,.47f,.51f,.025f,20);
            }
            metal.box(x*.95f,.37f,-.13f,.10f,.025f,.16f); // door handle
            metal.box(x*.83f,.50f,.32f,.16f,.055f,.09f); // bracket mirror
            // Front weapon brackets are exposed and mechanically distinct from lamps.
            metal.box(x*.53f,.37f,1.45f,.16f,.07f,.43f);
            black.box(x*.53f,.47f,1.65f,.095f,.095f,.43f);
            metal.cylinderZ(x*.53f,.47f,2.105f,.063f,.34f,10);
            black.cylinderZ(x*.53f,.47f,2.27f,.041f,.013f,10);
            frontLights.box(x*.61f,.16f,2.261f,.145f,.058f,.012f);
            rearLights.box(x*.6f,.19f,-2.25f,.15f,.042f,.023f);
            // Twin low exhausts, rear-facing, for the turbo effect anchors.
            metal.cylinderZ(x*.72f,-.11f,-2.33f,.09f,.14f,10);
            black.cylinderZ(x*.72f,-.11f,-2.48f,.067f,.008f,10);
            Node exhaust=new Node("exhaust-"+(side<0?"left":"right"));
            exhaust.setLocalTranslation(x*.72f,-.11f,-2.49f); root.attachChild(exhaust);
        }
        metal.bumper(2.32f,.072f);
        metal.bumper(-2.32f,.075f);
        black.box(0,.065f,2.305f,.27f,.055f,.015f);
        for (int i=-3;i<=3;i++) metal.box(i*.071f,.065f,2.324f,.01f,.05f,.008f);
        // Rear amplifier pack: two speakers in a braced cabinet, cooling fins and a carrying rail.
        black.box(0,.52f,-1.79f,.55f,.22f,.27f);
        metal.box(0,.76f,-1.80f,.58f,.025f,.29f);
        for (float x:new float[]{-.27f,.27f}) {
            metal.cylinderZ(x,.53f,-2.075f,.16f,.014f,14);
            black.cylinderZ(x,.53f,-2.096f,.12f,.016f,14);
            metal.cylinderZ(x,.53f,-2.115f,.043f,.008f,10);
        }
        for (int i=-3;i<=3;i++) metal.box(i*.115f,.535f,-1.508f,.019f,.145f,.015f);
        // Fastener heads, bonnet vent slats and scratches use the shared metal batch.
        for (float x:new float[]{-.81f,.81f}) for (float z:new float[]{-.72f,.65f,1.20f})
            metal.box(x,.405f-(z>0?z*.065f:0),z,.028f,.014f,.028f);
        for (int i=0;i<5;i++) black.box(0,.392f-i*.008f,.61f+i*.12f,.25f,.012f,.027f);
        cosmeticKit(body,metal,black,livery);
        pattern(ink,livery);
        body.attach(root,"paint",paint); metal.attach(root,"steel",steel); black.attach(root,"rubber-trim",dark);
        windows.attach(root,"glass",glass); ink.attach(root,"livery-markings",markings);
        frontLights.attach(root,"headlights",lamps); rearLights.attach(root,"taillights",tail);
        for (int wheel=0;wheel<4;wheel++) {
            Node node=wheel(wheel,steel,dark);
            node.setLocalTranslation(wheel%2==0?-1.06f:1.06f,-.20f,wheel<2?1.4f:-1.4f);
            root.attachChild(node);
        }
        addNumbers(root,assets,livery);
        root.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        root.setUserData("livery",livery); root.setUserData("assetOrigin","original-java-procedural");
        anchors(root,VehicleProfile.rivet());
        return root;
    }

    private static void cosmeticKit(Builder paint,Builder steel,Builder rubber,int livery) {
        // Five physical kits share the existing tyre/body envelope; each changes the silhouette from above.
        switch(livery) {
            case 0 -> {for(float x:new float[]{-.73f,.73f})steel.box(x,.62f,-1.65f,.035f,.27f,.035f);}
            case 1 -> {
                steel.box(0,.01f,2.35f,.93f,.15f,.065f);
                for(float x:new float[]{-.7f,0,.7f})steel.box(x,.02f,2.39f,.045f,.18f,.025f);
                paint.box(0,.30f,1.24f,.40f,.075f,.39f);
            }
            case 2 -> {
                for(float side:new float[]{-1,1}) {
                    steel.box(side*.61f,.235f,2.255f,.19f,.025f,.075f);
                    steel.box(side*.61f,.085f,2.255f,.19f,.022f,.075f);
                    steel.box(side*.80f,.16f,2.255f,.022f,.068f,.075f);
                    rubber.box(side*.46f,.73f,-.66f,.09f,.21f,.26f);
                }
            }
            case 3 -> {
                for(float side:new float[]{-1,1})for(float z:new float[]{-1.4f,1.4f}) {
                    paint.box(side*1.03f,.32f,z,.20f,.055f,.40f);
                    steel.box(side*1.04f,.38f,z,.16f,.018f,.31f);
                }
            }
            case 4 -> {
                for(float side:new float[]{-1,1}) {
                    steel.box(side*.82f,.64f,-1.75f,.04f,.29f,.04f);
                    steel.box(side*.82f,.46f,-1.54f,.04f,.10f,.25f);
                }
                steel.box(0,.935f,-1.75f,.86f,.035f,.07f);
                paint.box(0,.94f,-1.73f,.71f,.025f,.20f);
            }
            default -> throw new IllegalArgumentException("Rivet livery 0..4 required");
        }
    }

    private static void anchors(Node root,VehicleProfile profile) {
        for(int barrel=0;barrel<2;barrel++) {
            Node anchor=new Node("machine-gun-muzzle-"+barrel);anchor.setLocalTranslation(profile.machineGunMuzzle(barrel));root.attachChild(anchor);
        }
        Node muzzle=new Node("weapon-muzzle"),base=new Node("weapon-base");
        muzzle.setLocalTranslation(profile.muzzle());base.setLocalTranslation(profile.weaponBase());root.attachChild(muzzle);root.attachChild(base);
    }

    /** Pure presentation: phase comes from the match, vulnerability only from a real gameplay window. */
    public static void updateBossPhase(Node vehicle,int phase,boolean vulnerable) {
        if(phase<0||phase>2)throw new IllegalArgumentException("Boss phase 0..2 required");
        if(vehicle.getChild("phase-armor")==null)return;
        vehicle.getChild("phase-armor").setCullHint(phase==2?Spatial.CullHint.Always:Spatial.CullHint.Inherit);
        vehicle.getChild("service-cover").setCullHint(vulnerable?Spatial.CullHint.Always:Spatial.CullHint.Inherit);
        vehicle.getChild("service-core").setCullHint(vulnerable?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        Geometry screens=(Geometry)vehicle.getChild("phase-screens");
        if(screens!=null) {
            ColorRGBA color=phase==0?new ColorRGBA(.13f,.64f,.80f,1):phase==1?new ColorRGBA(1,.50f,.10f,1):new ColorRGBA(.9f,.12f,.045f,1);
            screens.getMaterial().setColor("Color",color);screens.getMaterial().setColor("GlowColor",color.mult(.35f));
        }
        vehicle.setUserData("bossVisualPhase",phase);vehicle.setUserData("servicePanelOpen",vulnerable);
    }

    private static Node boss(AssetManager assets,VehicleProfile profile,int livery) {
        Node root=new Node(profile.id());SurfaceMaterials surfaces=new SurfaceMaterials(assets);
        float w=profile.width()*.5f,l=profile.length()*.5f,h=profile.hullBounds().maxY();
        Builder paint=new Builder(),steel=new Builder(),rubber=new Builder(),glass=new Builder(),marks=new Builder();
        Builder lamps=new Builder(),tails=new Builder(),armor=new Builder(),screens=new Builder();
        ColorRGBA color;
        // The axle/chassis structure is shared. Upper bodies are independently authored, never scaled Rivets.
        steel.box(0,.06f*h,0,w*.65f,h*.09f,l*.94f);
        rubber.box(0,.17f*h,0,w*.80f,h*.075f,l*.82f);
        switch(profile.id()) {
            case "boss_foreman" -> {
                color=new ColorRGBA(.89f,.55f,.10f,1);
                // Broad stepped blade, high square working cab and an open, ribbed dump bed.
                paint.loft(new float[][]{{l*.70f,w*.79f,.12f*h,.32f*h},{l*.97f,w,.025f*h,.29f*h}});
                steel.box(0,.025f*h,l*.97f,w,h*.028f,l*.025f);
                paint.box(0,.56f*h,l*.24f,w*.71f,h*.29f,l*.20f);
                glass.box(0,.68f*h,l*.445f,w*.61f,h*.145f,l*.005f);
                glass.box(w*.715f,.68f*h,l*.24f,w*.006f,h*.14f,l*.15f);
                glass.box(-w*.715f,.68f*h,l*.24f,w*.006f,h*.14f,l*.15f);
                steel.box(0,.865f*h,l*.24f,w*.80f,h*.023f,l*.25f);
                paint.box(0,.31f*h,-l*.49f,w*.89f,h*.045f,l*.43f);
                for(float side:new float[]{-1,1}) {
                    paint.box(side*w*.90f,.52f*h,-l*.49f,w*.072f,h*.20f,l*.43f);
                    for(int rib=0;rib<5;rib++)steel.box(side*w*.979f,.53f*h,-l*(.12f+rib*.17f),w*.018f,h*.20f,l*.022f);
                    lamps.box(side*w*.55f,.887f*h,l*.475f,w*.15f,h*.034f,l*.020f);
                }
                armor.box(0,.5f*h,-l*.935f,w*.89f,h*.19f,l*.04f);
                for(int stripe=-3;stripe<=3;stripe++)marks.box(stripe*w*.25f,.19f*h,l*.999f,w*.055f,h*.085f,l*.002f);
            }
            case "boss_prefect" -> {
                color=new ColorRGBA(.12f,.20f,.27f,1);
                paint.loft(new float[][]{{-l,w*.84f,.01f*h,.27f*h},{-l*.78f,w*.95f,-.02f*h,.36f*h},
                        {l*.78f,w*.95f,-.02f*h,.33f*h},{l,w*.82f,.01f*h,.23f*h}});
                paint.loft(new float[][]{{-l*.61f,w*.73f,.32f*h,.40f*h},{-l*.42f,w*.70f,.32f*h,.67f*h},
                        {l*.23f,w*.70f,.32f*h,.67f*h},{l*.44f,w*.76f,.32f*h,.35f*h}});
                glass.quad(v(-w*.65f,.37f*h,l*.42f),v(w*.65f,.37f*h,l*.42f),v(w*.62f,.65f*h,l*.23f),v(-w*.62f,.65f*h,l*.23f));
                for(float side:new float[]{-1,1}) {
                    for(int window=0;window<3;window++)glass.box(side*w*.706f,.51f*h,-l*.32f+window*l*.21f,w*.004f,h*.115f,l*.083f);
                    steel.box(side*w*.95f,.37f*h,0,w*.025f,h*.018f,l*.80f);
                    lamps.box(side*w*.59f,.22f*h,l*.975f,w*.25f,h*.028f,l*.025f);
                    screens.box(side*w*.24f,.697f*h,-l*.02f,w*.20f,h*.035f,l*.11f);
                    armor.box(side*w*.96f,.19f*h,-l*.03f,w*.018f,h*.12f,l*.38f);
                }
                marks.box(0,.348f*h,l*.67f,w*.18f,h*.004f,l*.15f);
            }
            case "boss_emcee" -> {
                color=new ColorRGBA(.62f,.19f,.34f,1);
                paint.box(0,.35f*h,0,w*.79f,h*.15f,l*.71f);
                paint.loft(new float[][]{{-l*.45f,w*.64f,.44f*h,.63f*h},{-l*.25f,w*.61f,.44f*h,.83f*h},
                        {l*.15f,w*.61f,.44f*h,.83f*h},{l*.43f,w*.68f,.44f*h,.48f*h}});
                glass.quad(v(-w*.56f,.50f*h,l*.415f),v(w*.56f,.50f*h,l*.415f),v(w*.52f,.80f*h,l*.15f),v(-w*.52f,.80f*h,l*.15f));
                // Flared mechanical loudhailer grille, visibly wider at its front lip.
                steel.cylinderZ(0,.37f*h,l*.82f,w*.37f,l*.23f,10);
                rubber.cylinderZ(0,.37f*h,l*.94f,w*.29f,l*.012f,10);
                steel.cylinderZ(0,.37f*h,l*.96f,w*.12f,l*.012f,10);
                for(float side:new float[]{-1,1}) {
                    steel.box(side*w*.67f,.73f*h,-l*.50f,w*.035f,h*.24f,l*.028f);
                    lamps.box(side*w*.54f,.94f*h,-l*.50f,w*.14f,h*.037f,l*.06f);
                    paint.box(side*w*.82f,.28f*h,l*.30f,w*.08f,h*.07f,l*.49f);
                    armor.box(side*w*.80f,.42f*h,-l*.48f,w*.035f,h*.19f,l*.18f);
                }
                steel.box(-w*.30f,.87f*h,-l*.57f,w*.32f,h*.10f,l*.08f);
                screens.box(-w*.30f,.87f*h,-l*.655f,w*.28f,h*.08f,l*.006f);
                for(int i=-2;i<=2;i++)marks.box(i*w*.16f,.51f*h,l*.63f,w*.045f,h*.055f,l*.008f);
            }
            case "boss_ash_shepherd" -> {
                color=new ColorRGBA(.32f,.34f,.31f,1);
                paint.box(0,.46f*h,-l*.22f,w*.85f,h*.25f,l*.70f);
                paint.box(0,.34f*h,l*.64f,w*.75f,h*.17f,l*.30f);
                paint.loft(new float[][]{{l*.15f,w*.71f,.50f*h,.67f*h},{l*.31f,w*.65f,.50f*h,.84f*h},
                        {l*.55f,w*.65f,.50f*h,.84f*h},{l*.76f,w*.71f,.50f*h,.52f*h}});
                glass.quad(v(-w*.58f,.54f*h,l*.744f),v(w*.58f,.54f*h,l*.744f),v(w*.55f,.80f*h,l*.55f),v(-w*.55f,.80f*h,l*.55f));
                for(float side:new float[]{-1,1}) {
                    steel.box(side*w*.66f,.78f*h,-l*.67f,w*.028f,h*.205f,l*.028f);
                    for(int panel=0;panel<4;panel++)armor.box(side*w*.90f,.44f*h,-l*.73f+panel*l*.28f,w*.045f,h*.20f,l*.105f);
                    lamps.box(side*w*.60f,.34f*h,l*.957f,w*.045f,h*.12f,l*.007f);
                    // Chain rhythm is cut into the existing side silhouette, with no dangling collision traps.
                    for(int chain=0;chain<8;chain++)steel.box(side*w*.953f,.38f*h+(chain%2)*.025f*h,-l*.81f+chain*l*.13f,w*.014f,h*.022f,l*.03f);
                }
                steel.box(0,.982f*h,-l*.67f,w*.69f,h*.017f,l*.034f);
                steel.cylinderZ(0,.82f*h,-l*.67f,w*.18f,l*.13f,10);
                marks.box(0,.725f*h,-l*.20f,w*.095f,h*.006f,l*.48f);
                marks.box(0,.728f*h,-l*.20f,w*.53f,h*.006f,l*.035f);
            }
            case "boss_director" -> {
                color=new ColorRGBA(.25f,.27f,.32f,1);
                paint.box(0,.39f*h,l*.52f,w*.83f,h*.22f,l*.34f);
                paint.box(0,.65f*h,l*.30f,w*.75f,h*.20f,l*.22f);
                glass.box(0,.70f*h,l*.527f,w*.64f,h*.12f,l*.004f);
                steel.box(0,.25f*h,l*.94f,w*.94f,h*.035f,l*.035f);
                for(int i=-4;i<=4;i++)steel.box(i*w*.135f,.38f*h,l*.87f,w*.022f,h*.105f,l*.017f);
                paint.box(0,.25f*h,-l*.51f,w*.89f,h*.055f,l*.46f);
                for(float side:new float[]{-1,1}) {
                    armor.box(side*w*.91f,.52f*h,-l*.46f,w*.052f,h*.21f,l*.39f);
                    rubber.box(side*w*.88f,.53f*h,-l*.46f,w*.025f,h*.19f,l*.36f);
                    screens.box(side*w*.94f,.54f*h,-l*.46f,w*.008f,h*.145f,l*.30f);
                    steel.box(side*w*.77f,.63f*h,-l*.72f,w*.026f,h*.34f,l*.025f);
                    lamps.box(side*w*.68f,.953f*h,-l*.72f,w*.13f,h*.028f,l*.045f);
                    lamps.box(side*w*.70f,.31f*h,l*.914f,w*.14f,h*.032f,l*.012f);
                }
                steel.box(0,.965f*h,-l*.72f,w*.79f,h*.020f,l*.028f);
                marks.box(0,.871f*h,l*.30f,w*.45f,h*.005f,l*.13f);
            }
            default -> throw new IllegalArgumentException("Unknown visual profile "+profile.id());
        }
        for(int barrel=0;barrel<2;barrel++) {
            Vector3f muzzle=profile.machineGunMuzzle(barrel);
            steel.cylinderZ(muzzle.x,muzzle.y,muzzle.z-.30f,.085f,.58f,10);
            rubber.cylinderZ(muzzle.x,muzzle.y,muzzle.z-.005f,.057f,.01f,10);
        }
        Vector3f weapon=profile.muzzle();steel.cylinderZ(weapon.x,weapon.y,weapon.z-.47f,.17f,.90f,12);
        rubber.cylinderZ(weapon.x,weapon.y,weapon.z-.012f,.118f,.018f,12);
        for(int side:new int[]{-1,1}) {
            tails.box(side*w*.63f,.25f*h,-l*.975f,w*.095f,h*.03f,l*.014f);
            Node exhaust=new Node("exhaust-"+(side<0?"left":"right"));
            exhaust.setLocalTranslation(side*w*.68f,.04f*h,profile.fullBounds().minZ()+.03f);root.attachChild(exhaust);
            steel.cylinderZ(exhaust.getLocalTranslation().x,exhaust.getLocalTranslation().y,exhaust.getLocalTranslation().z+.12f,.12f,.22f,10);
        }
        paint.attach(root,"paint",surfaces.paint(color));steel.attach(root,"steel",surfaces.material("steel"));
        rubber.attach(root,"rubber-trim",surfaces.rubber());glass.attach(root,"glass",SurfaceMaterials.lit(assets,new ColorRGBA(.018f,.055f,.075f,1),100,.32f));
        marks.attach(root,"livery-markings",SurfaceMaterials.lit(assets,new ColorRGBA(.82f,.77f,.59f,1),10,.12f));
        lamps.attach(root,"headlights",unlit(assets,new ColorRGBA(1,.72f,.34f,1)));tails.attach(root,"taillights",unlit(assets,new ColorRGBA(.86f,.09f,.025f,1)));
        armor.attach(root,"phase-armor",surfaces.paint(color.mult(.72f)));
        screens.attach(root,"phase-screens",unlit(assets,new ColorRGBA(.13f,.64f,.80f,1)));
        // Visible service cover occupies the same local face as the future authoritative weakpoint.
        Builder cover=new Builder(),core=new Builder();boolean sidePanel=profile.id().equals("boss_prefect");
        if(sidePanel) {
            cover.box(w*.985f,.22f*h,0,w*.01f,h*.105f,l*.22f);
            core.box(w*.985f,.22f*h,0,w*.008f,h*.085f,l*.19f);
        } else {
            cover.box(0,.35f*h,-l*.978f,w*.34f,h*.14f,l*.015f);
            core.box(0,.35f*h,-l*.980f,w*.30f,h*.11f,l*.01f);
        }
        cover.attach(root,"service-cover",surfaces.material("steel"));core.attach(root,"service-core",unlit(assets,new ColorRGBA(.12f,.9f,.83f,1)));
        root.getChild("service-core").setCullHint(Spatial.CullHint.Always);
        for(int i=0;i<4;i++) {
            Node wheel=wheel(i,surfaces.material("steel"),surfaces.rubber());
            float scale=profile.wheelRadius()/.38f;wheel.setLocalScale(scale);
            wheel.setLocalTranslation(profile.wheelConnection(i).add(0,-profile.suspensionRestLength(),0));root.attachChild(wheel);
        }
        anchors(root,profile);root.setUserData("livery",livery);root.setUserData("bossVisualPhase",0);
        return root;
    }

    public static void updateDamage(Node vehicle,float hpFraction) {vehicle.getControl(VehicleDamageVisual.class).damage(hpFraction);}
    public static void updateEffects(Node vehicle,boolean frozen,boolean shielded) {vehicle.getControl(VehicleDamageVisual.class).effects(frozen,shielded);}

    static Node wheel(int id,Material metal,Material tyre) {
        Node node=new Node("wheel-"+id);
        Builder rubber=new Builder(),steel=new Builder();
        rubber.tyre(.38f,.30f,32,8);
        for (float x:new float[]{-.153f,.153f}) {
            steel.cylinderX(x,0,0,.228f,.016f,12);
            rubber.cylinderX(x*1.07f,0,0,.17f,.01f,12);
            steel.cylinderX(x*1.15f,0,0,.071f,.024f,10);
            for (int spoke=0;spoke<5;spoke++) {
                double a=spoke*Math.PI*2/5;
                // Faceted spokes rotate with the wheel; both outside faces are fully modeled.
                steel.box(x*1.12f,(float)Math.cos(a)*.11f,(float)Math.sin(a)*.11f,.009f,.027f,.027f);
            }
        }
        // Rounded shoulders and recessed tread retain shape even at close range.
        for (int i=0;i<32;i++) {
            double a=i*Math.PI*2/32, b=a+.025;
            rubber.quad(v(-.12f,(float)Math.cos(a)*.388f,(float)Math.sin(a)*.388f),
                    v(.12f,(float)Math.cos(a)*.388f,(float)Math.sin(a)*.388f),
                    v(.12f,(float)Math.cos(b)*.388f,(float)Math.sin(b)*.388f),
                    v(-.12f,(float)Math.cos(b)*.388f,(float)Math.sin(b)*.388f));
        }
        rubber.attach(node,"tyre",tyre); steel.attach(node,"hub",metal);
        return node;
    }
    private static void addNumbers(Node root,AssetManager assets,int livery) {
        Node numbers=new Node("race-numbers");
        for(int face=0;face<3;face++) {
            BitmapText number=new BitmapText(assets.loadFont("fonts/wreck-bold.fnt"));number.setText(Integer.toString(livery));
            number.setSize(face==2?.64f:.43f);number.setColor(new ColorRGBA(.9f,.87f,.74f,1));
            if(face==2) {
                number.rotate(-FastMath.HALF_PI,0,0);number.setLocalTranslation(-number.getLineWidth()*.5f,1.032f,-.72f);
            } else {
                float side=face==0?-1:1;number.rotate(0,side*FastMath.HALF_PI,0);
                number.setLocalTranslation(side*1.044f,.32f,-.10f+side*number.getLineWidth()*.5f);
            }
            number.updateLogicalState(0);number.setQueueBucket(RenderQueue.Bucket.Transparent);
            number.setShadowMode(RenderQueue.ShadowMode.Off);numbers.attachChild(number);
        }
        numbers.updateGeometricState();GeometryBatchFactory.optimize(numbers,false);root.attachChild(numbers);
    }
    private static void pattern(Builder ink,int livery) {
        // Hood patterns remain visibly distinct under colour-vision deficiencies.
        switch(livery) {
            case 0 -> { for(float x:new float[]{-.37f,.37f}) ink.hoodStrip(x,.095f,.61f,2.13f); }
            case 1 -> { for(int i=0;i<5;i++) ink.hoodStrip(-.65f+i*.29f,.06f,1.40f+(i%2)*.15f,2.10f); }
            case 2 -> {
                for(int i=0;i<5;i++) ink.hoodStrip(-.57f+i*.27f,.08f,.92f+i*.12f,1.17f+i*.12f);
                ink.hoodStrip(0,.48f,1.99f,2.10f);
            }
            case 3 -> { for(int i=0;i<4;i++) ink.hoodStrip(0,.19f+i*.12f,.94f+i*.28f,1.04f+i*.28f); }
            case 4 -> {
                for(int row=0;row<4;row++) for(int col=0;col<4;col++) if((row+col)%2==0)
                    ink.hoodStrip(-.48f+col*.32f,.145f,1.2f+row*.21f,1.38f+row*.21f);
            }
            default -> throw new IllegalArgumentException();
        }
    }
    static Material unlit(AssetManager assets,ColorRGBA color) {
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color",color); material.setColor("GlowColor",color.mult(.5f)); return material;
    }
    private static Vector3f v(float x,float y,float z) { return new Vector3f(x,y,z); }

    static final class Builder {
        final List<Float> points=new ArrayList<>(),normals=new ArrayList<>(),uvs=new ArrayList<>();
        void triangle(Vector3f a,Vector3f b,Vector3f c) {
            Vector3f n=b.subtract(a).cross(c.subtract(a)).normalizeLocal();
            for(Vector3f p:List.of(a,b,c)) { Collections.addAll(points,p.x,p.y,p.z); Collections.addAll(normals,n.x,n.y,n.z);
                float[] uv=SurfaceMesh.uv(p,n,1.5f);Collections.addAll(uvs,uv[0],uv[1]); }
        }
        void quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d) { triangle(a,b,c);triangle(a,c,d); }
        void panel(Vector3f a,Vector3f b,Vector3f c,Vector3f d,int across,int along) {
            // Extra local vertices let prepared dents crease a panel instead of only moving its corners.
            for(int u=0;u<across;u++)for(int v=0;v<along;v++) {
                float u0=u/(float)across,u1=(u+1)/(float)across,v0=v/(float)along,v1=(v+1)/(float)along;
                quad(panelPoint(a,b,c,d,u0,v0),panelPoint(a,b,c,d,u1,v0),
                        panelPoint(a,b,c,d,u1,v1),panelPoint(a,b,c,d,u0,v1));
            }
        }
        Vector3f panelPoint(Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u,float v) {
            return new Vector3f().interpolateLocal(new Vector3f().interpolateLocal(a,b,u),new Vector3f().interpolateLocal(d,c,u),v);
        }
        void bumper(float z,float height) {
            // One material draw, but a segmented beam that bends locally without moving the wheels.
            Vector3f a=v(-.81f,-.055f-height,z-.075f),b=v(.81f,-.055f-height,z-.075f),
                    c=v(.81f,-.055f+height,z-.075f),d=v(-.81f,-.055f+height,z-.075f),
                    e=v(-.81f,-.055f-height,z+.075f),f=v(.81f,-.055f-height,z+.075f),
                    g=v(.81f,-.055f+height,z+.075f),h=v(-.81f,-.055f+height,z+.075f);
            panel(a,d,c,b,1,8);panel(e,f,g,h,8,1);quad(a,e,h,d);quad(b,c,g,f);
            panel(d,h,g,c,1,8);panel(a,b,f,e,8,1);
        }
        void box(float x,float y,float z,float hx,float hy,float hz) {
            Vector3f a=v(x-hx,y-hy,z-hz),b=v(x+hx,y-hy,z-hz),c=v(x+hx,y+hy,z-hz),d=v(x-hx,y+hy,z-hz);
            Vector3f e=v(x-hx,y-hy,z+hz),f=v(x+hx,y-hy,z+hz),g=v(x+hx,y+hy,z+hz),h=v(x-hx,y+hy,z+hz);
            quad(a,d,c,b);quad(e,f,g,h);quad(a,e,h,d);quad(b,c,g,f);quad(d,h,g,c);quad(a,b,f,e);
        }
        void loft(float[][] rings) {
            for(int i=0;i<rings.length-1;i++) {
                Vector3f[] a=ring(rings[i]),b=ring(rings[i+1]);
                for(int face=0;face<8;face++) {
                    int across=face==4?6:face==2||face==6?2:1;
                    int along=face==4||face==2||face==6?3:1;
                    panel(a[face],a[(face+1)%8],b[(face+1)%8],b[face],across,along);
                }
            }
            Vector3f[] start=ring(rings[0]),end=ring(rings[rings.length-1]);
            for(int i=1;i<7;i++){triangle(start[0],start[i+1],start[i]);triangle(end[0],end[i],end[i+1]);}
        }
        Vector3f[] ring(float[] r) {
            float bevel=Math.min(.12f,(r[3]-r[2])*.24f),x=r[1],bottom=r[2],top=r[3],z=r[0];
            return new Vector3f[]{v(-x+bevel,bottom,z),v(x-bevel,bottom,z),v(x,bottom+bevel,z),
                    v(x,top-bevel,z),v(x-bevel,top,z),v(-x+bevel,top,z),v(-x,top-bevel,z),v(-x,bottom+bevel,z)};
        }
        void arch(float x,float y,float z,float inner,float outer,float half,int count) {
            for(int i=0;i<count;i++) {
                double a=-.15+i*(Math.PI+.3)/count,b=-.15+(i+1)*(Math.PI+.3)/count;
                for(float side:new float[]{-1,1}) {
                    float face=x+side*half;
                    Vector3f p=v(face,y+(float)Math.sin(a)*inner,z+(float)Math.cos(a)*inner),
                            q=v(face,y+(float)Math.sin(a)*outer,z+(float)Math.cos(a)*outer),
                            r=v(face,y+(float)Math.sin(b)*outer,z+(float)Math.cos(b)*outer),
                            t=v(face,y+(float)Math.sin(b)*inner,z+(float)Math.cos(b)*inner);
                    if(side>0)quad(p,t,r,q);else quad(p,q,r,t);
                }
                quad(v(x-half,y+(float)Math.sin(a)*outer,z+(float)Math.cos(a)*outer),
                        v(x+half,y+(float)Math.sin(a)*outer,z+(float)Math.cos(a)*outer),
                        v(x+half,y+(float)Math.sin(b)*outer,z+(float)Math.cos(b)*outer),
                        v(x-half,y+(float)Math.sin(b)*outer,z+(float)Math.cos(b)*outer));
            }
        }
        void tyre(float radius,float width,int segments,int sections) {
            // Smooth vertex normals follow the rounded axial shoulder profile; UVs unwrap circumference.
            float[][] profile={{-width*.5f,.22f},{-width*.55f,.30f},{-width*.44f,.36f},{-width*.28f,radius},
                    {width*.28f,radius},{width*.44f,.36f},{width*.55f,.30f},{width*.5f,.22f}};
            for(int i=0;i<segments;i++)for(int j=0;j<sections-1;j++) {
                double a=i*Math.PI*2/segments,b=(i+1)*Math.PI*2/segments;
                Vector3f p=radial(0,0,0,profile[j][1],profile[j][0],a,true),q=radial(0,0,0,profile[j][1],profile[j][0],b,true);
                Vector3f r=radial(0,0,0,profile[j+1][1],profile[j+1][0],b,true),t=radial(0,0,0,profile[j+1][1],profile[j+1][0],a,true);
                Vector3f[] vertices={p,q,r,t},normal={tyreNormal(profile,j,a),tyreNormal(profile,j,b),tyreNormal(profile,j+1,b),tyreNormal(profile,j+1,a)};
                float[][] uv={{i*3f/segments,j/(float)(sections-1)},{(i+1)*3f/segments,j/(float)(sections-1)},
                        {(i+1)*3f/segments,(j+1)/(float)(sections-1)},{i*3f/segments,(j+1)/(float)(sections-1)}};
                for(int vertex:new int[]{0,1,2,0,2,3}) {
                    Vector3f point=vertices[vertex],n=normal[vertex];
                    Collections.addAll(points,point.x,point.y,point.z);Collections.addAll(normals,n.x,n.y,n.z);
                    Collections.addAll(uvs,uv[vertex][0],uv[vertex][1]);
                }
            }
        }
        Vector3f tyreNormal(float[][] profile,int section,double angle) {
            float[] before=profile[Math.max(0,section-1)],after=profile[Math.min(profile.length-1,section+1)];
            float nx=before[1]-after[1],radial=after[0]-before[0];
            return v(nx,radial*(float)Math.cos(angle),radial*(float)Math.sin(angle)).normalizeLocal();
        }
        void cylinderZ(float x,float y,float z,float radius,float length,int count) {
            cylinder(x,y,z,radius,length,count,false);
        }
        void cylinderX(float x,float y,float z,float radius,float length,int count) {
            cylinder(x,y,z,radius,length,count,true);
        }
        void cylinder(float x,float y,float z,float radius,float length,int count,boolean alongX) {
            for(int i=0;i<count;i++) {
                double a=i*Math.PI*2/count,b=(i+1)*Math.PI*2/count;
                Vector3f p=radial(x,y,z,radius,-length*.5f,a,alongX),q=radial(x,y,z,radius,-length*.5f,b,alongX);
                Vector3f r=radial(x,y,z,radius,length*.5f,b,alongX),s=radial(x,y,z,radius,length*.5f,a,alongX);
                quad(p,q,r,s);
                triangle(radial(x,y,z,0,-length*.5f,0,alongX),q,p);
                triangle(radial(x,y,z,0,length*.5f,0,alongX),s,r);
            }
        }
        Vector3f radial(float x,float y,float z,float radius,float axis,double angle,boolean alongX) {
            float a=(float)Math.cos(angle)*radius,b=(float)Math.sin(angle)*radius;
            return alongX?v(x+axis,y+a,z+b):v(x+a,y+b,z+axis);
        }
        void hoodStrip(float x,float halfWidth,float near,float far) {
            // Follow the bonnet crease instead of a chord that would cut through the paint.
            if(near<1.65f && far>1.65f) { hoodStrip(x,halfWidth,near,1.65f);hoodStrip(x,halfWidth,1.65f,far);return; }
            float y1=hoodY(near),y2=hoodY(far);
            panel(v(x-halfWidth,y1,near),v(x-halfWidth,y2,far),v(x+halfWidth,y2,far),v(x+halfWidth,y1,near),4,2);
        }
        float hoodY(float z) { return z<1.65f?.39f-(z-.5f)*(.13f/1.15f)+.012f:.26f-(z-1.65f)*(.12f/.65f)+.012f; }
        void attach(Node parent,String name,Material material) {
            if(points.isEmpty())return;
            float[] p=new float[points.size()],n=new float[normals.size()],uv=new float[uvs.size()];
            for(int i=0;i<p.length;i++){p[i]=points.get(i);n[i]=normals.get(i);}
            for(int i=0;i<uv.length;i++)uv[i]=uvs.get(i);
            Mesh mesh=new Mesh(); mesh.setBuffer(VertexBuffer.Type.Position,3,BufferUtils.createFloatBuffer(p));
            mesh.setBuffer(VertexBuffer.Type.Normal,3,BufferUtils.createFloatBuffer(n));
            mesh.setBuffer(VertexBuffer.Type.TexCoord,2,BufferUtils.createFloatBuffer(uv));
            mesh.updateBound();MikktspaceTangentGenerator.generate(mesh);mesh.setStatic();
            Geometry geometry=new Geometry(name,mesh);geometry.setMaterial(material);parent.attachChild(geometry);
        }
    }
}
