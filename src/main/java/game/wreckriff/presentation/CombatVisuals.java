package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.control.AbstractControl;
import com.jme3.util.BufferUtils;
import game.wreckriff.combat.ProjectileState;
import game.wreckriff.combat.CombatSystem;
import game.wreckriff.simulation.*;
import java.nio.FloatBuffer;
import java.util.*;

/** Bounded, presentation-only effects. Four shared draws; no physics bodies or dynamic lights. */
public final class CombatVisuals implements AutoCloseable {
    public static final int PARTICLE_LIMIT=384, RING_LIMIT=8, PROJECTILE_LIMIT=64;
    private static final ColorRGBA AMBER=new ColorRGBA(1,.62f,.10f,1), HOT=new ColorRGBA(1,.24f,.035f,1);
    private static final ColorRGBA ION=new ColorRGBA(.12f,.9f,1,1), SMOKE=new ColorRGBA(.17f,.18f,.19f,.24f);
    private static final float SMOKE_RADIUS_LIMIT=.55f, FLASH_RADIUS_LIMIT=.68f;
    private static final float[] SPRITE_UV={0,0,1,0,1,1,0,0,1,1,0,1};
    private record Ring(Vector3f centre,float radius,float lifetime,float age) {}
    private static final class Particle {
        final Vector3f position,velocity;
        final ColorRGBA color;
        final boolean smoke;
        final float lifetime,size,growth,gravity;
        float age;
        Particle(Vector3f position,Vector3f velocity,ColorRGBA color,float lifetime,float size,float growth,float gravity) {
            this.position=position.clone();this.velocity=velocity.clone();this.smoke=color==SMOKE;this.color=color.clone();
            this.lifetime=lifetime;this.size=size;this.growth=growth;this.gravity=gravity;
        }
    }
    private static final class Tracer {
        final Vector3f from,to;
        float remaining=.055f;
        Tracer(Vector3f from,Vector3f to){this.from=from;this.to=to;}
    }
    private final Node root=new Node("combat-visuals");
    private final WorldQuery world;
    private final List<Particle> particles=new ArrayList<>();
    private final List<Ring> rings=new ArrayList<>();
    private final List<Tracer> tracers=new ArrayList<>();
    private final Map<Long,Vector3f> trailHeads=new HashMap<>();
    private final float[] previousTurbo={100,100,100,100,100},smokeClock=new float[5],turboClock=new float[5];
    private final Random visualRandom=new Random(0x56495355414cL); // Never touches combat RNG.
    private final Batch particleBatch,rocketBatch,ringBatch,fieldBatch;
    private List<CombatSystem.MineView> mines=List.of();
    private List<CombatSystem.FireZoneView> fires=List.of();
    private MatchSession session;
    private float flameClock;
    private List<ProjectileState> projectiles=List.of();
    private boolean closed;

    public CombatVisuals(AssetManager assets,Node scene,WorldQuery world) {
        this.world=Objects.requireNonNull(world);
        particleBatch=new Batch(root,"particles-and-tracers",assets,PARTICLE_LIMIT*6+64*12,true,true);
        rocketBatch=new Batch(root,"rocket-models",assets,PROJECTILE_LIMIT*120+10*500,false,false);
        ringBatch=new Batch(root,"pulse-rings",assets,RING_LIMIT*64*6,true,false);
        fieldBatch=new Batch(root,"control-and-fire-fields",assets,20000,true,false);
        particleBatch.geometry.addControl(new AbstractControl() {
            @Override protected void controlUpdate(float dt) { }
            @Override protected void controlRender(RenderManager manager,ViewPort view) {
                // A transparent mesh is sorted as one object by jME, so order its sprites here.
                // This uses the actual render camera, including rear view, without owning a camera.
                if(!closed)renderParticles(view.getCamera());
            }
        });
        scene.attachChild(root);
    }

