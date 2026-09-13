package game.wreckriff.presentation;

import com.jme3.anim.MorphControl;
import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.collision.CollisionResults;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.control.AbstractControl;
import com.jme3.scene.mesh.MorphTarget;
import com.jme3.util.BufferUtils;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.simulation.*;
import java.nio.FloatBuffer;
import java.util.*;

/** Two GPU composites, prepared HP poses, bounded local history and explicit repair. */
final class VehicleDamageVisual extends AbstractControl {
    static final int STAGES=5;static final float MAX_DENT=.35f,DAMAGE_SECONDS=.12f,REPAIR_SECONDS=.30f;
    private static final List<String> PANELS=List.of("panel-door-left","panel-door-right","panel-hood","panel-trunk");
    private static final class Part {
        final VehicleModelData.Part source;final Geometry geometry,canonical;final int lod;
        final float[] base,from,to,fromNormal,toNormal,fromTangent,toTangent;
        final boolean deforms;final MorphTarget previous=new MorphTarget("previous"),next=new MorphTarget("next");
        final FloatBuffer[] previousBuffers=new FloatBuffer[3],nextBuffers=new FloatBuffer[3];
        final List<Geometry> overlays=new ArrayList<>();final BitSet dirtyTriangles=new BitSet();
        float elapsed=1,duration=DAMAGE_SECONDS;int composedStage=-1;final float[] composedRegions=new float[8],fromRegions=new float[8],fromStages={1,0,0,0,0};
        Part(VehicleModelData.Part source,Geometry geometry,int lod) {
            this.source=source;this.geometry=geometry;this.lod=lod;base=positions(source.stages()[0]);from=base.clone();to=base.clone();
            fromNormal=read3(source.stages()[0],VertexBuffer.Type.Normal);toNormal=fromNormal.clone();fromTangent=read3(source.stages()[0],VertexBuffer.Type.Tangent);toTangent=fromTangent.clone();
            deforms=Arrays.stream(source.regions()).anyMatch(r->r.indices().length>0);
            Mesh mesh=source.stages()[0].clone();geometry.setMesh(mesh);
            if(deforms){mesh.addMorphTarget(previous);mesh.addMorphTarget(next);
                VertexBuffer.Type[] types={VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.Tangent};
                for(int i=0;i<3;i++){previousBuffers[i]=BufferUtils.createFloatBuffer(base.length);nextBuffers[i]=BufferUtils.createFloatBuffer(base.length);previous.setBuffer(types[i],previousBuffers[i]);next.setBuffer(types[i],nextBuffers[i]);}
                // The initial identity targets are already settled, matching elapsed >= duration.
                geometry.setMorphState(new float[]{0,1});
            }
            if(source.name().equals("headlights")||source.name().equals("taillights")){float[] colors=new float[base.length/3*4];Arrays.fill(colors,1);mesh.setBuffer(VertexBuffer.Type.Color,4,colors);}
            BoundingBox bound=(BoundingBox)source.stages()[0].getBound().clone();if(deforms){bound.setXExtent(bound.getXExtent()+MAX_DENT);bound.setYExtent(bound.getYExtent()+MAX_DENT);bound.setZExtent(bound.getZExtent()+MAX_DENT);}mesh.setBound(bound);
            canonical=lod==0?new Geometry(source.name(),source.stages()[0]):null;if(canonical!=null){canonical.updateGeometricState();source.stages()[0].createCollisionData();}
        }
        boolean needsCompose(int stage,float[] regions){if(!deforms)return false;if(composedStage!=stage)return true;for(int i=0;i<8;i++)if(composedRegions[i]!=regions[i]&&source.regions()[i].indices().length>0)return true;return false;}
        void compose(int stage,float[] regions,float blend) {
            for(int i=0;i<5;i++)fromStages[i]=fromStages[i]*(1-blend)+(Math.max(0,composedStage)==i?blend:0);for(int i=0;i<8;i++)fromRegions[i]+=(composedRegions[i]-fromRegions[i])*blend;
            float[] oldN=fromNormal,oldT=fromTangent;
            for(int i=0;i<from.length;i++){from[i]+=(to[i]-from[i])*blend;oldN[i]+=(toNormal[i]-oldN[i])*blend;oldT[i]+=(toTangent[i]-oldT[i])*blend;}
            Mesh pose=source.stages()[stage];copy3(pose,VertexBuffer.Type.Position,to);copy3(pose,VertexBuffer.Type.Normal,toNormal);copy3(pose,VertexBuffer.Type.Tangent,toTangent);dirtyTriangles.clear();
            displace(to,toNormal,toTangent,regions);
            upload(previousBuffers,from,fromNormal,fromTangent);upload(nextBuffers,to,toNormal,toTangent);dirtyGpu();composedStage=stage;System.arraycopy(regions,0,composedRegions,0,8);
        }
        private void displace(float[] point,float[] normal,float[] tangent,float[] regions){dirtyTriangles.clear();
            for(int region=0;region<8;region++)if(regions[region]>0){var d=source.regions()[region];for(int j=0;j<d.indices().length;j++){int vertex=d.indices()[j];dirtyTriangles.set(vertex/3);for(int k=0;k<3;k++)point[vertex*3+k]+=d.xyz()[j*3+k]*regions[region];}}
            for(int i=0;i<point.length;i+=3){float x=point[i]-base[i],y=point[i+1]-base[i+1],z=point[i+2]-base[i+2],s=x*x+y*y+z*z;if(s>MAX_DENT*MAX_DENT){float r=MAX_DENT/(float)Math.sqrt(s);point[i]=base[i]+x*r;point[i+1]=base[i+1]+y*r;point[i+2]=base[i+2]+z*r;dirtyTriangles.set(i/9);}}
            var uv=source.stages()[0].getFloatBuffer(VertexBuffer.Type.TexCoord);
            for(int triangle=dirtyTriangles.nextSetBit(0);triangle>=0;triangle=dirtyTriangles.nextSetBit(triangle+1))basis(point,uv,triangle,normal,tangent);
        }
        private void dirtyGpu(){for(VertexBuffer vb:geometry.getMesh().getBufferList())if(vb.getBufferType().ordinal()>=VertexBuffer.Type.MorphTarget0.ordinal()&&vb.getBufferType().ordinal()<=VertexBuffer.Type.MorphTarget9.ordinal())vb.setUpdateNeeded();geometry.setDirtyMorph(true);}
        void inheritTransition(Part active){
            if(!deforms)return;compose(Math.max(0,active.composedStage),active.composedRegions,1);
            System.arraycopy(active.fromStages,0,fromStages,0,5);System.arraycopy(active.fromRegions,0,fromRegions,0,8);
            Arrays.fill(from,0);Arrays.fill(fromNormal,0);Arrays.fill(fromTangent,0);
            for(int stage=0;stage<5;stage++)if(fromStages[stage]>0){float weight=fromStages[stage];Mesh pose=source.stages()[stage];var pos=pose.getFloatBuffer(VertexBuffer.Type.Position);var norm=pose.getFloatBuffer(VertexBuffer.Type.Normal);var tangent=pose.getFloatBuffer(VertexBuffer.Type.Tangent);
                for(int i=0;i<from.length;i++){from[i]+=pos.get(i)*weight;fromNormal[i]+=norm.get(i)*weight;fromTangent[i]+=tangent.get(i/3*4+i%3)*weight;}}
            displace(from,fromNormal,fromTangent,fromRegions);upload(previousBuffers,from,fromNormal,fromTangent);dirtyGpu();elapsed=active.elapsed;duration=active.duration;
        }
        void close(){for(VertexBuffer vb:geometry.getMesh().getBufferList()){var type=vb.getBufferType();if(type.ordinal()>=VertexBuffer.Type.MorphTarget0.ordinal()&&type.ordinal()<=VertexBuffer.Type.MorphTarget9.ordinal()||type==VertexBuffer.Type.Color)vb.dispose();}
            for(FloatBuffer buffer:previousBuffers)if(buffer!=null)BufferUtils.destroyDirectBuffer(buffer);for(FloatBuffer buffer:nextBuffers)if(buffer!=null)BufferUtils.destroyDirectBuffer(buffer);
            var color=geometry.getMesh().getBuffer(VertexBuffer.Type.Color);if(color!=null)BufferUtils.destroyDirectBuffer(color.getData());
        }
        private void upload(FloatBuffer[] buffers,float[] point,float[] normal,float[] tangent) {
            var baseN=source.stages()[0].getFloatBuffer(VertexBuffer.Type.Normal);var baseT=source.stages()[0].getFloatBuffer(VertexBuffer.Type.Tangent);
            for(int i=0;i<base.length;i++){buffers[0].put(i,point[i]-base[i]);buffers[1].put(i,normal[i]-baseN.get(i));buffers[2].put(i,tangent[i]-baseT.get(i/3*4+i%3));}
        }
    }
    private final Node root;private final VehicleProfile profile;private final VehicleDamageMarks marks;private final WeaponMountVisual mounts;
    private final List<Part> parts=new ArrayList<>();private final Node[] levels=new Node[3],frostLevels=new Node[3],shieldLevels=new Node[3];
    private final Node frost=new Node("frost-overlay"),shield=new Node("shield-shell");private final float[] regions=new float[8];
    private final Set<String> detached=new HashSet<>();private final List<VehicleVisual.DetachedPanel> pending=new ArrayList<>();private final LinkedHashSet<String> seen=new LinkedHashSet<>();
    private int stage,lod;private float elapsed=1,duration=DAMAGE_SECONDS,pendingDuration=-1,maximumHp=Float.NaN;private long repairRevision;
    private final float[] lampDamage=new float[2];private final Vector3f[] regionNormals=new Vector3f[8];
    private final Vector3f[] silhouetteCorners=new Vector3f[8];private final Vector3f worldCorner=new Vector3f(),screenCorner=new Vector3f();
    private final List<Geometry> wheelGeometry=new ArrayList<>();private final List<Mesh[]> wheelLods=new ArrayList<>();
    private final CollisionResults collisionResults=new CollisionResults();
    private final LinkedHashMap<String,Anchor> contactAnchors=new LinkedHashMap<>();
    private record Anchor(Part part,int triangle,float a,float b,float c,Vector3f point,Vector3f normal,Vector2f uv) {}
    static void install(AssetManager assets,Node root,VehicleProfile profile,int livery){root.addControl(new VehicleDamageVisual(assets,root,profile,livery));MorphControl morph=new MorphControl();morph.setApproximateTangents(false);root.addControl(morph);}
    private VehicleDamageVisual(AssetManager assets,Node root,VehicleProfile profile,int livery) {
        this.root=root;this.profile=profile;marks=new VehicleDamageMarks(assets,profile.id());mounts=new WeaponMountVisual(root);var model=VehicleModelData.load(assets,profile.id());
        for(int level=0;level<3;level++) {
            levels[level]=new Node("lod"+level);root.attachChild(levels[level]);frostLevels[level]=new Node("frost-lod"+level);shieldLevels[level]=new Node("shield-lod"+level);frost.attachChild(frostLevels[level]);shield.attachChild(shieldLevels[level]);
            for(var data:model.lods.get(level)) {
                String name=data.name();if(name.startsWith("wheel-"))continue;Geometry geometry=new Geometry(name);geometry.setMaterial(VehicleMaterials.create(assets,profile.id(),name));
                var material=geometry.getMaterial();if(material.getMaterialDef().getMaterialParam("DamageMap")!=null)material.setTexture("DamageMap",marks.texture);
                if(name.equals("paint")||name.startsWith("panel-")) {ColorRGBA tint=switch(Math.floorMod(livery,5)){case 1->new ColorRGBA(.8f,.72f,1,1);case 2->new ColorRGBA(1,.85f,.5f,1);case 3->new ColorRGBA(.5f,1,1,1);case 4->new ColorRGBA(.72f,1,.55f,1);default->ColorRGBA.White;};material.setColor("Diffuse",tint);material.setColor("Ambient",tint);}
                Part part=new Part(data,geometry,level);parts.add(part);
                if(name.startsWith("grinder-roller-")){Node roller=new Node(level==0?name:name+"-lod"+level);Vector3f centre=new Vector3f(name.endsWith("left")?-.62f:.62f,.42f,2.78f);roller.setLocalTranslation(centre);geometry.setLocalTranslation(centre.negate());roller.attachChild(geometry);levels[level].attachChild(roller);geometry.setName(name+"-mesh");}else levels[level].attachChild(geometry);
                if(name.equals("service-core"))geometry.setCullHint(Spatial.CullHint.Always);
                if(name.equals("paint")||name.equals("glass")||name.startsWith("panel-")) {
                    Material ice=new Material(assets,"materials/VehicleFrost.j3md");ice.setTexture("DamageMap",marks.texture);ice.setFloat("MaxOpacity",.62f);
                    status(part,frostLevels[level],"frost-",ice);
                    Material barrier=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");barrier.setColor("Color",new ColorRGBA(.055f,.48f,1,.14f));
                    status(part,shieldLevels[level],"shield-",barrier);
                }
            }
        }
        for(int wheel=0;wheel<4;wheel++) {
            Node axle=new Node("wheel-"+wheel);axle.setLocalScale(profile.wheelRadius()/.38f);axle.setLocalTranslation(profile.wheelConnection(wheel).add(0,-profile.suspensionRestLength(),0));
            for(var data:model.lods.get(0))if(data.name().startsWith("wheel-"+wheel+"-")){Geometry g=new Geometry(data.name(),data.stages()[0]);g.setMaterial(VehicleMaterials.create(assets,profile.id(),data.name().endsWith("tyre")?"rubber-trim":"steel"));axle.attachChild(g);
                Mesh[] wheelMeshes=new Mesh[3];for(int level=0;level<3;level++)for(var candidate:model.lods.get(level))if(candidate.name().equals(data.name()))wheelMeshes[level]=candidate.stages()[0];wheelGeometry.add(g);wheelLods.add(wheelMeshes);
            }root.attachChild(axle);
        }
        BoundingBox silhouette=null;for(Part part:parts)if(part.lod==0){if(silhouette==null)silhouette=(BoundingBox)part.geometry.getMesh().getBound().clone();else silhouette.mergeLocal(part.geometry.getMesh().getBound());}
        for(int wheel=0;wheel<4;wheel++){Vector3f center=profile.wheelConnection(wheel).add(0,-profile.suspensionRestLength(),0);silhouette.mergeLocal(new BoundingBox(center,profile.wheelRadius(),profile.wheelRadius(),profile.wheelRadius()));}
        Vector3f center=silhouette.getCenter();for(int i=0;i<8;i++)silhouetteCorners[i]=new Vector3f(center.x+((i&1)==0?-1:1)*silhouette.getXExtent(),center.y+((i&2)==0?-1:1)*silhouette.getYExtent(),center.z+((i&4)==0?-1:1)*silhouette.getZExtent());
        root.attachChild(frost);root.attachChild(shield);effects(false,false);selectLod(0);root.setUserData("damageStage",0);root.setUserData("repairRevision",0L);
    }
    private static void status(Part part,Node parent,String prefix,Material material) {
        Geometry overlay=new Geometry(prefix+part.source.name(),part.geometry.getMesh());material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);material.getAdditionalRenderState().setDepthWrite(false);material.getAdditionalRenderState().setPolyOffset(-1,-1);
        overlay.setMaterial(material);overlay.setQueueBucket(RenderQueue.Bucket.Transparent);overlay.setShadowMode(RenderQueue.ShadowMode.Off);if(part.deforms)overlay.setMorphState(part.geometry.getMorphState().clone());parent.attachChild(overlay);part.overlays.add(overlay);
    }
    static int stage(float hp){if(!Float.isFinite(hp))throw new IllegalArgumentException("Finite HP fraction required");return hp<=0?4:hp<=.25f?3:hp<=.5f?2:hp<=.75f?1:0;}
    void configureMaximumHp(float value){if(!Float.isFinite(value)||value<=0)throw new IllegalArgumentException("Positive authoritative maximum HP required");maximumHp=value;}
    void damage(float hp){int next=stage(hp);if(next==stage)return;stage=next;root.setUserData("damageStage",stage);transition(pendingDuration>=0?pendingDuration:duration==REPAIR_SECONDS&&elapsed<duration?REPAIR_SECONDS:DAMAGE_SECONDS);tryDetach();if(stage==4)effects(false,false);}
    void accept(GameEvent event) {
        String key=event.type()+":"+event.eventId()+":"+event.subjectId();if(!seen.add(key))return;if(seen.size()>256)seen.remove(seen.iterator().next());
        if(event.type()==GameEvent.Type.SHOT){mounts.accept(event);return;}
        if(event.type()==GameEvent.Type.REPAIRED) {
            var hp=event.healthChange();if(hp==null||!Float.isFinite(maximumHp))throw new IllegalStateException("Repair requires bound participant maximum HP and health change");
            float ratio=Math.clamp((hp.hpAfter()-hp.hpBefore())/Math.max(.001f,maximumHp-hp.hpBefore()),0,1);
            for(int i=0;i<8;i++)regions[i]*=1-ratio;for(int i=0;i<2;i++)lampDamage[i]*=1-ratio;marks.repair(ratio);
            if(hp.hpAfter()>=maximumHp-.001f){detached.clear();pending.clear();}repairRevision++;root.setUserData("repairRevision",repairRevision);transition(REPAIR_SECONDS);updateDetached();return;
        }
        if(event.type()==GameEvent.Type.DESTROYED){damage(0);return;}
        if(event.type()!=GameEvent.Type.DAMAGE||event.value()<=0||event.vehicleContact()==null)return;
        Vector3f p=event.vehicleContact().localPoint(),n=event.vehicleContact().localNormal();int region=region(p);boolean thermal=event.kind().equals("napalm-fire");float force=thermal?Math.clamp(event.value()/40,.012f,.15f):Math.clamp(event.value()/80,.06f,.65f);
        Anchor anchor=contactAnchor(event);if(anchor!=null)marks.hit(anchor.point,anchor.normal,anchor.uv,force,event.kind().contains("napalm")||event.kind().contains("fire"),anchor.part.source.name().equals("glass"),event.eventId(),anchor.part.source.islandId(),event.kind().equals("machine-gun")?0:event.kind().contains("ram")?4:2);
        if(thermal)return;
        regions[region]=Math.min(1,regions[region]+force);regionNormals[region]=n.clone();
        if(p.z>profile.length()*.3f)lampDamage[p.x<0?0:1]=Math.min(1,lampDamage[p.x<0?0:1]+force);
        transition(DAMAGE_SECONDS);
        tryDetach();
    }
    private void tryDetach(){if(stage<2)return;for(int region=0;region<8;region++)if(regions[region]>=.65f&&regionNormals[region]!=null){String panel=region==2?PANELS.get(0):region==3?PANELS.get(1):region<2||region==7?PANELS.get(2):region==4||region==5?PANELS.get(3):null;
            if(panel!=null&&detached.add(panel))for(Part part:parts)if(part.lod==2&&part.source.name().equals(panel)){Mesh mesh=part.source.stages()[stage];BoundingBox box=(BoundingBox)mesh.getBound();pending.add(new VehicleVisual.DetachedPanel(panel,box.getCenter().clone(),new Quaternion(),new Vector3f(box.getXExtent(),box.getYExtent(),box.getZExtent()),regionNormals[region].mult(2.5f).addLocal(0,1.5f,0),VehicleModelData.centeredPanel(mesh)));break;}updateDetached();}
    }
    private int region(Vector3f p){if(p.y>profile.height()*.68f)return 6;float z=p.z/profile.length();if(z>.24f)return p.x<0?0:1;if(z<-.24f)return p.x<0?4:5;return p.x<0?2:3;}
    private void transition(float seconds) {
        pendingDuration=seconds;
    }
    private void compose(float seconds) {
        float blend=Math.clamp(elapsed/duration,0,1);elapsed=0;duration=seconds;
        for(Part part:parts){if(part.lod!=lod)continue;
            if(part.needsCompose(stage,regions)){part.compose(stage,regions,Math.clamp(part.elapsed/part.duration,0,1));part.elapsed=0;part.duration=seconds;weights(part,0);}
            var m=part.geometry.getMaterial();if(m.getMaterialDef().getMaterialParam("Damage")!=null)m.setFloat("Damage",stage/4f);
            if(part.source.name().equals("headlights")||part.source.name().equals("taillights")){float[] colors=new float[part.base.length/3*4];for(int v=0;v<colors.length/4;v++){float energy=lampDamage[part.base[v*3]<0?0:1],light=stage==4?.015f:Math.max(.015f,1-energy*1.4f);Arrays.fill(colors,v*4,v*4+3,light);colors[v*4+3]=1;}part.geometry.getMesh().setBuffer(VertexBuffer.Type.Color,4,colors);}
        }
    }
    void advance(float dt,Camera camera) {
        if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Nonnegative finite presentation dt required");
        if(pendingDuration>=0){float seconds=pendingDuration;pendingDuration=-1;compose(seconds);}marks.flush();
        if(dt>0){if(elapsed<duration)elapsed=Math.min(duration,elapsed+dt);for(Part part:parts)if(part.lod==lod&&part.elapsed<part.duration){part.elapsed=Math.min(part.duration,part.elapsed+dt);weights(part,Math.clamp(part.elapsed/part.duration,0,1));}mounts.update(dt);}
        if(camera!=null){float minimum=Float.POSITIVE_INFINITY,maximum=Float.NEGATIVE_INFINITY;
            for(Vector3f corner:silhouetteCorners){root.localToWorld(corner,worldCorner);camera.getScreenCoordinates(worldCorner,screenCorner);minimum=Math.min(minimum,screenCorner.y);maximum=Math.max(maximum,screenCorner.y);}
            float pixels=maximum-minimum;
            int next=lod;if(lod==0&&pixels<180*.85f)next=pixels<60*.85f?2:1;else if(lod==1){if(pixels>180*1.15f)next=0;else if(pixels<60*.85f)next=2;}else if(lod==2&&pixels>60*1.15f)next=pixels>180*1.15f?0:1;
            if(next!=lod)selectLod(next);}
        if(profile.id().equals("grinder"))for(String side:List.of("left","right")){Spatial near=root.getChild("grinder-roller-"+side);for(int level=1;level<3;level++)root.getChild("grinder-roller-"+side+"-lod"+level).setLocalRotation(near.getLocalRotation());}
    }
    private static void weights(Part part,float blend){if(!part.deforms)return;float[] w=part.geometry.getMorphState();w[0]=1-blend;w[1]=blend;part.geometry.setMorphState(w);for(Geometry g:part.overlays){float[] ow=g.getMorphState();ow[0]=w[0];ow[1]=w[1];g.setMorphState(ow);}}
    private void selectLod(int value){int previousLod=lod;boolean changed=lod!=value;lod=value;for(int i=0;i<3;i++){var c=i==lod?Spatial.CullHint.Inherit:Spatial.CullHint.Always;levels[i].setCullHint(c);frostLevels[i].setCullHint(c);shieldLevels[i].setCullHint(c);}for(int i=0;i<wheelGeometry.size();i++)wheelGeometry.get(i).setMesh(wheelLods.get(i)[lod]);root.setUserData("vehicleLod",lod);
        if(changed)for(Part part:parts)if(part.lod==lod){Part old=findPart(previousLod,part.source.name());part.inheritTransition(old);if(part.deforms)weights(part,Math.clamp(part.elapsed/part.duration,0,1));var material=part.geometry.getMaterial();if(material.getMaterialDef().getMaterialParam("Damage")!=null)material.setFloat("Damage",stage/4f);}
    }
    private Part findPart(int level,String name){for(Part part:parts)if(part.lod==level&&part.source.name().equals(name))return part;throw new IllegalStateException("Missing prepared LOD part "+name);}
    private void updateDetached(){for(Part p:parts)if(PANELS.contains(p.source.name())){var c=detached.contains(p.source.name())?Spatial.CullHint.Always:Spatial.CullHint.Inherit;p.geometry.setCullHint(c);for(Geometry g:p.overlays)g.setCullHint(c);}}
    void effects(boolean frozen,boolean shielded){frost.setCullHint(frozen&&!shielded&&stage<4?Spatial.CullHint.Inherit:Spatial.CullHint.Always);shield.setCullHint(shielded&&stage<4?Spatial.CullHint.Inherit:Spatial.CullHint.Always);}
    List<VehicleVisual.DetachedPanel> drainDetached(){var result=List.copyOf(pending);pending.clear();return result;}
    int markCount(){return marks.count();}float regionDamage(int region){return regions[region];}long repairRevision(){return repairRevision;}
    void close(){marks.close();for(Part part:parts)part.close();parts.clear();pending.clear();seen.clear();contactAnchors.clear();collisionResults.clear();detached.clear();mounts.reset();root.detachAllChildren();}
    GameEvent refineContact(GameEvent event) {
        if(event.vehicleContact()==null)return event;Anchor a=contactAnchor(event);if(a==null)return event;
        ContactSurface surface=a.part.source.name().equals("glass")?ContactSurface.GLASS:a.part.source.name().contains("rubber")?ContactSurface.RUBBER:ContactSurface.METAL;
        return event.withContact(surface,new VehicleContact(a.point,a.normal));
    }
    private Anchor contactAnchor(GameEvent event){String key=event.eventId()+":"+event.subjectId();Anchor result=contactAnchors.get(key);if(result!=null)return result;result=anchor(event.vehicleContact().localPoint(),event.vehicleContact().localNormal());if(result!=null){contactAnchors.put(key,result);if(contactAnchors.size()>128)contactAnchors.remove(contactAnchors.keySet().iterator().next());}return result;}
    private Anchor anchor(Vector3f point,Vector3f normal) {
        if(normal.lengthSquared()<.01f)return null;Vector3f direction=normal.normalize();float reach=Math.max(1,profile.width());Ray ray=new Ray(point.add(direction.mult(reach)),direction.negate());ray.setLimit(reach*2);
        Part chosen=null;com.jme3.collision.CollisionResult contact=null;float nearest=Float.POSITIVE_INFINITY;
        for(Part part:parts)if(part.lod==0&&!part.source.name().startsWith("mount-")&&!part.source.name().startsWith("grinder-")&&!detached.contains(part.source.name())) {
            collisionResults.clear();part.canonical.collideWith(ray,collisionResults);
            for(var candidate:collisionResults){float distance=candidate.getContactPoint().distanceSquared(point);if(distance<nearest&&candidate.getContactNormal().dot(direction)>.05f){nearest=distance;chosen=part;contact=candidate;}}
        }
        if(chosen==null)return null;int triangle=contact.getTriangleIndex();Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f();chosen.source.stages()[0].getTriangle(triangle,a,b,c);Vector3f p=contact.getContactPoint(),v0=b.subtract(a),v1=c.subtract(a),v2=p.subtract(a);
        float d00=v0.dot(v0),d01=v0.dot(v1),d11=v1.dot(v1),d20=v2.dot(v0),d21=v2.dot(v1),den=d00*d11-d01*d01;if(Math.abs(den)<1e-10)return null;
        float wb=(d11*d20-d01*d21)/den,wc=(d00*d21-d01*d20)/den,wa=1-wb-wc;var indices=chosen.source.stages()[0].getIndicesAsList();int ia=indices.get(triangle*3),ib=indices.get(triangle*3+1),ic=indices.get(triangle*3+2);var tex=chosen.source.stages()[0].getFloatBuffer(VertexBuffer.Type.TexCoord);
        Vector2f uv=new Vector2f(tex.get(ia*2)*wa+tex.get(ib*2)*wb+tex.get(ic*2)*wc,tex.get(ia*2+1)*wa+tex.get(ib*2+1)*wb+tex.get(ic*2+1)*wc);
        return new Anchor(chosen,triangle,wa,wb,wc,p.clone(),contact.getContactNormal().clone(),uv);
    }
    private static float[] positions(Mesh mesh){var b=mesh.getFloatBuffer(VertexBuffer.Type.Position);float[] p=new float[b.limit()];for(int i=0;i<p.length;i++)p[i]=b.get(i);return p;}
    private static float[] read3(Mesh mesh,VertexBuffer.Type type){float[] result=new float[mesh.getVertexCount()*3];copy3(mesh,type,result);return result;}
    private static void copy3(Mesh mesh,VertexBuffer.Type type,float[] out){FloatBuffer source=mesh.getFloatBuffer(type);int stride=type==VertexBuffer.Type.Tangent?4:3;for(int v=0;v<out.length/3;v++)for(int k=0;k<3;k++)out[v*3+k]=source.get(v*stride+k);}
    private static void basis(float[] p,FloatBuffer uv,int triangle,float[] normal,float[] tangent) {
        int i=triangle*9,u=triangle*6;float x1=p[i+3]-p[i],y1=p[i+4]-p[i+1],z1=p[i+5]-p[i+2],x2=p[i+6]-p[i],y2=p[i+7]-p[i+1],z2=p[i+8]-p[i+2];
        float nx=y1*z2-z1*y2,ny=z1*x2-x1*z2,nz=x1*y2-y1*x2;float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(length<1e-8f)return;nx/=length;ny/=length;nz/=length;
        float du1=uv.get(u+2)-uv.get(u),dv1=uv.get(u+3)-uv.get(u+1),du2=uv.get(u+4)-uv.get(u),dv2=uv.get(u+5)-uv.get(u+1),den=du1*dv2-du2*dv1;
        float tx=tangent[i],ty=tangent[i+1],tz=tangent[i+2];if(Math.abs(den)>1e-8f){tx=(x1*dv2-x2*dv1)/den;ty=(y1*dv2-y2*dv1)/den;tz=(z1*dv2-z2*dv1)/den;}
        float dot=tx*nx+ty*ny+tz*nz;tx-=nx*dot;ty-=ny*dot;tz-=nz*dot;length=(float)Math.sqrt(tx*tx+ty*ty+tz*tz);if(length>1e-8f){tx/=length;ty/=length;tz/=length;}
        for(int k=0;k<3;k++){normal[i+k*3]=nx;normal[i+k*3+1]=ny;normal[i+k*3+2]=nz;tangent[i+k*3]=tx;tangent[i+k*3+1]=ty;tangent[i+k*3+2]=tz;}
    }
    @Override protected void controlUpdate(float dt){}
    @Override protected void controlRender(RenderManager manager,ViewPort viewport){}
}
