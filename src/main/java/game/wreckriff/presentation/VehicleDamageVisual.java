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
        final VehicleModelData.Part source;final Geometry geometry;final int lod;
        final float[] base,from,to;final MorphTarget previous=new MorphTarget("previous"),next=new MorphTarget("next");
        final List<Geometry> overlays=new ArrayList<>();
        Part(VehicleModelData.Part source,Geometry geometry,int lod) {
            this.source=source;this.geometry=geometry;this.lod=lod;base=positions(source.stages()[0]);from=base.clone();to=base.clone();
            Mesh mesh=source.stages()[0].clone();mesh.addMorphTarget(previous);mesh.addMorphTarget(next);geometry.setMesh(mesh);
            for(MorphTarget target:List.of(previous,next))for(var kind:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.Tangent))target.setBuffer(kind,BufferUtils.createFloatBuffer(new float[base.length]));
            geometry.setMorphState(new float[]{1,0});geometry.setNbSimultaneousGPUMorph(2);
            if(source.name().equals("headlights")||source.name().equals("taillights")){float[] colors=new float[base.length/3*4];Arrays.fill(colors,1);mesh.setBuffer(VertexBuffer.Type.Color,4,colors);}
            BoundingBox bound=(BoundingBox)source.stages()[0].getBound().clone();bound.setXExtent(bound.getXExtent()+MAX_DENT);bound.setYExtent(bound.getYExtent()+MAX_DENT);bound.setZExtent(bound.getZExtent()+MAX_DENT);mesh.setBound(bound);
        }
        void targets(){target(previous,from);target(next,to);geometry.setDirtyMorph(true);}
        private void target(MorphTarget target,float[] point) {
            float[] delta=new float[point.length];for(int i=0;i<delta.length;i++)delta[i]=point[i]-base[i];target.setBuffer(VertexBuffer.Type.Position,BufferUtils.createFloatBuffer(delta));
            float[] n=normals(point);FloatBuffer original=source.stages()[0].getFloatBuffer(VertexBuffer.Type.Normal);float[] dn=n.clone();for(int i=0;i<dn.length;i++)dn[i]-=original.get(i);target.setBuffer(VertexBuffer.Type.Normal,BufferUtils.createFloatBuffer(dn));
            Mesh posed=new Mesh();posed.setBuffer(VertexBuffer.Type.Position,3,point);posed.setBuffer(VertexBuffer.Type.Normal,3,n);posed.setBuffer(source.stages()[0].getBuffer(VertexBuffer.Type.TexCoord));com.jme3.util.mikktspace.MikktspaceTangentGenerator.generate(posed);
            FloatBuffer tangent=posed.getFloatBuffer(VertexBuffer.Type.Tangent),baseTangent=source.stages()[0].getFloatBuffer(VertexBuffer.Type.Tangent);float[] dt=new float[point.length];for(int v=0;v<point.length/3;v++)for(int k=0;k<3;k++)dt[v*3+k]=tangent.get(v*4+k)-baseTangent.get(v*4+k);target.setBuffer(VertexBuffer.Type.Tangent,BufferUtils.createFloatBuffer(dt));
        }
    }
    private final Node root;private final VehicleProfile profile;private final VehicleDamageMarks marks;private final WeaponMountVisual mounts;
    private final List<Part> parts=new ArrayList<>();private final Node[] levels=new Node[3],frostLevels=new Node[3],shieldLevels=new Node[3];
    private final Node frost=new Node("frost-overlay"),shield=new Node("shield-shell");private final float[] regions=new float[8];
    private final Set<String> detached=new HashSet<>();private final List<VehicleVisual.DetachedPanel> pending=new ArrayList<>();private final LinkedHashSet<String> seen=new LinkedHashSet<>();
    private int stage,lod;private float elapsed=1,duration=DAMAGE_SECONDS,pendingDuration=-1,maximumHp=Float.NaN;private long repairRevision;
    private final float[] lampDamage=new float[2];
    private record Anchor(Part part,int triangle,float a,float b,float c,Vector3f point,Vector3f normal,Vector2f uv) {}
    static void install(AssetManager assets,Node root,VehicleProfile profile,int livery){root.addControl(new VehicleDamageVisual(assets,root,profile,livery));root.addControl(new MorphControl());}
    private VehicleDamageVisual(AssetManager assets,Node root,VehicleProfile profile,int livery) {
        this.root=root;this.profile=profile;marks=new VehicleDamageMarks(profile);mounts=new WeaponMountVisual(root);var model=VehicleModelData.load(assets,profile.id());
        for(int level=0;level<3;level++) {
            levels[level]=new Node("lod"+level);root.attachChild(levels[level]);frostLevels[level]=new Node("frost-lod"+level);shieldLevels[level]=new Node("shield-lod"+level);frost.attachChild(frostLevels[level]);shield.attachChild(shieldLevels[level]);
            for(var data:model.lods.get(level)) {
                String name=data.name();if(name.startsWith("wheel-"))continue;Geometry geometry=new Geometry(name);geometry.setMaterial(VehicleMaterials.create(assets,profile.id(),name));
                var material=geometry.getMaterial();if(material.getMaterialDef().getMaterialParam("DamageMap")!=null)material.setTexture("DamageMap",marks.texture);
                if(name.equals("paint")||name.startsWith("panel-")) {ColorRGBA tint=switch(Math.floorMod(livery,5)){case 1->new ColorRGBA(.8f,.72f,1,1);case 2->new ColorRGBA(1,.85f,.5f,1);case 3->new ColorRGBA(.5f,1,1,1);case 4->new ColorRGBA(.72f,1,.55f,1);default->ColorRGBA.White;};material.setColor("Diffuse",tint);material.setColor("Ambient",tint);}
                Part part=new Part(data,geometry,level);parts.add(part);
                if(name.startsWith("grinder-roller-")){Node roller=new Node(level==0?name:name+"-lod"+level);roller.attachChild(geometry);levels[level].attachChild(roller);geometry.setName(name+"-mesh");}else levels[level].attachChild(geometry);
                if(name.equals("service-core"))geometry.setCullHint(Spatial.CullHint.Always);
                if(name.equals("paint")||name.equals("glass")||name.startsWith("panel-")){status(assets,part,frostLevels[level],"frost-",new ColorRGBA(.31f,.72f,.93f,.32f));status(assets,part,shieldLevels[level],"shield-",new ColorRGBA(.055f,.48f,1,.14f));}
            }
        }
        for(int wheel=0;wheel<4;wheel++) {
            Node axle=new Node("wheel-"+wheel);axle.setLocalScale(profile.wheelRadius()/.38f);axle.setLocalTranslation(profile.wheelConnection(wheel).add(0,-profile.suspensionRestLength(),0));
            for(var data:model.lods.get(0))if(data.name().startsWith("wheel-"+wheel+"-")){Geometry g=new Geometry(data.name(),data.stages()[0]);g.setMaterial(VehicleMaterials.create(assets,profile.id(),data.name().endsWith("tyre")?"rubber-trim":"steel"));axle.attachChild(g);}root.attachChild(axle);
        }
        root.attachChild(frost);root.attachChild(shield);effects(false,false);selectLod(0);root.setUserData("damageStage",0);root.setUserData("repairRevision",0L);
    }
    private static void status(AssetManager assets,Part part,Node parent,String prefix,ColorRGBA color) {
        Geometry overlay=new Geometry(prefix+part.source.name(),part.geometry.getMesh());Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");material.setColor("Color",color);material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);material.getAdditionalRenderState().setDepthWrite(false);material.getAdditionalRenderState().setPolyOffset(-1,-1);
        overlay.setMaterial(material);overlay.setQueueBucket(RenderQueue.Bucket.Transparent);overlay.setShadowMode(RenderQueue.ShadowMode.Off);overlay.setMorphState(new float[]{1,0});overlay.setNbSimultaneousGPUMorph(2);parent.attachChild(overlay);part.overlays.add(overlay);
    }
    static int stage(float hp){if(!Float.isFinite(hp))throw new IllegalArgumentException("Finite HP fraction required");return hp<=0?4:hp<=.25f?3:hp<=.5f?2:hp<=.75f?1:0;}
    void configureMaximumHp(float value){if(!Float.isFinite(value)||value<=0)throw new IllegalArgumentException("Positive authoritative maximum HP required");maximumHp=value;}
    void damage(float hp){int next=stage(hp);if(next==stage)return;stage=next;root.setUserData("damageStage",stage);transition(duration==REPAIR_SECONDS&&elapsed<duration?REPAIR_SECONDS:DAMAGE_SECONDS);if(stage==4)effects(false,false);}
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
        Vector3f p=event.vehicleContact().localPoint(),n=event.vehicleContact().localNormal();int region=region(p);float force=Math.clamp(event.value()/80,.06f,.65f);regions[region]=Math.min(1,regions[region]+force);
        if(p.z>profile.length()*.3f)lampDamage[p.x<0?0:1]=Math.min(1,lampDamage[p.x<0?0:1]+force);
        Anchor anchor=anchor(p,n);if(anchor!=null)marks.hit(anchor.point,anchor.normal,anchor.uv,force,event.kind().contains("napalm")||event.kind().contains("fire"),anchor.part.source.name().equals("glass"),event.eventId());
        transition(DAMAGE_SECONDS);
        if(stage>=2&&regions[region]>=.65f){String panel=region==2?PANELS.get(0):region==3?PANELS.get(1):region<2||region==7?PANELS.get(2):region==4||region==5?PANELS.get(3):null;
            if(panel!=null&&detached.add(panel))for(Part part:parts)if(part.lod==0&&part.source.name().equals(panel)){BoundingBox box=(BoundingBox)part.source.stages()[stage].getBound();pending.add(new VehicleVisual.DetachedPanel(panel,box.getCenter().clone(),new Quaternion(),new Vector3f(box.getXExtent(),box.getYExtent(),box.getZExtent()),n.mult(2.5f).addLocal(0,1.5f,0)));break;}updateDetached();}
    }
    private int region(Vector3f p){if(p.y>profile.height()*.68f)return 6;float z=p.z/profile.length();if(z>.24f)return p.x<0?0:1;if(z<-.24f)return p.x<0?4:5;return p.x<0?2:3;}
    private void transition(float seconds) {
        pendingDuration=seconds;
    }
    private void compose(float seconds) {
        float blend=Math.clamp(elapsed/duration,0,1);elapsed=0;duration=seconds;
        for(Part part:parts){if(part.lod!=lod)continue;for(int i=0;i<part.from.length;i++)part.from[i]+=(part.to[i]-part.from[i])*blend;FloatBuffer target=part.source.stages()[stage].getFloatBuffer(VertexBuffer.Type.Position);for(int i=0;i<part.to.length;i++)part.to[i]=target.get(i);
            for(int region=0;region<8;region++)if(regions[region]>0){var d=part.source.regions()[region];for(int j=0;j<d.indices().length;j++)for(int k=0;k<3;k++)part.to[d.indices()[j]*3+k]+=d.xyz()[j*3+k]*regions[region];}
            for(int i=0;i<part.to.length;i+=3){float x=part.to[i]-part.base[i],y=part.to[i+1]-part.base[i+1],z=part.to[i+2]-part.base[i+2],len=(float)Math.sqrt(x*x+y*y+z*z);if(len>MAX_DENT){float r=MAX_DENT/len;part.to[i]=part.base[i]+x*r;part.to[i+1]=part.base[i+1]+y*r;part.to[i+2]=part.base[i+2]+z*r;}}
            part.targets();weights(part,0);var m=part.geometry.getMaterial();if(m.getMaterialDef().getMaterialParam("Damage")!=null)m.setFloat("Damage",stage/4f);
            if(part.source.name().equals("headlights")||part.source.name().equals("taillights")){float[] colors=new float[part.base.length/3*4];for(int v=0;v<colors.length/4;v++){float energy=lampDamage[part.base[v*3]<0?0:1],light=stage==4?.015f:Math.max(.015f,1-energy*1.4f);Arrays.fill(colors,v*4,v*4+3,light);colors[v*4+3]=1;}part.geometry.getMesh().setBuffer(VertexBuffer.Type.Color,4,colors);}
        }
    }
    void advance(float dt,Camera camera) {
        if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Nonnegative finite presentation dt required");
        if(pendingDuration>=0){float seconds=pendingDuration;pendingDuration=-1;compose(seconds);}marks.flush();
        if(dt>0){elapsed=Math.min(duration,elapsed+dt);float blend=Math.clamp(elapsed/duration,0,1);for(Part part:parts)if(part.lod==lod)weights(part,blend);mounts.update(dt);}
        if(camera!=null){float depth=Math.max(camera.getFrustumNear(),root.getWorldTranslation().subtract(camera.getLocation()).dot(camera.getDirection()));float size=Math.max(profile.length(),profile.height());
            float pixels=size*camera.getFrustumNear()*camera.getHeight()/(Math.max(.001f,camera.getFrustumTop()-camera.getFrustumBottom())*depth);
            int next=lod;if(lod==0&&pixels<180*.85f)next=pixels<60*.85f?2:1;else if(lod==1){if(pixels>180*1.15f)next=0;else if(pixels<60*.85f)next=2;}else if(lod==2&&pixels>60*1.15f)next=pixels>180*1.15f?0:1;
            if(next!=lod)selectLod(next);}
    }
    private static void weights(Part part,float blend){float[] w=part.geometry.getMorphState();w[0]=1-blend;w[1]=blend;part.geometry.setMorphState(w);for(Geometry g:part.overlays){g.setMorphState(w.clone());g.setDirtyMorph(true);}}
    private void selectLod(int value){boolean changed=lod!=value;lod=value;for(int i=0;i<3;i++){var c=i==lod?Spatial.CullHint.Inherit:Spatial.CullHint.Always;levels[i].setCullHint(c);frostLevels[i].setCullHint(c);shieldLevels[i].setCullHint(c);}root.setUserData("vehicleLod",lod);if(changed){compose(DAMAGE_SECONDS);elapsed=duration;for(Part p:parts)if(p.lod==lod)weights(p,1);}}
    private void updateDetached(){for(Part p:parts)if(PANELS.contains(p.source.name())){var c=detached.contains(p.source.name())?Spatial.CullHint.Always:Spatial.CullHint.Inherit;p.geometry.setCullHint(c);for(Geometry g:p.overlays)g.setCullHint(c);}}
    void effects(boolean frozen,boolean shielded){frost.setCullHint(frozen&&stage<4?Spatial.CullHint.Inherit:Spatial.CullHint.Always);shield.setCullHint(shielded&&stage<4?Spatial.CullHint.Inherit:Spatial.CullHint.Always);}
    List<VehicleVisual.DetachedPanel> drainDetached(){var result=List.copyOf(pending);pending.clear();return result;}
    int markCount(){return marks.count();}float regionDamage(int region){return regions[region];}long repairRevision(){return repairRevision;}
    void close(){marks.close();parts.clear();pending.clear();seen.clear();detached.clear();mounts.reset();root.detachAllChildren();}
    GameEvent refineContact(GameEvent event) {
        if(event.vehicleContact()==null)return event;Anchor a=anchor(event.vehicleContact().localPoint(),event.vehicleContact().localNormal());if(a==null)return event;
        ContactSurface surface=a.part.source.name().equals("glass")?ContactSurface.GLASS:a.part.source.name().contains("rubber")?ContactSurface.RUBBER:ContactSurface.METAL;
        return event.withContact(surface,new VehicleContact(a.point,a.normal)).forPresentation(root.localToWorld(a.point,null),root.getWorldRotation().mult(a.normal));
    }
    private Anchor anchor(Vector3f point,Vector3f normal) {
        if(normal.lengthSquared()<.01f)return null;Vector3f direction=normal.normalize();float reach=Math.max(1,profile.width());Ray ray=new Ray(point.add(direction.mult(reach)),direction.negate());ray.setLimit(reach*2);
        Part chosen=null;com.jme3.collision.CollisionResult contact=null;float nearest=Float.POSITIVE_INFINITY;
        for(Part part:parts)if(part.lod==0&&!part.source.name().startsWith("mount-")&&!part.source.name().startsWith("grinder-")&&!detached.contains(part.source.name())) {
            Geometry canonical=new Geometry(part.source.name(),part.source.stages()[0]);canonical.updateGeometricState();CollisionResults results=new CollisionResults();canonical.collideWith(ray,results);
            for(var candidate:results){float distance=candidate.getContactPoint().distanceSquared(point);if(distance<nearest&&candidate.getContactNormal().dot(direction)>.05f){nearest=distance;chosen=part;contact=candidate;}}
        }
        if(chosen==null)return null;int triangle=contact.getTriangleIndex();Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f();chosen.source.stages()[0].getTriangle(triangle,a,b,c);Vector3f p=contact.getContactPoint(),v0=b.subtract(a),v1=c.subtract(a),v2=p.subtract(a);
        float d00=v0.dot(v0),d01=v0.dot(v1),d11=v1.dot(v1),d20=v2.dot(v0),d21=v2.dot(v1),den=d00*d11-d01*d01;if(Math.abs(den)<1e-10)return null;
        float wb=(d11*d20-d01*d21)/den,wc=(d00*d21-d01*d20)/den,wa=1-wb-wc;var indices=chosen.source.stages()[0].getIndicesAsList();int ia=indices.get(triangle*3),ib=indices.get(triangle*3+1),ic=indices.get(triangle*3+2);var tex=chosen.source.stages()[0].getFloatBuffer(VertexBuffer.Type.TexCoord);
        Vector2f uv=new Vector2f(tex.get(ia*2)*wa+tex.get(ib*2)*wb+tex.get(ic*2)*wc,tex.get(ia*2+1)*wa+tex.get(ib*2+1)*wb+tex.get(ic*2+1)*wc);
        return new Anchor(chosen,triangle,wa,wb,wc,p.clone(),contact.getContactNormal().clone(),uv);
    }
    private static float[] positions(Mesh mesh){var b=mesh.getFloatBuffer(VertexBuffer.Type.Position);float[] p=new float[b.limit()];for(int i=0;i<p.length;i++)p[i]=b.get(i);return p;}
    private static float[] normals(float[] p){float[] out=new float[p.length];for(int i=0;i<p.length;i+=9){Vector3f a=new Vector3f(p[i],p[i+1],p[i+2]),b=new Vector3f(p[i+3],p[i+4],p[i+5]),c=new Vector3f(p[i+6],p[i+7],p[i+8]);Vector3f n=b.subtractLocal(a).crossLocal(c.subtractLocal(a)).normalizeLocal();for(int k=0;k<3;k++){out[i+k*3]=n.x;out[i+k*3+1]=n.y;out[i+k*3+2]=n.z;}}return out;}
    @Override protected void controlUpdate(float dt){}
    @Override protected void controlRender(RenderManager manager,ViewPort viewport){}
}