    public void accept(List<GameEvent> events) {
        if(closed)return;
        for(GameEvent event:events) {
            switch(event.type()) {
                case SHOT -> {
                    if("machine-gun".equals(event.kind())) {
                        Vector3f muzzle=world.muzzle(event.sourceId());
                        if(tracers.size()<64)tracers.add(new Tracer(muzzle,event.position().clone()));
                        emit(muzzle,world.forward(event.sourceId()).mult(1.5f),AMBER,.045f,.15f,3,0);
                        // Endpoint is the authoritative hitscan intersection, never a guessed target.
                        for(int i=0;i<3;i++)emit(event.position(),randomDirection(3),AMBER,.12f,.035f,0,6);
                    } else emit(event.position(),world.forward(event.sourceId()).mult(-3),HOT,.15f,.25f,2,0);
                }
                case EXPLOSION -> explosion(event.position(),"power".equals(event.kind())||"mine".equals(event.kind())?1.35f:1);
                case FREEZE -> {for(int i=0;i<18;i++)emit(event.position(),randomDirection(3),ION,.4f,.08f,.05f,1);}
                case STUN -> {for(int i=0;i<18;i++)emit(event.position(),randomDirection(4),new ColorRGBA(.65f,.33f,1,1),.3f,.06f,.1f,0);}
                case SHIELD -> {for(int i=0;i<14;i++)emit(event.position(),randomDirection(2),new ColorRGBA(.15f,.62f,1,1),.35f,.10f,.1f,0);}
                case DESTROYED -> explosion(event.position(),1.8f);
                case PULSE -> {
                    if(rings.size()>=RING_LIMIT)rings.remove(0);
                    rings.add(new Ring(event.position().clone(),Math.max(1,event.value()),.52f,0));
                    for(int i=0;i<10;i++)emit(event.position().add(0,.2f,0),randomDirection(5),ION,.25f,.09f,1,0);
                }
                case DAMAGE -> { if(!"hazard".equals(event.kind()) && !"recovery".equals(event.kind()))
                    for(int i=0;i<Math.min(8,2+(int)event.value()/6);i++)
                        emit(event.position().add(0,.35f,0),randomDirection(4),AMBER,.22f,.045f,0,8); }
                case PICKUP -> {
                    ColorRGBA colour="repair".equals(event.kind())?new ColorRGBA(.25f,1,.3f,1):ION;
                    for(int i=0;i<10;i++)emit(event.position().add(0,.5f,0),randomDirection(2).addLocal(0,2,0),colour,.38f,.06f,.3f,0);
                }
                default -> { }
            }
        }
    }

