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
    public static final int PARTICLE_LIMIT=384, SHOT_LIMIT=128, SHARD_LIMIT=96, FLARE_LIMIT=24, PROJECTILE_LIMIT=64;
    public static final float TRACER_LENGTH=1.5f;
    private static final ColorRGBA AMBER=new ColorRGBA(1,.62f,.10f,1), HOT=new ColorRGBA(1,.24f,.035f,1);
    private static final ColorRGBA ION=new ColorRGBA(.12f,.9f,1,1), SMOKE=new ColorRGBA(.17f,.18f,.19f,.24f);
    private static final ColorRGBA CRITICAL_SMOKE=new ColorRGBA(.012f,.014f,.017f,.78f);
    private static final ColorRGBA DUST=new ColorRGBA(.42f,.34f,.25f,.28f);
    private static final float SMOKE_RADIUS_LIMIT=.55f, FLASH_RADIUS_LIMIT=.68f;
    private static final float[] SPRITE_UV={0,0,1,0,1,1,0,0,1,1,0,1};
    private static final class Particle {
        final Vector3f position,velocity;
        final ColorRGBA color;
        final boolean smoke,criticalSmoke;
        final float lifetime,size,growth,gravity;
        float age;
        Particle(Vector3f position,Vector3f velocity,ColorRGBA color,float lifetime,float size,float growth,float gravity) {
            this.position=position.clone();this.velocity=velocity.clone();this.criticalSmoke=color==CRITICAL_SMOKE;this.smoke=color==SMOKE||criticalSmoke||color==DUST;this.color=color.clone();
            this.lifetime=lifetime;this.size=size;this.growth=growth;this.gravity=gravity;
        }
    }
    private static final class GunShot {
        final long id;final Vector3f origin,end,direction;final float distance,flight;final boolean tracer;
        float age;boolean impactShown;GameEvent impact,shieldHit;
        GunShot(GameEvent event,boolean tracer) {
            id=event.eventId();origin=event.origin().clone();end=event.position().clone();
            direction=end.subtract(origin);distance=direction.length();if(distance>0)direction.divideLocal(distance);
            flight=event.cosmeticImpactDelaySeconds();this.tracer=tracer;
        }
    }
    private static final class Shard {
        final Vector3f position,velocity;final Quaternion rotation;final float life,size;float age;
        Shard(Vector3f position,Vector3f velocity,Quaternion rotation,float life,float size) {
            this.position=position.clone();this.velocity=velocity;this.rotation=rotation;this.life=life;this.size=size;
        }
    }
    private static final class HitFlare {
        final Vector3f position,normal;float age;
        HitFlare(GameEvent event){position=event.position().clone();normal=event.normal().clone();if(normal.lengthSquared()<.1f)normal.set(Vector3f.UNIT_Y);else normal.normalizeLocal();}
    }
    record TracerSegment(long id,Vector3f from,Vector3f to) {}
    private final Node root=new Node("combat-visuals");
    private final WorldQuery world;
    private final List<Particle> particles=new ArrayList<>();
    private final LinkedHashMap<Long,GunShot> shots=new LinkedHashMap<>();
    private final Set<Integer> destroyedTargets=new HashSet<>();
    private record EventKey(GameEvent.Type type,long id,int subject) {}
    private final LinkedHashSet<EventKey> recentEvents=new LinkedHashSet<>();
    private static final int EVENT_HISTORY_LIMIT=2048;
    private final List<Shard> shards=new ArrayList<>();
    private final List<HitFlare> hitFlares=new ArrayList<>();
    private final int[] gunShotCount=new int[5];
    private final Map<Long,Vector3f> trailHeads=new HashMap<>();
    private final float[] previousTurbo={100,100,100,100,100},smokeClock=new float[5],turboClock=new float[5];
    private final Random visualRandom=new Random(0x56495355414cL); // Never touches combat RNG.
    private final Batch particleBatch,rocketBatch,fragmentBatch,fieldBatch;
    private List<CombatSystem.MineView> mines=List.of();
    private List<CombatSystem.FireZoneView> fires=List.of();
    private float flameClock;
    private List<ProjectileState> projectiles=List.of();
    private boolean closed;

    public CombatVisuals(AssetManager assets,Node scene,WorldQuery world) {
        this.world=Objects.requireNonNull(world);
        root.setShadowMode(RenderQueue.ShadowMode.Off); // Emissive particles and screen-facing quads are not shadow casters/receivers.
        particleBatch=new Batch(root,"particles-and-tracers",assets,PARTICLE_LIMIT*6+SHOT_LIMIT*12,true,true);
        rocketBatch=new Batch(root,"rocket-models",assets,PROJECTILE_LIMIT*120+10*500,false,false);
        fragmentBatch=new Batch(root,"impact-fragments",assets,SHARD_LIMIT*12+FLARE_LIMIT*16*3,true,false);
        fieldBatch=new Batch(root,"ground-fire",assets,20000,true,false);
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
        List<GameEvent> fresh=new ArrayList<>(events.size());
        for(GameEvent event:events) {
            if(!recentEvents.add(new EventKey(event.type(),event.eventId(),event.subjectId())))continue;
            if(recentEvents.size()>EVENT_HISTORY_LIMIT)recentEvents.remove(recentEvents.iterator().next());
            fresh.add(event);
        }
        // Death cancels pending target contacts, including contacts delivered later in this batch.
        // Launched tracers and muzzle flashes still finish independently of the target/shooter lifecycle.
        for(GameEvent event:fresh)if(event.type()==GameEvent.Type.DESTROYED) {
            destroyedTargets.add(event.subjectId());
            for(GunShot shot:shots.values()) {
                if(shot.impact!=null&&shot.impact.subjectId()==event.subjectId())shot.impact=null;
                if(shot.shieldHit!=null&&shot.shieldHit.subjectId()==event.subjectId())shot.shieldHit=null;
            }
        }
        // Match IMPACT to SHOT even if their transport order changes inside a drained event batch.
        for(GameEvent event:fresh)if(event.type()==GameEvent.Type.SHOT&&"machine-gun".equals(event.kind())&&!shots.containsKey(event.eventId())) {
            if(shots.size()>=SHOT_LIMIT)shots.remove(shots.keySet().iterator().next());
            int count=++gunShotCount[event.sourceId()];shots.put(event.eventId(),new GunShot(event,count%3==0));
            emit(event.origin(),Vector3f.ZERO,AMBER,.045f,.12f,1.4f,0);
        }
        for(GameEvent event:fresh) {
            switch(event.type()) {
                case SHOT -> {
                    if(!"machine-gun".equals(event.kind()))emit(event.position(),world.forward(event.sourceId()).mult(-2),
                            "freeze".equals(event.kind())?ION:HOT,.12f,.14f,.5f,0);
                }
                case IMPACT -> {
                    if(!destroyedTargets.contains(event.subjectId())&&"machine-gun".equals(event.kind())) {GunShot shot=shots.get(event.eventId());if(shot!=null)shot.impact=event;}
                }
                case SHIELD_HIT -> {
                    if(!destroyedTargets.contains(event.subjectId())) {
                        GunShot shot=shots.get(event.eventId());
                        if(shot!=null&&"machine-gun".equals(event.kind()))shot.shieldHit=event;else shieldFlare(event);
                    }
                }
                case SHIELD_ENDED -> { }
                case EXPLOSION -> explosion(event.position(),"power".equals(event.kind())||"mine".equals(event.kind())?1.35f:1);
                case FREEZE -> ice(event.position(),false);
                case CONTROL_ENDED -> {if("freeze".equals(event.kind()))ice(event.position(),true);}
                case SHIELD -> {for(int i=0;i<8;i++)emit(event.position(),randomDirection(1.4f),new ColorRGBA(.15f,.62f,1,.65f),.22f,.07f,.1f,0);}
                case DESTROYED -> explosion(event.position(),1.8f);
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
        projectiles=states;this.mines=mines;this.fires=fires;
        if(dt<=0){render();return;}
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
        for(Iterator<GunShot> it=shots.values().iterator();it.hasNext();) {
            GunShot shot=it.next();shot.age+=dt;
            if(!shot.impactShown&&shot.age>=shot.flight) {
                if(shot.impact!=null)impact(shot.impact);
                if(shot.shieldHit!=null)shieldFlare(shot.shieldHit);
                shot.impactShown=true;
            }
            if(shot.age>=shot.flight+.035f)it.remove();
        }
        for(Iterator<Shard> it=shards.iterator();it.hasNext();) {
            Shard shard=it.next();shard.age+=dt;if(shard.age>=shard.life){it.remove();continue;}
            shard.velocity.y-=4*dt;shard.position.addLocal(shard.velocity.mult(dt));
        }
        for(Iterator<HitFlare> it=hitFlares.iterator();it.hasNext();) {HitFlare flare=it.next();flare.age+=dt;if(flare.age>=.2f)it.remove();}
        Set<Long> live=new HashSet<>();
        for(ProjectileState rocket:states) {
            live.add(rocket.id());Vector3f position=rocket.position();
            Vector3f previous=trailHeads.get(rocket.id());
            if(previous==null)previous=rocket.previousPosition();
            Vector3f delta=position.subtract(previous);float distance=delta.length();
            float spacing="freeze".equals(rocket.kind())?.22f:"power".equals(rocket.kind())?.62f:.48f;
            int steps=Math.min(10,(int)(distance/spacing));
            if(steps>0) {
                for(int i=1;i<=steps;i++) {
                    Vector3f at=previous.add(delta.mult(i/(float)steps));
                    if("power".equals(rocket.kind())||"napalm".equals(rocket.kind())) {
                        emit(at,new Vector3f(0,.45f,0),SMOKE,.42f,.11f,.35f,0);
                        if(i%2==0)emit(at,randomDirection(.45f),HOT,.18f,.09f,.2f,0);
                    } else if("freeze".equals(rocket.kind()))emit(at,Vector3f.ZERO,ION,.12f,.045f,.02f,0);
                    else emit(at,Vector3f.ZERO,ION,.25f,.065f,.12f,0);
                }
                trailHeads.put(rocket.id(),position);
            } else if(!trailHeads.containsKey(rocket.id()))trailHeads.put(rocket.id(),previous);
        }
        trailHeads.keySet().retainAll(live);
        if(session!=null)for(VehicleState vehicle:session.vehicles) {
            int id=vehicle.id;smokeClock[id]-=dt;turboClock[id]-=dt;
            if(vehicle.alive() && vehicle.hp/vehicle.maximumHp<=.25f) {
                // Accumulate emitter time so the plume has the same density at 30/60/120 render FPS.
                for(int emitted=0;smokeClock[id]<=0&&emitted<2;emitted++,smokeClock[id]+=.05f) {
                    Vector3f bonnet=world.position(id).add(world.rotation(id).mult(new Vector3f(
                            (visualRandom.nextFloat()-.5f)*.06f,.72f,1.05f+(visualRandom.nextFloat()-.5f)*.06f)));
                    Vector3f rise=new Vector3f((visualRandom.nextFloat()-.5f)*.14f,1.10f,(visualRandom.nextFloat()-.5f)*.10f);
                    emit(bonnet,rise.addLocal(world.velocity(id).mult(.12f)),CRITICAL_SMOKE,1.8f,.18f,.25f,0);
                }
            } else smokeClock[id]=0;
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

    private void impact(GameEvent event) {
        Vector3f normal=event.normal();if(normal.lengthSquared()<.1f)normal=Vector3f.UNIT_Y;
        for(int i=0;i<(event.subjectId()>=0?6:4);i++) {
            Vector3f velocity=normal.mult(event.subjectId()>=0?2.8f:.8f).addLocal(randomDirection(event.subjectId()>=0?1.7f:.45f));
            emit(event.position().add(normal.mult(.02f)),velocity,event.subjectId()>=0?AMBER:DUST,event.subjectId()>=0?.18f:.32f,
                    event.subjectId()>=0?.035f:.09f,event.subjectId()>=0?0:.25f,event.subjectId()>=0?7:0);
        }
    }
    private void shieldFlare(GameEvent event) {
        if(hitFlares.size()>=FLARE_LIMIT)hitFlares.remove(0);hitFlares.add(new HitFlare(event));
    }
    private void ice(Vector3f centre,boolean breaking) {
        for(int i=0;i<(breaking?22:14);i++) {
            if(shards.size()>=SHARD_LIMIT)shards.remove(0);
            Vector3f offset=randomDirection(breaking?1.1f:.3f);offset.z*=1.6f;
            shards.add(new Shard(centre.add(offset),randomDirection(breaking?3.5f:2.2f).addLocal(0,1,0),
                    new Quaternion().fromAngles(visualRandom.nextFloat()*3,visualRandom.nextFloat()*3,visualRandom.nextFloat()*3),
                    breaking?.48f:.34f,.035f+visualRandom.nextFloat()*.06f));
        }
        for(int i=0;i<8;i++)emit(centre.add(randomDirection(.45f)),randomDirection(.7f),ION,.25f,.05f,.12f,0);
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
        renderRockets();renderFields();renderFragments();
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
            float fade=p.criticalSmoke?Math.min(1,p.age/.12f)*Math.clamp((p.lifetime-p.age)/.65f,0,1):
                    (1-life)*(p.smoke?Math.min(1,life*12):1);
            float alpha=p.color.a*fade;
            particleBatch.sprite(p.position,size,p.color,alpha,p.criticalSmoke?3:p.smoke?1:2);
        }
        for(TracerSegment tracer:tracerSegments()) {
            Vector3f direction=tracer.to.subtract(tracer.from).normalizeLocal();
            Vector3f side=direction.cross(Vector3f.UNIT_Y);
            if(side.lengthSquared()<.001f)side=direction.cross(Vector3f.UNIT_Z);
            side.normalizeLocal().multLocal(.014f);
            particleBatch.quad(tracer.from.subtract(side),tracer.from.add(side),tracer.to.add(side),tracer.to.subtract(side),AMBER,.9f);
            side=direction.cross(side).normalizeLocal().multLocal(.014f);
            particleBatch.quad(tracer.from.subtract(side),tracer.from.add(side),tracer.to.add(side),tracer.to.subtract(side),AMBER,.9f);
        }
        particleBatch.end();
    }
    private void renderRockets() {
        rocketBatch.begin();
        int count=0;
        for(ProjectileState rocket:projectiles) {
            if(count++>=PROJECTILE_LIMIT)break;
            boolean power="power".equals(rocket.kind()),napalm="napalm".equals(rocket.kind()),freeze="freeze".equals(rocket.kind());
            Vector3f centre=rocket.position(),direction=rocket.direction();
            Quaternion rotation=new Quaternion().lookAt(direction,Math.abs(direction.y)>.98f?Vector3f.UNIT_Z:Vector3f.UNIT_Y);
            if(freeze) {
                for(int face=0;face<6;face++) {
                    float a=face*FastMath.TWO_PI/6,b=(face+1)*FastMath.TWO_PI/6;
                    Vector3f p=local(centre,rotation,FastMath.cos(a)*.11f,FastMath.sin(a)*.11f,0);
                    Vector3f q=local(centre,rotation,FastMath.cos(b)*.11f,FastMath.sin(b)*.11f,0);
                    rocketBatch.triangle(p,q,local(centre,rotation,0,0,.30f),ION,1);
                    rocketBatch.triangle(q,p,local(centre,rotation,0,0,-.24f),new ColorRGBA(.65f,.93f,1,1),1);
                }
                continue;
            }
            float radius=power?.19f:napalm?.25f:.11f,tail=power?-.5f:napalm?-.28f:-.35f,nose=power?.52f:napalm?.36f:.55f;
            ColorRGBA color=power?HOT:napalm?AMBER:ION;
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
    }
    List<TracerSegment> tracerSegments() {
        List<TracerSegment> result=new ArrayList<>();
        for(GunShot shot:shots.values())if(shot.tracer&&shot.distance>.001f&&shot.age>0) {
            float head=Math.min(shot.distance,shot.flight>0?shot.distance*shot.age/shot.flight:shot.distance),tail=Math.max(0,head-TRACER_LENGTH);
            result.add(new TracerSegment(shot.id,shot.origin.add(shot.direction.mult(tail)),shot.origin.add(shot.direction.mult(head))));
        }
        return result;
    }
    private void renderFragments() {
        fragmentBatch.begin();
        for(Shard shard:shards) {
            float size=shard.size,alpha=.75f*(1-shard.age/shard.life);
            Vector3f a=local(shard.position,shard.rotation,0,size*2.2f,0),b=local(shard.position,shard.rotation,-size,-size,0),
                    c=local(shard.position,shard.rotation,size,-size,0),d=local(shard.position,shard.rotation,0,0,size);
            fragmentBatch.triangle(a,b,c,ION,alpha);fragmentBatch.triangle(a,c,d,ION,alpha);
            fragmentBatch.triangle(a,d,b,ION,alpha);fragmentBatch.triangle(b,d,c,ION,alpha);
        }
        for(HitFlare flare:hitFlares) {
            Quaternion rotation=surfaceRotation(flare.normal);Vector3f centre=flare.position.add(flare.normal.mult(.08f));
            float radius=.13f+flare.age*2.2f,alpha=.8f*(1-flare.age/.2f);
            for(int segment=0;segment<16;segment++) {
                float a=segment*FastMath.TWO_PI/16,b=(segment+1)*FastMath.TWO_PI/16;
                fragmentBatch.triangle(centre,local(centre,rotation,FastMath.cos(a)*radius,0,FastMath.sin(a)*radius),
                        local(centre,rotation,FastMath.cos(b)*radius,0,FastMath.sin(b)*radius),new ColorRGBA(.3f,.7f,1,1),alpha);
            }
        }
        fragmentBatch.end();
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
        fieldBatch.end();
    }
    private static Quaternion surfaceRotation(Vector3f normal) {
        Vector3f axis=Vector3f.UNIT_Y.cross(normal);
        return axis.lengthSquared()<.00001f?new Quaternion():new Quaternion().fromAngleAxis(FastMath.acos(Math.clamp(normal.y,-1,1)),axis.normalizeLocal());
    }
    private static Vector3f local(Vector3f centre,Quaternion rotation,float x,float y,float z) {
        return rotation.mult(new Vector3f(x,y,z)).addLocal(centre);
    }
    public int effectCount(){return particles.size()+shots.size()+shards.size()+hitFlares.size();}
    public int projectileCount(){return Math.min(projectiles.size(),PROJECTILE_LIMIT);}
    @Override public void close(){if(closed)return;closed=true;root.removeFromParent();particles.clear();shots.clear();destroyedTargets.clear();shards.clear();hitFlares.clear();recentEvents.clear();trailHeads.clear();projectiles=List.of();mines=List.of();fires=List.of();}

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
        void sprite(Vector3f centre,float radius,ColorRGBA color,float alpha,float shape) {
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
