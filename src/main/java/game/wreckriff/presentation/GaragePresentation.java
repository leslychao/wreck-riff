package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.util.BufferUtils;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** The menu's real, lit garage in the application's existing scene and lighting pipeline. */
public final class GaragePresentation implements AutoCloseable {
    private static final float TRANSITION_SECONDS=.3f,DECK_HEIGHT=.12f,ROTATION_SECONDS=36;
    private final AssetManager assets;
    private final VehicleRules rules;
    private final Node root=new Node("garage");
    private final Node turntable=new Node("garage-turntable"),fan=new Node("workshop-fan-rotor");
    private final Map<String,DisplayVehicle> vehicles=new HashMap<>();
    private final Haze haze;
    private DisplayVehicle selected;
    private double elapsed,selectionElapsed;
    private float transition=1;
    private boolean closed;
    private record DisplayVehicle(Node node,BoundingBox rotationEnvelope,float groundOffset) {}

    public GaragePresentation(AssetManager assets,VehicleRules rules) {
        this.assets=Objects.requireNonNull(assets);this.rules=Objects.requireNonNull(rules);
        buildWorkshop();haze=new Haze(assets);root.attachChild(haze.geometry);
    }

    public Node node() {return root;}

    /** Browsing changes presentation only. Saving a choice belongs to the screen flow. */
    public void show(String profileId) {
        if(closed)throw new IllegalStateException("Garage is closed");
        VehicleDefinition.forId(profileId);
        if(selected!=null&&profileId.equals(selected.node.getUserData("profileId")))return;
        DisplayVehicle next=vehicles.computeIfAbsent(profileId,this::createVehicle);
        if(selected!=null)selected.node.removeFromParent();
        selected=next;transition=0;selectionElapsed=0;root.attachChild(next.node);
        // A cached vehicle must re-enter from its authored three-quarter view, not its last turn.
        next.node.setLocalRotation(Quaternion.IDENTITY);
        next.node.setLocalTranslation(.55f,next.groundOffset,0);
        turntable.setLocalRotation(Quaternion.IDENTITY);
    }

    /** Occlusions are fractions of the viewport reserved by the responsive menu, measured left/bottom. */
    public void update(float dt,Camera camera,float leftOcclusion,float bottomOcclusion) {
        if(closed)return;
        if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Nonnegative finite frame time required");
        if(!Float.isFinite(leftOcclusion)||!Float.isFinite(bottomOcclusion)
                ||leftOcclusion<0||leftOcclusion>.8f||bottomOcclusion<0||bottomOcclusion>.8f)
            throw new IllegalArgumentException("Menu occlusion must be 0..0.8");
        Objects.requireNonNull(camera,"Camera required");
        elapsed+=dt;selectionElapsed+=dt;transition=Math.min(1,transition+dt/TRANSITION_SECONDS);
        if(selected!=null) {
            float entrance=1-transition*transition*(3-2*transition);
            // Use real elapsed time, including slow frames; every chassis completes a turn in 36 s.
            float angle=(float)((selectionElapsed%ROTATION_SECONDS)*Math.PI*2/ROTATION_SECONDS);
            selected.node.setLocalRotation(new Quaternion().fromAngleAxis(angle,Vector3f.UNIT_Y));
            turntable.setLocalRotation(selected.node.getLocalRotation());
            selected.node.setLocalTranslation(entrance*.55f,selected.groundOffset+.003f*(float)Math.sin(elapsed*8),0);
            // A tiny steering settle and idling tool motion make the intact model feel alive.
            for(int wheel=0;wheel<2;wheel++) {
                Spatial tyre=selected.node.getChild("wheel-"+wheel);
                tyre.setLocalRotation(new Quaternion().fromAngleAxis(.025f*(float)Math.sin(elapsed*.45f),Vector3f.UNIT_Y));
            }
            for(String name:new String[]{"grinder-roller-left","grinder-roller-right"}) {
                Spatial roller=selected.node.getChild(name);
                if(roller!=null)roller.setLocalRotation(new Quaternion().fromAngleAxis((float)(elapsed*.13%(Math.PI*2)),Vector3f.UNIT_X));
            }
            frame(camera,leftOcclusion,bottomOcclusion);
        }
        fan.setLocalRotation(new Quaternion().fromAngleAxis((float)(elapsed*1.9%(Math.PI*2)),Vector3f.UNIT_Z));
        haze.update(elapsed);
    }