    public void update(List<ProjectileState> states,List<CombatSystem.MineView> mines,
            List<CombatSystem.FireZoneView> fires,MatchSession session,float dt) {
        if(closed)return;
        dt=Math.max(0,Math.min(.1f,dt));
        projectiles=states;this.mines=mines;this.fires=fires;this.session=session;
        flameClock-=dt;
        if(flameClock<=0) {
            for(var fire:fires) {
                var points=fire.surfacePoints();
                for(int i=0;i<Math.min(12,points.size());i++) {
                    Vector3f at=points.get(visualRandom.nextInt(points.size())).add(fire.normal().mult(.1f));
                    emit(at,fire.normal().mult(1.5f+visualRandom.nextFloat()*1.5f),i%3==0?AMBER:HOT,.30f,.18f,-.15f,0);
                    if(i%4==0)emit(at.add(0,.4f,0),new Vector3f(.15f,.65f,0),SMOKE,.6f,.20f,.25f,0);
                }
            }
            flameClock=.065f;
        }
        for(Iterator<Particle> it=particles.iterator();it.hasNext();) {
            Particle p=it.next();p.age+=dt;
            if(p.age>=p.lifetime){it.remove();continue;}
            p.velocity.y-=p.gravity*dt;p.position.addLocal(p.velocity.mult(dt));
        }
        for(Iterator<Tracer> it=tracers.iterator();it.hasNext();) {Tracer t=it.next();t.remaining-=dt;if(t.remaining<=0)it.remove();}
        for(int i=rings.size()-1;i>=0;i--) {
            Ring ring=rings.get(i);float age=ring.age+dt;
            if(age>=ring.lifetime)rings.remove(i);else rings.set(i,new Ring(ring.centre,ring.radius,ring.lifetime,age));
        }
        Set<Long> live=new HashSet<>();
        for(ProjectileState rocket:states) {
            live.add(rocket.id());Vector3f position=rocket.position();
            Vector3f previous=trailHeads.get(rocket.id());
            if(previous==null)previous=rocket.previousPosition();
            Vector3f delta=position.subtract(previous);float distance=delta.length();
            float spacing="power".equals(rocket.kind())?.62f:.48f;
            int steps=Math.min(10,(int)(distance/spacing));
            if(steps>0) {
                for(int i=1;i<=steps;i++) {
                    Vector3f at=previous.add(delta.mult(i/(float)steps));
                    if("power".equals(rocket.kind())||"napalm".equals(rocket.kind())) {
                        emit(at,new Vector3f(0,.45f,0),SMOKE,.42f,.11f,.35f,0);
                        if(i%2==0)emit(at,randomDirection(.45f),HOT,.18f,.09f,.2f,0);
                    } else emit(at,Vector3f.ZERO,ION,.25f,.065f,.12f,0);
                }
                trailHeads.put(rocket.id(),position);
            } else if(!trailHeads.containsKey(rocket.id()))trailHeads.put(rocket.id(),previous);
        }
        trailHeads.keySet().retainAll(live);
        if(session!=null)for(VehicleState vehicle:session.vehicles) {
            int id=vehicle.id;smokeClock[id]-=dt;turboClock[id]-=dt;
            if(vehicle.alive() && vehicle.hp/vehicle.maximumHp<1f/3 && smokeClock[id]<=0) {
                Vector3f bonnet=world.position(id).add(world.rotation(id).mult(new Vector3f(0,.6f,.9f)));
                emit(bonnet,new Vector3f(0,1.15f,0).addLocal(world.velocity(id).mult(.12f)),SMOKE,.78f,.12f,.4f,0);
                smokeClock[id]=.12f;
            }
            if(vehicle.alive() && vehicle.turbo<previousTurbo[id]-.01f && turboClock[id]<=0) {
                for(float side:new float[]{-.72f,.72f}) {
                    Vector3f exhaust=world.position(id).add(world.rotation(id).mult(new Vector3f(side,-.11f,-2.49f)));
                    emit(exhaust,world.forward(id).mult(-5).addLocal(world.velocity(id).mult(.2f)),HOT,.16f,.16f,-.7f,0);
                }
                turboClock[id]=.025f;
            }
            previousTurbo[id]=vehicle.turbo;
        }
        render();
    }

