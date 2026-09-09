package game.wreckriff.arena;

import com.jme3.asset.AssetManager;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.*;
import com.jme3.util.BufferUtils;
import com.jme3.font.BitmapText;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.material.RenderState;
import com.jme3.util.geom.GeometryBatchFactory;
import game.wreckriff.presentation.SurfaceMaterials;
import game.wreckriff.presentation.SurfaceMesh;
import java.util.*;

/** Original, parameter-driven industrial yard. Every solid uses the rendered geometry. */
public final class ArenaFactory {
    private final AssetManager assets;
    private final SurfaceMaterials materials;
    public ArenaFactory(AssetManager assets) { this.assets=Objects.requireNonNull(assets);this.materials=new SurfaceMaterials(assets); }

    public ArenaContent build(ArenaDefinition definition) {
        Node root=new Node("Dead Air Yard");
        root.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        List<ArenaContent.StaticBody> bodies=new ArrayList<>();
        for (var part:definition.boxes()) {
            Vector3f half=part.size().vector().mult(.5f);
            Geometry visual=new Geometry(part.id(),SurfaceMesh.box(half.x,half.y,half.z,tileSize(part.material())));
            visual.setLocalTranslation(part.center().vector());
            visual.setMaterial(material(part.material())); root.attachChild(visual);
            if (part.collision()) bodies.add(new ArenaContent.StaticBody(part.id(),new BoxCollisionShape(half),
                    part.center().vector(),new Quaternion()));
        }
        for (var ramp:definition.ramps()) {
            Mesh mesh=rampMesh(ramp);
            Geometry visual=new Geometry(ramp.id(),mesh); visual.setMaterial(material(ramp.material()));
            root.attachChild(visual);
            // The same exact top vertices provide both ramp contacts and visible seam.
            bodies.add(new ArenaContent.StaticBody(ramp.id(),new MeshCollisionShape(mesh),new Vector3f(),new Quaternion()));
        }
        addDecoration(root,definition);
        root.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry &&
                geometry.getMaterial().getParam("NormalMap")!=null && geometry.getMesh().getBuffer(VertexBuffer.Type.Tangent)==null)
            com.jme3.util.mikktspace.MikktspaceTangentGenerator.generate(geometry.getMesh());});
        return new ArenaContent(root,bodies,definition.spawns(),definition.pickups(),new NavGraph(definition));
    }
    public Material material(String name) {return materials.material(name);}
    private static float tileSize(String name) {
        return switch(name){case "asphalt","concrete" -> 4;case "rust","blue" -> 3;default -> 2;};
    }
    private static Mesh rampMesh(ArenaDefinition.Ramp ramp) {
        Vector3f[] v={new Vector3f(ramp.minX(),ramp.startY(),ramp.minZ()),
                new Vector3f(ramp.maxX(),ramp.startY(),ramp.minZ()),
                new Vector3f(ramp.maxX(),ramp.endY(),ramp.maxZ()),
                new Vector3f(ramp.minX(),ramp.endY(),ramp.maxZ()),
                new Vector3f(ramp.minX(),-.5f,ramp.minZ()),new Vector3f(ramp.maxX(),-.5f,ramp.minZ()),
                new Vector3f(ramp.maxX(),-.5f,ramp.maxZ()),new Vector3f(ramp.minX(),-.5f,ramp.maxZ())};
        int[] faces={0,3,2,0,2,1, 4,5,6,4,6,7, 0,1,5,0,5,4,
                1,2,6,1,6,5, 2,3,7,2,7,6, 3,0,4,3,4,7};
        List<Vector3f> triangles=new ArrayList<>();for(int vertex:faces)triangles.add(v[vertex]);
        return SurfaceMesh.triangles(triangles,tileSize(ramp.material()));
    }
    private void addDecoration(Node root,ArenaDefinition definition) {
        Node structure=new Node("authored-industrial-details");
        // Static details are outside the driving volume or flush on existing solid faces.
        addIndustrialDetails(structure);
        addBackdrop(root,structure);
        // Markings have no collision: these thin surfaces cannot turn into invisible curbs.
        for (int z=-60;z<=60;z+=12) {
            box(root,"west-lane-"+z,new Vector3f(-64,.008f,z),new Vector3f(.16f,.008f,2.2f),"ivory");
            box(root,"east-lane-"+z,new Vector3f(74,.008f,z),new Vector3f(.16f,.008f,2.2f),"ivory");
        }
        for (int x=-70;x<=70;x+=14) {
            box(root,"south-lane-"+x,new Vector3f(x,.008f,-59),new Vector3f(2.2f,.008f,.16f),"yellow");
            box(root,"north-lane-"+x,new Vector3f(x,.008f,59),new Vector3f(2.2f,.008f,.16f),"yellow");
        }
        var hazard=definition.hazard();
        box(root,"hazard-surface",new Vector3f((hazard.minX()+hazard.maxX())*.5f,.012f,
                (hazard.minZ()+hazard.maxZ())*.5f),new Vector3f((hazard.maxX()-hazard.minX())*.5f,.012f,
                (hazard.maxZ()-hazard.minZ())*.5f),"black");
        for (float x=hazard.minX()+1;x<hazard.maxX();x+=2) box(root,"hazard-stripe",new Vector3f(x,.03f,
                (hazard.minZ()+hazard.maxZ())*.5f),new Vector3f(.32f,.02f,(hazard.maxZ()-hazard.minZ())*.5f-.2f),"yellow");
        GeometryBatchFactory.optimize(structure,false);root.attachChild(structure);
        addSigns(root);
        for (var pickup:definition.pickups()) {
            Geometry stand=new Geometry("stand-"+pickup.id(),new Cylinder(2,16,1.2f,.12f,true));
            stand.rotate(FastMath.HALF_PI,0,0); stand.setLocalTranslation(pickup.position().vector().add(0,.06f,0));
            stand.setMaterial(material("steel")); root.attachChild(stand);
            Node item=new Node("pickup-"+pickup.id());item.setLocalTranslation(pickup.position().vector().add(0,1.1f,0));
            switch (pickup.type()) {
                case REPAIR -> {
                    box(item,"cross-x",new Vector3f(),new Vector3f(.65f,.19f,.19f),"repair");
                    box(item,"cross-y",new Vector3f(),new Vector3f(.19f,.65f,.19f),"repair");
                    box(item,"cross-z",new Vector3f(),new Vector3f(.19f,.19f,.65f),"repair");
                }
                case HOMING_AMMO -> {
                    pickupRocket(item,-.34f,.17f,.9f,"yellow");pickupRocket(item,.34f,.17f,.9f,"yellow");
                }
                case POWER_AMMO -> pickupRocket(item,0,.31f,1.2f,"red");
                case MINE_AMMO -> {
                    cylinder(item,"mine-magazine",new Vector3f(),.55f,.24f,"steel");
                    cylinder(item,"mine-trigger",new Vector3f(0,.2f,0),.2f,.18f,"red");
                }
                case NAPALM_AMMO -> {
                    cylinder(item,"napalm-canister",new Vector3f(),.38f,1.15f,"yellow");
                    cylinder(item,"canister-lid",new Vector3f(0,.64f,0),.22f,.13f,"red");
                    box(item,"napalm-band",new Vector3f(0,0,-.38f),new Vector3f(.24f,.14f,.022f),"red");
                }
                case TURBO_CELL -> {
                    Geometry cell=new Geometry("energy-cell",new Cylinder(2,10,.46f,1.0f,true));
                    cell.rotate(FastMath.HALF_PI,0,0);cell.setMaterial(material("cyan"));item.attachChild(cell);
                    box(item,"terminal",new Vector3f(0,.65f,0),new Vector3f(.22f,.15f,.22f),"ivory");
                    box(item,"charge-mark",new Vector3f(0,0,-.47f),new Vector3f(.30f,.08f,.035f),"black");
                    box(item,"charge-plus",new Vector3f(0,0,-.48f),new Vector3f(.08f,.30f,.035f),"black");
                }
            }
            root.attachChild(item);
        }
    }
    private void pickupRocket(Node root,float x,float radius,float height,String color) {
        Geometry body=new Geometry("ammo-shell",new Cylinder(2,8,radius,height,true));
        body.rotate(FastMath.HALF_PI,0,0);body.setLocalTranslation(x,0,0);body.setMaterial(material(color));root.attachChild(body);
        Geometry nose=new Geometry("ammo-nose",new Sphere(5,8,radius));
        nose.setLocalTranslation(x,height*.5f,0);nose.setLocalScale(1,1.7f,1);nose.setMaterial(material("ivory"));root.attachChild(nose);
        box(root,"ammo-fin-x",new Vector3f(x,-height*.4f,0),new Vector3f(radius*1.7f,.16f,.05f),"steel");
        box(root,"ammo-fin-z",new Vector3f(x,-height*.4f,0),new Vector3f(.05f,.16f,radius*1.7f),"steel");
    }
    private void box(Node root,String id,Vector3f position,Vector3f half,String material) {
        Geometry visual=new Geometry(id,SurfaceMesh.box(half.x,half.y,half.z,tileSize(material)));
        visual.setLocalTranslation(position); visual.setMaterial(material(material)); root.attachChild(visual);
    }

    private void addIndustrialDetails(Node root) {
        // Retaining walls read as concrete footings with steel coping and regular expansion seams.
        for(int x=-76;x<=76;x+=8)for(float z:new float[]{-69.46f,69.46f}) {
            box(root,"wall-buttress",new Vector3f(x,1.5f,z),new Vector3f(.18f,1.5f,.08f),"steel");
            box(root,"wall-coping",new Vector3f(x,3.04f,z),new Vector3f(3.94f,.075f,.18f),"steel");
        }
        for(int z=-64;z<=64;z+=8)for(float x:new float[]{-79.46f,79.46f}) {
            box(root,"wall-buttress",new Vector3f(x,1.5f,z),new Vector3f(.08f,1.5f,.18f),"steel");
            box(root,"wall-coping",new Vector3f(x,3.04f,z),new Vector3f(.18f,.075f,3.94f),"steel");
        }
        // Concrete broadcast bunker: framed service doors, dark inset vents and raised panel joints.
        for(float x:new float[]{-8.02f,8.02f})for(int z=-6;z<=6;z+=3) {
            box(root,"bunker-rib",new Vector3f(x,5.7f,z),new Vector3f(.045f,5.6f,.055f),"rust");
            box(root,"bunker-vent",new Vector3f(x,8.3f,z),new Vector3f(.06f,.9f,.78f),"black");
            for(int l=0;l<6;l++)box(root,"vent-louver",new Vector3f(x*1.004f,7.7f+l*.24f,z),new Vector3f(.07f,.035f,.76f),"steel");
        }
        for(float z:new float[]{-8.025f,8.025f}) {
            box(root,"bunker-door",new Vector3f(-3,2.3f,z),new Vector3f(1.6f,2.3f,.035f),"blue");
            box(root,"door-jamb",new Vector3f(-4.65f,2.4f,z),new Vector3f(.065f,2.4f,.10f),"steel");
            box(root,"door-jamb",new Vector3f(-1.35f,2.4f,z),new Vector3f(.065f,2.4f,.10f),"steel");
            box(root,"door-header",new Vector3f(-3,4.8f,z),new Vector3f(1.7f,.065f,.10f),"steel");
            for(int row=0;row<9;row++)box(root,"door-roller",new Vector3f(-3,.35f+row*.5f,z*1.009f),new Vector3f(1.52f,.028f,.03f),"black");
            box(root,"bunker-plinth",new Vector3f(0,.4f,z),new Vector3f(7.95f,.38f,.055f),"rust");
            box(root,"bunker-cornice",new Vector3f(0,11.7f,z),new Vector3f(8.12f,.16f,.16f),"steel");
        }
        // Four tapered lattice legs with cross-bracing instead of stacked cuboids.
        for(int level=0;level<7;level++) {
            float bottom=12+level*3.8f,top=bottom+3.8f,lower=3.65f-level*.32f,upper=lower-.32f;
            for(int side:new int[]{-1,1})for(int edge:new int[]{-1,1}) {
                beam(root,"mast-leg",new Vector3f(side*lower,bottom,edge*lower),new Vector3f(side*upper,top,edge*upper),.16f,"rust");
                beam(root,"mast-x-brace",new Vector3f(side*lower,bottom,-lower),new Vector3f(side*upper,top,upper),.055f,"steel");
                beam(root,"mast-z-brace",new Vector3f(-lower,bottom,edge*lower),new Vector3f(upper,top,edge*upper),.055f,"steel");
                beam(root,"mast-crossbar",new Vector3f(-upper,top,edge*upper),new Vector3f(upper,top,edge*upper),.07f,"steel");
            }
        }
        beam(root,"broadcast-antenna",new Vector3f(0,37,0),new Vector3f(0,44,0),.07f,"steel");
        for(int level=0;level<4;level++)beam(root,"antenna-dipole",new Vector3f(-1.5f,38+level*1.4f,0),new Vector3f(1.5f,38+level*1.4f,0),.045f,"ivory");
        // Garage has long clerestory windows, sliding-door tracks, steel lintels, downpipes and ribbed cladding.
        for(float z:new float[]{9.60f,10.4f,33.6f,34.4f}) {
            for(int x=-58;x<=-30;x+=4) {
                box(root,"garage-window",new Vector3f(x,3.45f,z),new Vector3f(1.46f,.63f,.027f),"black");
                box(root,"window-divider",new Vector3f(x,3.45f,z+(z<22?-.04f:.04f)),new Vector3f(.035f,.65f,.025f),"steel");
                for(float y:new float[]{2.78f,4.12f})box(root,"window-frame",new Vector3f(x,y,z),new Vector3f(1.53f,.045f,.04f),"steel");
            }
            for(float x=-59;x<=-29;x+=.75f)box(root,"wall-rib",new Vector3f(x,1.3f,z),new Vector3f(.022f,1.3f,.045f),"steel");
            box(root,"garage-roof-edge",new Vector3f(-44,5.27f,z),new Vector3f(16.15f,.18f,.16f),"steel");
        }
        for(float x:new float[]{-60.06f,-27.94f}) {
            box(root,"garage-entry-lintel",new Vector3f(x,4.93f,22),new Vector3f(.13f,.22f,12),"steel");
            for(float z:new float[]{12,32}) {
                beam(root,"garage-downpipe",new Vector3f(x,5.05f,z),new Vector3f(x,.2f,z),.065f,"steel");
                for(float y=.25f;y<4.6f;y+=.8f)box(root,"post-warning-band",new Vector3f(x,y,z),new Vector3f(.02f,.10f,1.93f),"black");
            }
            for(float z=14.4f;z<30;z+=.45f)box(root,"rolled-door-above-opening",new Vector3f(x,5.12f,z),new Vector3f(.17f,.10f,.18f),"blue");
        }
        for(int x=-56;x<=-32;x+=8) {
            cylinder(root,"roof-extractor",new Vector3f(x,6.2f,22),.8f,1.5f,"steel");
            cylinder(root,"extractor-cap",new Vector3f(x,7.0f,22),1.0f,.22f,"rust");
        }
        // Platform girders stay within the slab/post silhouette; every crossing remains open.
        for(float z:new float[]{-9.7f,9.7f}) {
            box(root,"deck-edge-girder",new Vector3f(50,5.45f,z),new Vector3f(22,.08f,.12f),"rust");
            for(int x=30;x<72;x+=3)box(root,"deck-rivet-plate",new Vector3f(x,5.7f,z),new Vector3f(.12f,.20f,.08f),"steel");
        }
        for(float x:new float[]{32,64})for(float z:new float[]{-7,7}) {
            box(root,"post-baseplate",new Vector3f(x,.05f,z),new Vector3f(.34f,.045f,.34f),"rust");
            box(root,"post-capplate",new Vector3f(x,5.47f,z),new Vector3f(.36f,.055f,.36f),"rust");
        }
        // Flush tyre scars and drain grids give the road a scale cue without adding collision curbs.
        for(int mark=0;mark<24;mark++) {
            float x=-67+(mark*37)%134,z=-54+(mark*29)%108;
            if(Math.abs(x)<10&&Math.abs(z)<12||x>27&&x<73&&Math.abs(z)<40||x> -62&&x< -26&&z>8&&z<36)continue;
            for(float side:new float[]{-.9f,.9f}) {
                Geometry scar=new Geometry("skid-scar",SurfaceMesh.box(.055f,.002f,2.3f,1));
                scar.setLocalTranslation(x+side,.018f,z);scar.rotate(0,(mark%5)*.33f,0);scar.setMaterial(material("black"));
                scar.setShadowMode(RenderQueue.ShadowMode.Off);root.attachChild(scar);
            }
        }
        for(float x:new float[]{-74,74})for(int z=-48;z<60;z+=24) {
            box(root,"drain-recess",new Vector3f(x,.014f,z),new Vector3f(.55f,.004f,1.0f),"black");
            for(int slot=0;slot<9;slot++)box(root,"drain-grate",new Vector3f(x,.022f,z-.85f+slot*.21f),new Vector3f(.53f,.003f,.035f),"steel");
        }
    }
    private void addBackdrop(Node root,Node buildings) {
        for(int index=0;index<12;index++) {
            float angle=index*FastMath.TWO_PI/12,range=120+(index%3)*17;
            float x=FastMath.cos(angle)*range,z=FastMath.sin(angle)*range,height=12+(index*11)%23;
            box(buildings,"distant-factory",new Vector3f(x,height*.5f,z),new Vector3f(13,height*.5f,10),index%2==0?"concrete":"blue");
            box(buildings,"factory-cornice",new Vector3f(x,height+.25f,z),new Vector3f(13.3f,.25f,10.3f),"steel");
            for(int floor=0;floor<height/4;floor++)for(int column=-3;column<=3;column++) {
                box(buildings,"factory-window",new Vector3f(x+column*3,height-floor*4-2,z-10.035f),new Vector3f(.8f,.9f,.035f),"black");
                box(buildings,"factory-window",new Vector3f(x+column*3,height-floor*4-2,z+10.035f),new Vector3f(.8f,.9f,.035f),"black");
            }
            if(index%3==0) {
                cylinder(buildings,"smokestack",new Vector3f(x+8,height+13,z),1.8f,26,"rust");
                for(int ring=0;ring<4;ring++)cylinder(buildings,"stack-band",new Vector3f(x+8,height+3+ring*6,z),1.86f,.4f,"steel");
            } else if(index%3==1) {
                cylinder(buildings,"silo",new Vector3f(x-8,height+5,z),4,10,"steel");
                Geometry dome=new Geometry("silo-dome",new Sphere(12,24,4));dome.setLocalTranslation(x-8,height+10,z);
                dome.setLocalScale(1,.32f,1);dome.setMaterial(material("steel"));buildings.attachChild(dome);
            }
        }
        // A graded sky hemisphere uses continuous vertex colours, with no low-resolution image enlargement.
        Sphere sphere=new Sphere(20,48,350);Geometry sky=new Geometry("industrial-sky",sphere);
        var positions=(java.nio.FloatBuffer)sphere.getBuffer(VertexBuffer.Type.Position).getData();
        float[] colors=new float[sphere.getVertexCount()*4];
        for(int vertex=0;vertex<sphere.getVertexCount();vertex++) {
            float height=Math.clamp(positions.get(vertex*3+1)/350,0,1);
            ColorRGBA color=new ColorRGBA(.47f,.42f,.35f,1).interpolateLocal(new ColorRGBA(.065f,.105f,.18f,1),(float)Math.sqrt(height));
            colors[vertex*4]=color.r;colors[vertex*4+1]=color.g;colors[vertex*4+2]=color.b;colors[vertex*4+3]=1;
        }
        sphere.setBuffer(VertexBuffer.Type.Color,4,BufferUtils.createFloatBuffer(colors));
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");material.setBoolean("VertexColor",true);
        material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Front);
        material.getAdditionalRenderState().setDepthWrite(false);sky.setMaterial(material);
        sky.setQueueBucket(RenderQueue.Bucket.Sky);sky.setShadowMode(RenderQueue.ShadowMode.Off);root.attachChild(sky);
    }
    private void addSigns(Node root) {
        sign(root,"DEAD AIR",new Vector3f(0,9.65f,-8.17f),FastMath.PI,10,.95f);
        sign(root,"TRANSMISSION YARD / 04",new Vector3f(0,7.9f,-8.17f),FastMath.PI,10,.38f);
        sign(root,"WRECK & REPAIR",new Vector3f(-44,5.95f,34.65f),0,18,.75f);
        sign(root,"RIVET MOTOR WORKS",new Vector3f(-60.20f,5.93f,22),-FastMath.HALF_PI,16,.55f);
        sign(root,"DANGER / LIVE GRID",new Vector3f(0,.05f,-27),-FastMath.PI,15,.35f);
    }
    private void sign(Node root,String words,Vector3f centre,float yaw,float width,float height) {
        Node sign=new Node("sign-"+words);sign.setLocalTranslation(centre);sign.rotate(0,yaw,0);
        box(sign,"sign-backing",new Vector3f(0,0,-.04f),new Vector3f(width*.53f,height*.9f,.045f),"black");
        BitmapText text=new BitmapText(assets.loadFont("fonts/wreck-bold.fnt"));text.setText(words);
        text.setSize(height);text.setColor(new ColorRGBA(.93f,.74f,.38f,1));
        float scale=Math.min(1,width/text.getLineWidth());text.setLocalScale(scale);
        text.setLocalTranslation(-text.getLineWidth()*scale*.5f,height*.5f,.015f);
        text.setQueueBucket(RenderQueue.Bucket.Transparent);text.setShadowMode(RenderQueue.ShadowMode.Off);sign.attachChild(text);
        root.attachChild(sign);
    }
    private void cylinder(Node root,String name,Vector3f centre,float radius,float height,String surface) {
        Mesh mesh=new Cylinder(2,24,radius,height,true);com.jme3.util.mikktspace.MikktspaceTangentGenerator.generate(mesh);
        Geometry geometry=new Geometry(name,mesh);geometry.rotate(FastMath.HALF_PI,0,0);geometry.setLocalTranslation(centre);
        geometry.setMaterial(material(surface));root.attachChild(geometry);
    }
    private void beam(Node root,String name,Vector3f a,Vector3f b,float radius,String surface) {
        Vector3f direction=b.subtract(a);Geometry beam=new Geometry(name,SurfaceMesh.box(radius,radius,direction.length()*.5f,2));
        beam.setLocalTranslation(a.add(b).multLocal(.5f));beam.setLocalRotation(new Quaternion().lookAt(direction.normalizeLocal(),Math.abs(direction.y)>.99f?Vector3f.UNIT_Z:Vector3f.UNIT_Y));
        beam.setMaterial(material(surface));root.attachChild(beam);
    }
}
