package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.util.BufferUtils;
import game.wreckriff.combat.ProjectileState;
import game.wreckriff.simulation.*;
import java.nio.FloatBuffer;
import java.util.*;

/** Bounded, presentation-only effects. Three shared draws; no physics bodies or dynamic lights. */
public final class CombatVisuals implements AutoCloseable {
    public static final int PARTICLE_LIMIT=384, RING_LIMIT=8, PROJECTILE_LIMIT=64;
    private static final ColorRGBA AMBER=new ColorRGBA(1,.62f,.10f,1), HOT=new ColorRGBA(1,.24f,.035f,1);
    private static final ColorRGBA ION=new ColorRGBA(.12f,.9f,1,1), SMOKE=new ColorRGBA(.22f,.23f,.24f,.75f);
    private static final Vector3f[] OCTAHEDRON={
        new Vector3f(1,0,0),new Vector3f(-1,0,0),new Vector3f(0,1,0),
        new Vector3f(0,-1,0),new Vector3f(0,0,1),new Vector3f(0,0,-1)
    };
    private static final int[] OCTA_INDEX={0,2,4,4,2,1,1,2,5,5,2,0,4,3,0,1,3,4,5,3,1,0,3,5};
    private record Ring(Vector3f centre,float radius,float lifetime,float age) {}
    private static final class Particle {
        final Vector3f position,velocity;
        final ColorRGBA color;
        final float lifetime,size,growth,gravity;
        float age;
        Particle(Vector3f position,Vector3f velocity,ColorRGBA color,float lifetime,float size,float growth,float gravity) {
            this.position=position.clone();this.velocity=velocity.clone();this.color=color.clone();
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
    private final Batch particleBatch,rocketBatch,ringBatch;
    private List<ProjectileState> projectiles=List.of();
    private boolean closed;

    public CombatVisuals(AssetManager assets,Node scene,WorldQuery world) {
        this.world=Objects.requireNonNull(world);
        particleBatch=new Batch(root,"particles-and-tracers",assets,PARTICLE_LIMIT*24+64*12,true);
        rocketBatch=new Batch(root,"rocket-models",assets,PROJECTILE_LIMIT*120,false);
        ringBatch=new Batch(root,"pulse-rings",assets,RING_LIMIT*64*6,true);
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
                case EXPLOSION -> explosion(event.position(),"power".equals(event.kind())?1.35f:1);
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

    public void update(List<ProjectileState> states,MatchSession session,float dt) {
        if(closed)return;
        dt=Math.max(0,Math.min(.1f,dt));
        projectiles=states;
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
                    if("power".equals(rocket.kind())) {
                        emit(at,new Vector3f(0,.45f,0),SMOKE,.55f,.18f,.55f,0);
                        if(i%2==0)emit(at,randomDirection(.45f),HOT,.18f,.09f,.2f,0);
                    } else emit(at,Vector3f.ZERO,ION,.25f,.065f,.12f,0);
                }
                trailHeads.put(rocket.id(),position);
            } else if(!trailHeads.containsKey(rocket.id()))trailHeads.put(rocket.id(),previous);
        }
        trailHeads.keySet().retainAll(live);
        if(session!=null)for(VehicleState vehicle:session.vehicles) {
            int id=vehicle.id;smokeClock[id]-=dt;turboClock[id]-=dt;
            if(vehicle.alive() && vehicle.hp<60 && smokeClock[id]<=0) {
                Vector3f bonnet=world.position(id).add(world.rotation(id).mult(new Vector3f(0,.6f,.9f)));
                emit(bonnet,new Vector3f(0,1.4f,0).addLocal(world.velocity(id).mult(.12f)),SMOKE,1,.19f,.85f,0);
                smokeClock[id]=.08f;
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
            emit(centre,velocity,color,(i<18?.35f:.75f)*scale,(i<18?.16f:.32f)*scale,i<18?1:1.4f,i<18?4:0);
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
        particleBatch.begin();
        for(Particle p:particles) {
            float size=Math.max(.015f,p.size+p.age*p.growth),alpha=p.color.a*(1-p.age/p.lifetime);
            for(int index:OCTA_INDEX)particleBatch.vertex(p.position.x+OCTAHEDRON[index].x*size,
                    p.position.y+OCTAHEDRON[index].y*size,p.position.z+OCTAHEDRON[index].z*size,p.color,alpha);
        }
        for(Tracer tracer:tracers) {
            Vector3f direction=tracer.to.subtract(tracer.from).normalizeLocal();
            Vector3f side=direction.cross(Vector3f.UNIT_Y).normalizeLocal().multLocal(.018f);
            particleBatch.quad(tracer.from.subtract(side),tracer.from.add(side),tracer.to.add(side),tracer.to.subtract(side),AMBER,.9f);
            side=direction.cross(side).normalizeLocal().multLocal(.018f);
            particleBatch.quad(tracer.from.subtract(side),tracer.from.add(side),tracer.to.add(side),tracer.to.subtract(side),AMBER,.9f);
        }
        particleBatch.end();
        rocketBatch.begin();
        int count=0;
        for(ProjectileState rocket:projectiles) {
            if(count++>=PROJECTILE_LIMIT)break;
            boolean power="power".equals(rocket.kind());
            Vector3f centre=rocket.position(),direction=rocket.direction();
            Quaternion rotation=new Quaternion().lookAt(direction,Math.abs(direction.y)>.98f?Vector3f.UNIT_Z:Vector3f.UNIT_Y);
            float radius=power?.19f:.11f,tail=power?-.5f:-.35f,nose=power?.52f:.55f;
            ColorRGBA color=power?HOT:ION;
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
    private static Vector3f local(Vector3f centre,Quaternion rotation,float x,float y,float z) {
        return rotation.mult(new Vector3f(x,y,z)).addLocal(centre);
    }
    private static Vector3f ringPoint(Vector3f centre,float radius,float angle) {
        return centre.add(FastMath.cos(angle)*radius,.08f,FastMath.sin(angle)*radius);
    }
    public int effectCount(){return particles.size()+rings.size()+tracers.size();}
    public int projectileCount(){return Math.min(projectiles.size(),PROJECTILE_LIMIT);}
    @Override public void close(){if(closed)return;closed=true;root.removeFromParent();particles.clear();rings.clear();tracers.clear();trailHeads.clear();projectiles=List.of();}

    private static final class Batch {
        final Geometry geometry;
        final Mesh mesh=new Mesh();
        final FloatBuffer positions,colors;
        final int capacity;
        Batch(Node root,String name,AssetManager assets,int vertices,boolean transparent) {
            capacity=vertices;positions=BufferUtils.createFloatBuffer(vertices*3);colors=BufferUtils.createFloatBuffer(vertices*4);
            mesh.setBuffer(VertexBuffer.Type.Position,3,positions);mesh.setBuffer(VertexBuffer.Type.Color,4,colors);mesh.setDynamic();
            geometry=new Geometry(name,mesh);
            Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");material.setBoolean("VertexColor",true);
            material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            if(transparent) {
                material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
                material.getAdditionalRenderState().setDepthWrite(false);geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
            }
            geometry.setMaterial(material);geometry.setCullHint(Spatial.CullHint.Always);root.attachChild(geometry);
        }
        void begin(){positions.clear();colors.clear();}
        void vertex(float x,float y,float z,ColorRGBA color,float alpha) {
            if(positions.remaining()<3)return;
            positions.put(x).put(y).put(z);colors.put(color.r).put(color.g).put(color.b).put(alpha);
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
            mesh.updateCounts();
            if(positions.limit()==0)geometry.setCullHint(Spatial.CullHint.Always);
            else {mesh.updateBound();geometry.setCullHint(Spatial.CullHint.Dynamic);}
        }
    }
}