    private void explosion(Vector3f centre,float scale) {
        for(int i=0;i<24;i++) {
            ColorRGBA color=i<12?HOT:i<18?AMBER:SMOKE;
            Vector3f velocity=randomDirection((i<18?4:2)*scale);velocity.y=Math.abs(velocity.y)+.7f;
            emit(centre,velocity,color,(i<18?.30f:.45f)*scale,(i<18?.14f:.16f)*scale,i<18?.7f:.4f,i<18?4:0);
        }
    }
    private void emit(Vector3f position,Vector3f velocity,ColorRGBA color,float lifetime,float size,float growth,float gravity) {
        if(particles.size()>=PARTICLE_LIMIT)particles.remove(0);
        particles.add(new Particle(position,velocity,color,lifetime,size,growth,gravity));
    }
    private Vector3f randomDirection(float speed) {
        Vector3f result=new Vector3f(visualRandom.nextFloat()*2-1,visualRandom.nextFloat()*2-1,visualRandom.nextFloat()*2-1);
        return result.normalizeLocal().multLocal(speed);
    }
    private void render() {
        renderParticles(null);
        renderRocketsAndRings();renderFields();
    }
    private void renderParticles(Camera camera) {
        List<Particle> ordered=particles;
        if(camera!=null && particles.size()>1) {
            Vector3f eye=camera.getLocation(),forward=camera.getDirection();
            ordered=new ArrayList<>(particles);
            ordered.sort(Comparator.comparingDouble((Particle p)->
                    (p.position.x-eye.x)*forward.x+(p.position.y-eye.y)*forward.y+(p.position.z-eye.z)*forward.z).reversed());
        }
        particleBatch.begin();
        for(Particle p:ordered) {
            float size=Math.clamp(p.size+p.age*p.growth,.015f,p.smoke?SMOKE_RADIUS_LIMIT:FLASH_RADIUS_LIMIT);
            float life=p.age/p.lifetime;
            float alpha=p.color.a*(1-life)*(p.smoke?Math.min(1,life*12):1);
            particleBatch.sprite(p.position,size,p.color,alpha,p.smoke);
        }
        for(Tracer tracer:tracers) {
            Vector3f direction=tracer.to.subtract(tracer.from).normalizeLocal();
            Vector3f side=direction.cross(Vector3f.UNIT_Y).normalizeLocal().multLocal(.018f);
            particleBatch.quad(tracer.from.subtract(side),tracer.from.add(side),tracer.to.add(side),tracer.to.subtract(side),AMBER,.9f);
            side=direction.cross(side).normalizeLocal().multLocal(.018f);
            particleBatch.quad(tracer.from.subtract(side),tracer.from.add(side),tracer.to.add(side),tracer.to.subtract(side),AMBER,.9f);
        }
        particleBatch.end();
    }
    private void renderRocketsAndRings() {
        rocketBatch.begin();
        int count=0;
        for(ProjectileState rocket:projectiles) {
            if(count++>=PROJECTILE_LIMIT)break;
            boolean power="power".equals(rocket.kind()),napalm="napalm".equals(rocket.kind()),freeze="freeze".equals(rocket.kind());
            Vector3f centre=rocket.position(),direction=rocket.direction();
            Quaternion rotation=new Quaternion().lookAt(direction,Math.abs(direction.y)>.98f?Vector3f.UNIT_Z:Vector3f.UNIT_Y);
            float radius=power?.19f:napalm?.25f:freeze?.14f:.11f,tail=power?-.5f:napalm?-.28f:-.35f,nose=power?.52f:napalm?.36f:.55f;
            ColorRGBA color=power?HOT:napalm?AMBER:freeze?new ColorRGBA(.48f,.95f,1,1):ION;
            for(int i=0;i<6;i++) {
                float a=i*FastMath.TWO_PI/6,b=(i+1)*FastMath.TWO_PI/6;
                Vector3f p=local(centre,rotation,FastMath.cos(a)*radius,FastMath.sin(a)*radius,tail);
                Vector3f q=local(centre,rotation,FastMath.cos(b)*radius,FastMath.sin(b)*radius,tail);
                Vector3f r=local(centre,rotation,FastMath.cos(b)*radius,FastMath.sin(b)*radius,.23f);
                Vector3f s=local(centre,rotation,FastMath.cos(a)*radius,FastMath.sin(a)*radius,.23f);
                rocketBatch.quad(p,q,r,s,color,1);
                rocketBatch.triangle(s,r,local(centre,rotation,0,0,nose),AMBER,1);
            }
            int fins=power?4:3;
            for(int i=0;i<fins;i++) {
                float a=i*FastMath.TWO_PI/fins;
                Vector3f tip=local(centre,rotation,FastMath.cos(a)*radius*2.4f,FastMath.sin(a)*radius*2.4f,tail);
                rocketBatch.triangle(local(centre,rotation,0,0,tail),tip,local(centre,rotation,0,0,.1f),color,1);
            }
        }
        for(var mine:mines) {
            Vector3f normal=mine.normal(),centre=mine.position().add(normal.mult(.12f));
            Quaternion pose=surfaceRotation(normal);ColorRGBA body=new ColorRGBA(.17f,.18f,.20f,1);
            for(int i=0;i<12;i++) {
                float a=i*FastMath.TWO_PI/12,b=(i+1)*FastMath.TWO_PI/12;
                Vector3f p=local(centre,pose,FastMath.cos(a)*.54f,0,FastMath.sin(a)*.54f),
                        q=local(centre,pose,FastMath.cos(b)*.54f,0,FastMath.sin(b)*.54f),
                        r=local(centre,pose,FastMath.cos(b)*.44f,.18f,FastMath.sin(b)*.44f),
                        t=local(centre,pose,FastMath.cos(a)*.44f,.18f,FastMath.sin(a)*.44f);
                rocketBatch.quad(p,q,r,t,body,1);
                rocketBatch.triangle(t,r,local(centre,pose,0,.2f,0),i%2==0?body:AMBER,1);
            }
            ColorRGBA indicator=mine.armed()?HOT:new ColorRGBA(.33f,.27f,.14f,1);
            for(int i=0;i<8;i++) {
                float a=i*FastMath.TWO_PI/8,b=(i+1)*FastMath.TWO_PI/8;
                rocketBatch.triangle(local(centre,pose,FastMath.cos(a)*.13f,.21f,FastMath.sin(a)*.13f),
                        local(centre,pose,FastMath.cos(b)*.13f,.21f,FastMath.sin(b)*.13f),local(centre,pose,0,.24f,0),indicator,1);
            }
        }
        rocketBatch.end();
        ringBatch.begin();
        for(Ring ring:rings) {
            float radius=ring.radius*(ring.age/ring.lifetime),width=.13f+.25f*(1-ring.age/ring.lifetime);
            for(int i=0;i<64;i++) {
                float a=i*FastMath.TWO_PI/64,b=(i+1)*FastMath.TWO_PI/64;
                ringBatch.quad(ringPoint(ring.centre,radius-width,a),ringPoint(ring.centre,radius+width,a),
                        ringPoint(ring.centre,radius+width,b),ringPoint(ring.centre,radius-width,b),ION,1-ring.age/ring.lifetime);
            }
        }
        ringBatch.end();
    }
    private void renderFields() {
        fieldBatch.begin();
        for(var fire:fires) {
            Quaternion pose=surfaceRotation(fire.normal());
            for(Vector3f support:fire.surfacePoints()) {
                Vector3f centre=support.add(fire.normal().mult(.028f));
                for(int segment=0;segment<8;segment++) {
                    float a=segment*FastMath.TWO_PI/8,b=(segment+1)*FastMath.TWO_PI/8;
                    fieldBatch.triangle(centre,local(centre,pose,FastMath.cos(a)*.56f,0,FastMath.sin(a)*.56f),
                            local(centre,pose,FastMath.cos(b)*.56f,0,FastMath.sin(b)*.56f),new ColorRGBA(.14f,.055f,.017f,1),.58f);
                }
            }
        }
        if(session!=null)for(VehicleState vehicle:session.vehicles) {
            if(!vehicle.alive())continue;
            Vector3f centre=world.position(vehicle.id);Quaternion rotation=world.rotation(vehicle.id);
            if(vehicle.shieldTicks>0) {
                ColorRGBA color=new ColorRGBA(.16f,.55f,1,1);
                for(int ring=0;ring<3;ring++)for(int segment=0;segment<32;segment++) {
                    float a=segment*FastMath.TWO_PI/32,b=(segment+1)*FastMath.TWO_PI/32;
                    Vector3f p=shieldPoint(centre,rotation,a,ring),q=shieldPoint(centre,rotation,b,ring);
                    fieldLine(p,q,color,.48f,.025f);
                }
            }
            if(vehicle.frozenTicks>0) {
                Vector3f[] corners=new Vector3f[8];
                for(int i=0;i<8;i++)corners[i]=local(centre,rotation,(i&1)==0?-1.3f:1.3f,(i&2)==0?-.42f:1.2f,(i&4)==0?-2.5f:2.5f);
                for(int i=0;i<8;i++)for(int axis:new int[]{1,2,4})if((i&axis)==0)fieldLine(corners[i],corners[i|axis],ION,.75f,.035f);
                // Narrow crystal facets, instead of an opaque ice box over the vehicle.
                for(int i=0;i<8;i++)fieldBatch.triangle(corners[i],corners[i].add(0,.4f,0),centre.add(corners[i].subtract(centre).mult(.85f)),ION,.22f);
            }
            if(vehicle.stunnedTicks>0) {
                ColorRGBA color=new ColorRGBA(.69f,.36f,1,1);
                for(int segment=0;segment<20;segment++) {
                    float a=segment*FastMath.TWO_PI/20,b=(segment+1)*FastMath.TWO_PI/20;
                    float lift=(segment%2==0?.15f:.45f);
                    fieldLine(local(centre,rotation,FastMath.cos(a)*1.25f,1.3f+lift,FastMath.sin(a)*2.3f),
                            local(centre,rotation,FastMath.cos(b)*1.25f,1.3f+.6f-lift,FastMath.sin(b)*2.3f),color,.85f,.04f);
                }
            }
        }
        fieldBatch.end();
    }
    private static Vector3f shieldPoint(Vector3f centre,Quaternion rotation,float angle,int ring) {
        float c=FastMath.cos(angle),s=FastMath.sin(angle);
        return switch(ring) {
            case 0 -> local(centre,rotation,c*1.55f,.3f,s*2.9f);
            case 1 -> local(centre,rotation,c*1.55f,.3f+s*1.35f,0);
            default -> local(centre,rotation,0,.3f+c*1.35f,s*2.9f);
        };
    }
    private void fieldLine(Vector3f a,Vector3f b,ColorRGBA color,float alpha,float width) {
        Vector3f along=b.subtract(a);Vector3f side=along.cross(Vector3f.UNIT_Y);
        if(side.lengthSquared()<.00001f)side=along.cross(Vector3f.UNIT_Z);
        side.normalizeLocal().multLocal(width);
        fieldBatch.quad(a.subtract(side),a.add(side),b.add(side),b.subtract(side),color,alpha);
    }
    private static Quaternion surfaceRotation(Vector3f normal) {
        Vector3f axis=Vector3f.UNIT_Y.cross(normal);
        return axis.lengthSquared()<.00001f?new Quaternion():new Quaternion().fromAngleAxis(FastMath.acos(Math.clamp(normal.y,-1,1)),axis.normalizeLocal());
    }
    private static Vector3f local(Vector3f centre,Quaternion rotation,float x,float y,float z) {
        return rotation.mult(new Vector3f(x,y,z)).addLocal(centre);
    }
    private static Vector3f ringPoint(Vector3f centre,float radius,float angle) {
        return centre.add(FastMath.cos(angle)*radius,.08f,FastMath.sin(angle)*radius);
    }
    public int effectCount(){return particles.size()+rings.size()+tracers.size();}
    public int projectileCount(){return Math.min(projectiles.size(),PROJECTILE_LIMIT);}
    @Override public void close(){if(closed)return;closed=true;root.removeFromParent();particles.clear();rings.clear();tracers.clear();trailHeads.clear();projectiles=List.of();mines=List.of();fires=List.of();session=null;}