    int cachedVehicleCount() {return vehicles.size();}

    private DisplayVehicle createVehicle(String id) {
        VehicleProfile profile=VehicleProfile.player(id,rules);
        Node model=VehicleVisual.create(assets,profile,0);model.setName("garage-vehicle");
        model.updateGeometricState();
        BoundingBox bounds=(BoundingBox)model.getWorldBound().clone();
        float groundOffset=DECK_HEIGHT-bounds.getCenter().y+bounds.getYExtent();
        float maxX=Math.abs(bounds.getCenter().x)+bounds.getXExtent(),maxZ=Math.abs(bounds.getCenter().z)+bounds.getZExtent();
        // A fixed cylinder envelope includes every yaw, steering/roller motion and the entrance slide.
        float radius=FastMath.sqrt(maxX*maxX+maxZ*maxZ)+.08f;
        BoundingBox envelope=new BoundingBox(new Vector3f(0,bounds.getCenter().y+groundOffset,0),
                radius+.55f,bounds.getYExtent()+.04f,radius);
        return new DisplayVehicle(model,envelope,groundOffset);
    }

    private void frame(Camera camera,float left,float bottom) {
        // Frame the complete turn once, not the current rotated AABB. Camera breathing makes
        // a rotating car change apparent size and clips tools at the widest intermediate yaw.
        // simpleUpdate precedes the renderer's root-to-leaf geometric update. Forcing an update
        // on this child while parent light/material flags are dirty violates jME's traversal contract.
        // Transform the measured, immutable authored bounds instead; the renderer retains sole
        // responsibility for updating the full scene, including inherited lights and overrides.
        Transform transform=root.getLocalTransform().clone();
        for(Node parent=root.getParent();parent!=null;parent=parent.getParent())
            transform.combineWithParent(parent.getLocalTransform());
        BoundingBox bounds=(BoundingBox)selected.rotationEnvelope.transform(transform,null);
        Vector3f centre=bounds.getCenter();
        float yaw=.63f;
        Vector3f away=new Vector3f(FastMath.sin(yaw),.32f,FastMath.cos(yaw)).normalizeLocal();
        Vector3f right=Vector3f.UNIT_Y.cross(away).normalizeLocal();
        Vector3f up=away.cross(right).normalizeLocal();
        float tanY=FastMath.tan(35*FastMath.DEG_TO_RAD/2),aspect=(float)camera.getWidth()/Math.max(1,camera.getHeight());
        float tanX=tanY*aspect,usableX=tanX*(1-left)*.84f,usableY=tanY*(1-bottom)*.78f;
        float distance=6;
        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1}) {
            Vector3f corner=new Vector3f(x*bounds.getXExtent(),y*bounds.getYExtent(),z*bounds.getZExtent());
            float toward=corner.dot(away);
            distance=Math.max(distance,toward+Math.max(Math.abs(corner.dot(right))/usableX,Math.abs(corner.dot(up))/usableY));
        }
        camera.setParallelProjection(false);
        float near=.1f;
        camera.setFrustum(near,400,-near*tanX*(1+left),near*tanX*(1-left),near*tanY*(1-bottom),-near*tanY*(1+bottom));
        camera.setLocation(centre.add(away.mult(distance)));camera.lookAt(centre,Vector3f.UNIT_Y);
        camera.update();
    }

    private void buildWorkshop() {
        SurfaceMaterials surfaces=new SurfaceMaterials(assets);
        Material floor=surfaces.textured("cracked_concrete",new ColorRGBA(.12f,.13f,.15f,1),26,.18f);
        Material steel=surfaces.material("black"),metal=surfaces.material("steel"),rust=surfaces.material("rust");
        Material amber=surfaces.material("light-amber"),cyan=surfaces.material("light-cyan");
        Node room=new Node("garage-workshop");root.attachChild(room);
        box(room,"workshop-floor",floor,0,-.18f,52,120,.18f,80,3);
        buildTurntable(room,steel,metal,rust);
        // The complete turning envelope needs more space at 640x480 and maximum UI scale.
        // Distant perimeter walls close the asymmetric ultrawide frustum, including its left edge.
        // Keep the roof open so these perimeter walls preserve the directional workshop lighting.
        box(room,"back-wall",floor,0,35,-8,120,35,.2f,3);
        box(room,"side-wall",floor,13,5,-1,.2f,5,7,3);
        box(room,"left-wall",floor,-22,5,9.5f,.2f,5,17.5f,3);
        box(room,"workshop-outer-left",floor,-120,35,61,.2f,35,69,3);
        box(room,"workshop-outer-right",floor,120,35,61,.2f,35,69,3);
        box(room,"workshop-front-wall",floor,0,35,130,120,35,.2f,3);
        // Corrugated steel shutter, bolted support frames and roof trusses anchor the room in real metres.
        var dark=new VehicleVisual.Builder();var structure=new VehicleVisual.Builder();
        var wear=new VehicleVisual.Builder();var warm=new VehicleVisual.Builder();var cool=new VehicleVisual.Builder();
        dark.box(0,2.5f,-7.65f,4.2f,2.5f,.12f);
        for(int rib=0;rib<26;rib++)structure.box(0,.17f+rib*.19f,-7.49f,4.1f,.035f,.038f);
        for(float x:new float[]{-10,-5,5,10}) {
            structure.box(x,3.5f,-7.2f,.13f,3.5f,.20f);
            structure.box(x,6.6f,-.4f,.13f,.16f,7);
            dark.box(x,.14f,-7.2f,.32f,.14f,.36f);
            for(float y:new float[]{.6f,2,3.4f,4.8f,6.2f})wear.box(x+.145f,y,-6.98f,.055f,.055f,.024f);
        }
        for(float z:new float[]{-7,-3,1,5}) {
            structure.box(0,6.8f,z,12,.16f,.15f);
            dark.box(0,6.52f,z,3.1f,.065f,.25f);
            warm.box(0,6.435f,z,2.8f,.025f,.095f);
        }
        // Fixed service lights frame the turning platform without rotating the entire workshop.
        for(int side:new int[]{-1,1}) {
            for(int segment=0;segment<6;segment++)
                (side<0?warm:cool).box(side*4.55f,.025f,-3.5f+segment*1.35f,.035f,.018f,.38f);
            dark.box(side*4.65f,2.8f,-6.8f,.16f,1.9f,.15f);
            (side<0?warm:cool).box(side*4.65f,2.8f,-6.61f,.045f,1.5f,.035f);
        }
        // A service bench, drawer cabinets, stacked wheels and cable ducts add asymmetry to the workshop.
        dark.box(-7.4f,.82f,-5.7f,1.7f,.75f,.64f);structure.box(-7.4f,1.61f,-5.7f,1.83f,.07f,.73f);
        for(int drawer=0;drawer<4;drawer++) {
            wear.box(-7.4f,.36f+drawer*.29f,-5.03f,1.61f,.115f,.018f);
            structure.box(-7.4f,.36f+drawer*.29f,-4.995f,.22f,.025f,.035f);
        }
        dark.box(7.2f,1.1f,-6.3f,.78f,1.1f,.6f);wear.box(7.2f,1.1f,-5.68f,.7f,1.02f,.025f);
        for(int vent=0;vent<8;vent++)structure.box(7.2f,1.12f+vent*.10f,-5.64f,.5f,.015f,.015f);
        for(float y:new float[]{4.2f,4.45f})structure.box(8,y,-7.4f,3.4f,.055f,.065f);
        // A welding station and spare weapons use the same visual vocabulary as the playable cars.
        dark.box(-6.25f,1.75f,-5.6f,.25f,.09f,.25f);
        structure.cylinderZ(-6.25f,1.92f,-5.25f,.075f,.52f,12);
        dark.box(-8.1f,1.92f,-5.75f,.55f,.24f,.28f);
        for(float x:new float[]{-8.35f,-7.86f}) {
            structure.cylinderZ(x,1.92f,-5.44f,.18f,.025f,14);
            dark.cylinderZ(x,1.92f,-5.416f,.125f,.02f,14);
        }
        for(float x:new float[]{6.1f,6.55f}) {
            structure.cylinderZ(x,1.58f,-6.1f,.15f,1.05f,12);
            dark.cylinderZ(x,1.58f,-5.56f,.095f,.025f,12);
            wear.box(x,1.38f,-6.1f,.18f,.045f,.45f);
        }
        buildFan(room,dark,structure,metal);
        buildCables(room,steel);
        dark.attach(room,"workshop-dark-metal",steel);structure.attach(room,"workshop-steel",metal);
        wear.attach(room,"workshop-worn-trim",rust);warm.attach(room,"workshop-warm-fixtures",amber);cool.attach(room,"workshop-cool-fixtures",cyan);
        room.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        for(int stack=0;stack<3;stack++) {
            Node tyre=VehicleVisual.wheel(stack,metal,surfaces.rubber());tyre.setName("spare-tyre-"+stack);
            tyre.setLocalRotation(new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Z));
            tyre.setLocalTranslation(5.6f,.21f+stack*.31f,-4.8f);room.attachChild(tyre);
        }
    }

    private void buildTurntable(Node room,Material steel,Material metal,Material rust) {
        room.attachChild(turntable);
        var base=new VehicleVisual.Builder();base.cylinderZ(0,0,0,4.42f,.075f,96);
        base.attach(room,"turntable-bearing",metal);
        Spatial bearing=room.getChild("turntable-bearing");
        bearing.setLocalRotation(new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_X));
        bearing.setLocalTranslation(0,.0375f,0);
        var deck=new VehicleVisual.Builder();deck.cylinderZ(0,0,0,4.30f,DECK_HEIGHT,96);
        deck.attach(turntable,"service-deck",steel);
        Spatial surface=turntable.getChild("service-deck");
        surface.setLocalRotation(new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_X));
        surface.setLocalTranslation(0,DECK_HEIGHT/2,0);
        var seams=new VehicleVisual.Builder();var markings=new VehicleVisual.Builder();
        for(int index=0;index<32;index++) {
            float angle=index*FastMath.TWO_PI/32,x=FastMath.sin(angle),z=FastMath.cos(angle);
            seams.box(x*4.16f,DECK_HEIGHT+.008f,z*4.16f,.025f,.008f,.025f);
            if(index%2==0) {
                float next=angle+.065f;
                markings.quad(new Vector3f(x*4.00f,DECK_HEIGHT+.002f,z*4.00f),
                        new Vector3f(x*4.24f,DECK_HEIGHT+.002f,z*4.24f),
                        new Vector3f(FastMath.sin(next)*4.24f,DECK_HEIGHT+.002f,FastMath.cos(next)*4.24f),
                        new Vector3f(FastMath.sin(next)*4.00f,DECK_HEIGHT+.002f,FastMath.cos(next)*4.00f));
            }
        }
        seams.box(0,DECK_HEIGHT+.002f,0,3.82f,.002f,.012f);
        seams.attach(turntable,"turntable-seams-and-bolts",metal);
        markings.attach(turntable,"turntable-index-marks",rust);
    }

    private void buildFan(Node room,VehicleVisual.Builder dark,VehicleVisual.Builder structure,Material metal) {
        dark.cylinderZ(8.9f,5.25f,-7.25f,.94f,.23f,48);
        structure.cylinderZ(8.9f,5.25f,-7.09f,.14f,.22f,20);
        fan.setLocalTranslation(8.9f,5.25f,-7.10f);room.attachChild(fan);
        var blades=new VehicleVisual.Builder();
        for(int index=0;index<5;index++) {
            Quaternion rotation=new Quaternion().fromAngleAxis(index*FastMath.TWO_PI/5,Vector3f.UNIT_Z);
            blades.quad(rotation.mult(new Vector3f(.11f,-.05f,.06f)),rotation.mult(new Vector3f(.78f,-.19f,.03f)),
                    rotation.mult(new Vector3f(.81f,.13f,-.01f)),rotation.mult(new Vector3f(.22f,.17f,.02f)));
        }
        blades.attach(fan,"fan-blades",metal);
        for(int bar=-3;bar<=3;bar++) {
            float offset=bar*.23f,length=FastMath.sqrt(.87f*.87f-offset*offset);
            structure.box(8.9f+offset,5.25f,-6.93f,.016f,length,.017f);
        }
    }

    private void buildCables(Node room,Material material) {
        var cables=new VehicleVisual.Builder();
        for(int cable=0;cable<3;cable++)for(int part=0;part<32;part++) {
            float phase=part/31f;
            Vector3f from=new Vector3f(-8.9f+phase*4.6f,.038f,-4.3f+cable*.16f+.58f*FastMath.sin(phase*FastMath.PI*2));
            float next=(part+1)/31f;
            Vector3f to=new Vector3f(-8.9f+next*4.6f,.038f,-4.3f+cable*.16f+.58f*FastMath.sin(next*FastMath.PI*2));
            Vector3f cross=to.subtract(from).normalizeLocal().cross(Vector3f.UNIT_Y).multLocal(.027f);
            cables.quad(from.add(cross),to.add(cross),to.subtract(cross),from.subtract(cross));
        }
        cables.attach(room,"workshop-power-cables",material);
    }

    private static void box(Node parent,String name,Material material,float x,float y,float z,float halfX,float halfY,float halfZ,float tile) {
        var geometry=new Geometry(name,SurfaceMesh.box(halfX,halfY,halfZ,tile));geometry.setMaterial(material);
        geometry.setLocalTranslation(x,y,z);parent.attachChild(geometry);
    }

    /** Twelve wisps and eight rare welding sparks reuse the local particle shader in one bounded draw. */
    private static final class Haze {
        private static final int WISPS=12,SPARKS=8,COUNT=WISPS+SPARKS,VERTICES=COUNT*6;
        private static final float[] UV={0,0,1,0,1,1,0,0,1,1,0,1};
        final Mesh mesh=new Mesh();final Geometry geometry=new Geometry("garage-haze",mesh);
        final FloatBuffer positions=BufferUtils.createFloatBuffer(VERTICES*3),colors=BufferUtils.createFloatBuffer(VERTICES*4);
        Haze(AssetManager assets) {
            FloatBuffer uv=BufferUtils.createFloatBuffer(VERTICES*2),shape=BufferUtils.createFloatBuffer(VERTICES*2),variation=BufferUtils.createFloatBuffer(VERTICES*2);
            for(int puff=0;puff<COUNT;puff++)for(int vertex=0;vertex<6;vertex++) {
                uv.put(UV[vertex*2]).put(UV[vertex*2+1]);
                shape.put(puff<WISPS?.65f+puff%3*.18f:.038f+puff%3*.008f).put(puff<WISPS?1:2);
                variation.put(puff*.91f).put(puff/(float)COUNT);
            }
            uv.flip();shape.flip();variation.flip();
            mesh.setBuffer(VertexBuffer.Type.Position,3,positions);mesh.setBuffer(VertexBuffer.Type.Color,4,colors);
            mesh.setBuffer(VertexBuffer.Type.TexCoord,2,uv);mesh.setBuffer(VertexBuffer.Type.TexCoord2,2,shape);mesh.setBuffer(VertexBuffer.Type.TexCoord3,2,variation);
            mesh.setDynamic();mesh.setBound(new BoundingBox(new Vector3f(0,2,-4),10,4,4));
            Material material=new Material(assets,"materials/CombatParticles.j3md");
            material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            material.getAdditionalRenderState().setDepthWrite(false);material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            geometry.setMaterial(material);geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
            geometry.setShadowMode(RenderQueue.ShadowMode.Off);update(0);
        }
        void update(double time) {
            positions.clear();colors.clear();
            for(int puff=0;puff<WISPS;puff++) {
                float phase=(float)((time*.065+puff/(double)WISPS)%1),alpha=.09f*FastMath.sin(phase*FastMath.PI);
                float x=-5.3f+puff*.91f+.55f*(float)Math.sin(time*.15+puff),y=.4f+phase*2.3f,z=-4.5f+puff%3*.33f;
                for(int vertex=0;vertex<6;vertex++){positions.put(x).put(y).put(z);colors.put(.31f).put(.36f).put(.40f).put(alpha);}
            }
            for(int spark=0;spark<SPARKS;spark++) {
                float age=(float)((time+2.7)%8.6)-spark*.029f;
                float alpha=age>=0&&age<.46f?.82f*(1-age/.46f):0;
                float visibleAge=Math.max(0,Math.min(.46f,age));
                float x=-6.25f+visibleAge*(.6f+spark*.23f),y=1.92f+visibleAge*(1.8f-spark*.14f)-4.9f*visibleAge*visibleAge;
                float z=-4.96f+visibleAge*(.35f+spark%3*.42f);
                for(int vertex=0;vertex<6;vertex++){positions.put(x).put(y).put(z);colors.put(1).put(.62f).put(.20f).put(alpha);}
            }
            positions.flip();colors.flip();mesh.getBuffer(VertexBuffer.Type.Position).updateData(positions);mesh.getBuffer(VertexBuffer.Type.Color).updateData(colors);
        }
    }

    @Override public void close() {closed=true;root.removeFromParent();root.detachAllChildren();vehicles.clear();selected=null;}
}