    private static final class Batch {
        final Geometry geometry;
        final Mesh mesh=new Mesh();
        final FloatBuffer positions,colors,textureCoordinates,spriteData;
        final boolean softSprites;
        Batch(Node root,String name,AssetManager assets,int vertices,boolean transparent,boolean softSprites) {
            this.softSprites=softSprites;
            positions=BufferUtils.createFloatBuffer(vertices*3);colors=BufferUtils.createFloatBuffer(vertices*4);
            mesh.setBuffer(VertexBuffer.Type.Position,3,positions);mesh.setBuffer(VertexBuffer.Type.Color,4,colors);mesh.setDynamic();
            textureCoordinates=softSprites?BufferUtils.createFloatBuffer(vertices*2):null;
            spriteData=softSprites?BufferUtils.createFloatBuffer(vertices*2):null;
            if(softSprites) {
                mesh.setBuffer(VertexBuffer.Type.TexCoord,2,textureCoordinates);
                mesh.setBuffer(VertexBuffer.Type.TexCoord2,2,spriteData);
            }
            geometry=new Geometry(name,mesh);
            Material material=new Material(assets,softSprites?"materials/CombatParticles.j3md":"Common/MatDefs/Misc/Unshaded.j3md");
            if(!softSprites)material.setBoolean("VertexColor",true);
            material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            if(transparent) {
                material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
                material.getAdditionalRenderState().setDepthWrite(false);geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
            }
            geometry.setMaterial(material);geometry.setCullHint(Spatial.CullHint.Always);root.attachChild(geometry);
        }
        void begin(){positions.clear();colors.clear();if(softSprites){textureCoordinates.clear();spriteData.clear();}}
        void vertex(float x,float y,float z,ColorRGBA color,float alpha) {
            vertex(x,y,z,color,alpha,0,0,0,0);
        }
        void vertex(float x,float y,float z,ColorRGBA color,float alpha,float u,float v,float radius,float shape) {
            if(positions.remaining()<3)return;
            positions.put(x).put(y).put(z);colors.put(color.r).put(color.g).put(color.b).put(alpha);
            if(softSprites){textureCoordinates.put(u).put(v);spriteData.put(radius).put(shape);}
        }
        void sprite(Vector3f centre,float radius,ColorRGBA color,float alpha,boolean smoke) {
            float shape=smoke?1:2;
            for(int i=0;i<SPRITE_UV.length;i+=2)
                vertex(centre.x,centre.y,centre.z,color,alpha,SPRITE_UV[i],SPRITE_UV[i+1],radius,shape);
        }
        void triangle(Vector3f a,Vector3f b,Vector3f c,ColorRGBA color,float alpha) {
            for(Vector3f p:List.of(a,b,c))vertex(p.x,p.y,p.z,color,alpha);
        }
        void quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,ColorRGBA color,float alpha) {
            triangle(a,b,c,color,alpha);triangle(a,c,d,color,alpha);
        }
        void end() {
            positions.flip();colors.flip();
            mesh.getBuffer(VertexBuffer.Type.Position).updateData(positions);mesh.getBuffer(VertexBuffer.Type.Color).updateData(colors);
            if(softSprites) {
                textureCoordinates.flip();spriteData.flip();
                mesh.getBuffer(VertexBuffer.Type.TexCoord).updateData(textureCoordinates);
                mesh.getBuffer(VertexBuffer.Type.TexCoord2).updateData(spriteData);
            }
            mesh.updateCounts();
            if(positions.limit()==0)geometry.setCullHint(Spatial.CullHint.Always);
            else {
                geometry.updateModelBound();
                if(softSprites && mesh.getBound() instanceof BoundingBox bounds) {
                    // Vertex positions are sprite centres; GPU offsets must also fit culling bounds.
                    bounds.setXExtent(bounds.getXExtent()+FLASH_RADIUS_LIMIT);
                    bounds.setYExtent(bounds.getYExtent()+FLASH_RADIUS_LIMIT);
                    bounds.setZExtent(bounds.getZExtent()+FLASH_RADIUS_LIMIT);
                }
                geometry.setCullHint(Spatial.CullHint.Dynamic);
            }
        }
    }
}
