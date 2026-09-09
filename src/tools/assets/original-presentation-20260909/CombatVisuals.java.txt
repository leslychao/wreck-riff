package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.material.*;
import com.jme3.light.PointLight;
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

/** Bounded presentation effects: four shared draws and at most two brief, shadowless blast lights. */
public final class CombatVisuals implements AutoCloseable {
    public static final int PARTICLE_LIMIT=768, SHOT_LIMIT=128, SHARD_LIMIT=192, FLARE_LIMIT=24, PROJECTILE_LIMIT=64, COSMETIC_FIRE_LIMIT=16;
    public static final float TRACER_LENGTH=1.5f;
    private static final ColorRGBA AMBER=new ColorRGBA(1,.62f,.10f,1), HOT=new ColorRGBA(1,.24f,.035f,1);
    private static final ColorRGBA ION=new ColorRGBA(.12f,.9f,1,1), SMOKE=new ColorRGBA(.17f,.18f,.19f,.24f);
    private static final ColorRGBA CRITICAL_SMOKE=new ColorRGBA(.012f,.014f,.017f,.78f);
    private static final ColorRGBA BLAST_SMOKE=new ColorRGBA(.075f,.062f,.053f,.66f), BLAST_FLAME=new ColorRGBA(1,.27f,.025f,.95f);
    private static final ColorRGBA METAL=new ColorRGBA(.47f,.43f,.35f,1);
    private static final ColorRGBA DUST=new ColorRGBA(.42f,.34f,.25f,.28f);
    private static final float SMOKE_RADIUS_LIMIT=.55f, FLASH_RADIUS_LIMIT=2.4f;
    private static final float[] SPRITE_UV={0,0,1,0,1,1,0,0,1,1,0,1};
    private static final class Particle {
        final Vector3f position,velocity;
        final ColorRGBA color;
        final boolean smoke,criticalSmoke,blast;
        final int priority;
        final float lifetime,size,growth,gravity;
        float age;
        Particle(Vector3f position,Vector3f velocity,ColorRGBA color,float lifetime,float size,float growth,float gravity,int priority) {
            this.position=position.clone();this.velocity=velocity.clone();this.criticalSmoke=color==CRITICAL_SMOKE;
            this.blast=color==BLAST_SMOKE||color==BLAST_FLAME;this.smoke=color==SMOKE||criticalSmoke||color==DUST||color==BLAST_SMOKE;this.color=color.clone();this.priority=priority;
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
        final Vector3f position,velocity;final Quaternion rotation;final float life,size;final ColorRGBA color;final int priority;float age;
        Shard(Vector3f position,Vector3f velocity,Quaternion rotation,float life,float size,ColorRGBA color,int priority) {
            this.position=position.clone();this.velocity=velocity;this.rotation=rotation;this.life=life;this.size=size;
            this.color=color;this.priority=priority;
        }
    }
    private static final class CosmeticFire {
        final Vector3f position,normal;final int priority;float age,clock;
        CosmeticFire(GameEvent event,int priority) {position=event.position();normal=contactNormal(event);this.priority=priority;}
    }
    private static final class BlastLight {
        final PointLight light=new PointLight();float age;int priority;
    }
    private static final class HitFlare {
        final Vector3f position,normal;float age;
        HitFlare(GameEvent event){position=event.position().clone();normal=event.normal().clone();if(normal.lengthSquared()<.1f)normal.set(Vector3f.UNIT_Y);else normal.normalizeLocal();}
    }
    record TracerSegment(long id,Vector3f from,Vector3f to) {}
    private final Node root=new Node("combat-visuals");
    private final Node scene;
    private final WorldQuery world;
    private final Vector3f observer=new Vector3f();
    private final List<Particle> particles=new ArrayList<>();
    private final LinkedHashMap<Long,GunShot> shots=new LinkedHashMap<>();
    private final Set<Integer> destroyedTargets=new HashSet<>();
    private record EventKey(GameEvent.Type type,long id,int subject) {}
    private final LinkedHashSet<EventKey> recentEvents=new LinkedHashSet<>();
    private static final int EVENT_HISTORY_LIMIT=2048;
    private final List<Shard> shards=new ArrayList<>();
    private final List<HitFlare> hitFlares=new ArrayList<>();
    private final List<CosmeticFire> cosmeticFires=new ArrayList<>();
    private final BlastLight[] lights={new BlastLight(),new BlastLight()};
    private final Map<Integer,Integer> gunShotCount=new HashMap<>();
    private final Map<Long,Vector3f> trailHeads=new HashMap<>();
    private static final class Emitter { float previousTurbo=100,smokeClock,turboClock; }
    private final Map<Integer,Emitter> emitters=new HashMap<>();
    private final Random visualRandom=new Random(0x56495355414cL); // Never touches combat RNG.
    private final Batch particleBatch,rocketBatch,fragmentBatch,fieldBatch;
    private List<CombatSystem.MineView> mines=List.of();
    private List<CombatSystem.FireZoneView> fires=List.of();
    private List<CombatSystem.BallisticWarningView> warnings=List.of();
    private float flameClock;
    private int emissionPriority;
    private List<ProjectileState> projectiles=List.of();
    private boolean closed;

    public CombatVisuals(AssetManager assets,Node scene,WorldQuery world) {
        this.world=Objects.requireNonNull(world);this.scene=scene;
        root.setShadowMode(RenderQueue.ShadowMode.Off); // Emissive particles and screen-facing quads are not shadow casters/receivers.
        particleBatch=new Batch(root,"particles-and-tracers",assets,PARTICLE_LIMIT*6+SHOT_LIMIT*12,true,true);
        rocketBatch=new Batch(root,"rocket-models",assets,PROJECTILE_LIMIT*450+10*500,false,false);
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
        for(BlastLight light:lights){light.light.setEnabled(false);scene.addLight(light.light);}
        scene.attachChild(root);
    }

    public void accept(List<GameEvent> events) {
        if(closed)return;
        observer.set(world.position(0));
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
            int count=gunShotCount.merge(event.sourceId(),1,Integer::sum);shots.put(event.eventId(),new GunShot(event,count%3==0));
            emissionPriority=event.sourceId()==0?3:1;
            emit(event.origin(),Vector3f.ZERO,AMBER,.055f,.19f,1.4f,0);
        }
        for(GameEvent event:fresh) {
            emissionPriority=event.sourceId()==0||event.subjectId()==0?3:2;
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
                case EXPLOSION -> {
                    explosion(event.position(),contactNormal(event),event.kind());
                    if("ballistic".equals(event.kind())&&event.normal().lengthSquared()>.1f)addCosmeticFire(event);
                }
                case RAM -> {if(event.value()>=3)ram(event);}
                case FREEZE -> ice(event.position(),false);
                case CONTROL_ENDED -> {if("freeze".equals(event.kind()))ice(event.position(),true);}
                case SHIELD -> {for(int i=0;i<8;i++)emit(event.position(),randomDirection(1.4f),new ColorRGBA(.15f,.62f,1,.65f),.22f,.07f,.1f,0);}
                case DESTROYED -> explosion(event.position(),Vector3f.UNIT_Y,"destroyed");
                case PICKUP -> {
                    ColorRGBA colour="repair".equals(event.kind())?new ColorRGBA(.25f,1,.3f,1):ION;
                    for(int i=0;i<10;i++)emit(event.position().add(0,.5f,0),randomDirection(2).addLocal(0,2,0),colour,.38f,.06f,.3f,0);
                }
                default -> { }
            }
        }
        emissionPriority=0;
    }

    public void update(List<ProjectileState> states,List<CombatSystem.MineView> mines,
            List<CombatSystem.FireZoneView> fires,List<CombatSystem.BallisticWarningView> warnings,MatchSession session,float dt) {
        if(closed)return;
        observer.set(world.position(0));
        dt=Math.max(0,Math.min(.1f,dt));
        projectiles=states;this.mines=mines;this.fires=fires;this.warnings=warnings;emissionPriority=0;
        if(dt<=0){render();return;}
        for(BlastLight light:lights)if(light.light.isEnabled()) {
            light.age+=dt;
            if(light.age>=.22f)light.light.setEnabled(false);
            else light.light.setColor(new ColorRGBA(1,.37f,.07f,1).mult(3.5f*(1-light.age/.22f)));
        }
        for(Iterator<CosmeticFire> it=cosmeticFires.iterator();it.hasNext();) {
            CosmeticFire fire=it.next();fire.age+=dt;fire.clock-=dt;
            if(fire.age>=2){it.remove();continue;}
            if(fire.clock<=0) {
                emissionPriority=fire.priority;
                Quaternion surface=surfaceRotation(fire.normal);
                for(int i=0;i<4;i++) {
                    Vector3f at=fire.position.add(surface.mult(new Vector3f((visualRandom.nextFloat()-.5f)*1.3f,.08f,(visualRandom.nextFloat()-.5f)*1.3f)));
                    emit(at,fire.normal.mult(1.1f+visualRandom.nextFloat()),BLAST_FLAME,Math.min(.32f,2-fire.age),.18f,-.1f,0);
                }
                fire.clock=.07f;
            }
        }
        emissionPriority=0;
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
                if(shot.impact!=null){emissionPriority=shot.impact.sourceId()==0||shot.impact.subjectId()==0?3:2;impact(shot.impact);}
                if(shot.shieldHit!=null)shieldFlare(shot.shieldHit);
                shot.impactShown=true;
            }
            if(shot.age>=shot.flight+.035f)it.remove();
        }
        emissionPriority=0;
        for(Iterator<Shard> it=shards.iterator();it.hasNext();) {
            Shard shard=it.next();shard.age+=dt;if(shard.age>=shard.life){it.remove();continue;}
            shard.velocity.y-=4*dt;shard.position.addLocal(shard.velocity.mult(dt));
        }
        for(Iterator<HitFlare> it=hitFlares.iterator();it.hasNext();) {HitFlare flare=it.next();flare.age+=dt;if(flare.age>=.2f)it.remove();}
        Set<Long> live=new HashSet<>();
        for(ProjectileState rocket:states) {
            emissionPriority=rocket.ownerId()==0?1:0;
            live.add(rocket.id());Vector3f position=rocket.position();
            Vector3f previous=trailHeads.get(rocket.id());
            if(previous==null)previous=rocket.previousPosition();
            Vector3f delta=position.subtract(previous);float distance=delta.length();
            float spacing="freeze".equals(rocket.kind())?.22f:"power".equals(rocket.kind())?.62f:.48f;
            int steps=Math.min(10,(int)(distance/spacing));
            if(steps>0) {
                for(int i=1;i<=steps;i++) {
                    Vector3f at=previous.add(delta.mult(i/(float)steps));
                    if("power".equals(rocket.kind())||"napalm".equals(rocket.kind())||rocket.kind().startsWith("ballistic")) {
                        emit(at,new Vector3f(0,.45f,0),SMOKE,.42f,.11f,.35f,0);
                        if(i%2==0)emit(at,randomDirection(.45f),HOT,.18f,.09f,.2f,0);
                    } else if("cannon".equals(rocket.kind()))emit(at,randomDirection(.3f),AMBER,.18f,.055f,.02f,0);
                    else if("freeze".equals(rocket.kind()))emit(at,Vector3f.ZERO,ION,.12f,.045f,.02f,0);
                    else emit(at,Vector3f.ZERO,ION,.25f,.065f,.12f,0);
                }
                trailHeads.put(rocket.id(),position);
            } else if(!trailHeads.containsKey(rocket.id()))trailHeads.put(rocket.id(),previous);
        }
        trailHeads.keySet().retainAll(live);
        if(session!=null)for(VehicleState vehicle:session.vehicles) {
            emissionPriority=vehicle.id==0?1:0;
            int id=vehicle.id;Emitter emitter=emitters.computeIfAbsent(id,key->new Emitter());emitter.smokeClock-=dt;emitter.turboClock-=dt;
            if(vehicle.alive() && vehicle.hp/vehicle.maximumHp<=.25f) {
                // Accumulate emitter time so the plume has the same density at 30/60/120 render FPS.
                for(int emitted=0;emitter.smokeClock<=0&&emitted<2;emitted++,emitter.smokeClock+=.05f) {
                    Vector3f bonnet=world.position(id).add(world.rotation(id).mult(new Vector3f(
                            (visualRandom.nextFloat()-.5f)*.06f,.72f,1.05f+(visualRandom.nextFloat()-.5f)*.06f)));
                    Vector3f rise=new Vector3f((visualRandom.nextFloat()-.5f)*.14f,1.10f,(visualRandom.nextFloat()-.5f)*.10f);
                    emit(bonnet,rise.addLocal(world.velocity(id).mult(.12f)),CRITICAL_SMOKE,1.8f,.18f,.25f,0);
                }
            } else emitter.smokeClock=0;
            if(vehicle.alive() && vehicle.turbo<emitter.previousTurbo-.01f && emitter.turboClock<=0) {
                for(float side:new float[]{-.72f,.72f}) {
                    Vector3f exhaust=world.position(id).add(world.rotation(id).mult(new Vector3f(side,-.11f,-2.49f)));
                    emit(exhaust,world.forward(id).mult(-5).addLocal(world.velocity(id).mult(.2f)),HOT,.16f,.16f,-.7f,0);
                }
                emitter.turboClock=.025f;
            }
            emitter.previousTurbo=vehicle.turbo;
        }
        emissionPriority=0;
        render();

    }

    private void impact(GameEvent event) {
        Vector3f normal=contactNormal(event);boolean metal=event.subjectId()>=0;
        for(int i=0;i<(metal?12:8);i++) {
            Vector3f velocity=normal.mult(metal?4.8f:1.2f).addLocal(randomDirection(metal?3.2f:.7f));
            emit(event.position().add(normal.mult(.025f)),velocity,metal?AMBER:DUST,metal?.28f:.40f,
                    metal?.052f:.12f,metal?-.035f:.30f,metal?8:0);
        }
        if(metal)for(int i=0;i<3;i++)addShard(event.position(),normal.mult(2).addLocal(randomDirection(2.2f)),.38f,.025f,METAL);
    }
    private void ram(GameEvent event) {
        Vector3f normal=contactNormal(event);float force=Math.clamp(event.value()/12,.3f,1.8f);
        for(int i=0;i<(int)(12*force);i++) {
            Vector3f velocity=randomDirection(3*force).addLocal(normal.mult(1.5f)).addLocal(0,1,0);
            emit(event.position(),velocity,i%3==0?DUST:AMBER,i%3==0?.5f:.32f,i%3==0?.16f:.065f,.1f,i%3==0?0:7);
        }
        for(int i=0;i<(int)(5*force);i++)addShard(event.position(),randomDirection(3*force).addLocal(0,2,0),.6f,.045f,METAL);
    }
    private void shieldFlare(GameEvent event) {
        if(hitFlares.size()>=FLARE_LIMIT)hitFlares.remove(0);hitFlares.add(new HitFlare(event));
    }
    private void ice(Vector3f centre,boolean breaking) {
        for(int i=0;i<(breaking?22:14);i++) {
            Vector3f offset=randomDirection(breaking?1.1f:.3f);offset.z*=1.6f;
            addShard(centre.add(offset),randomDirection(breaking?3.5f:2.2f).addLocal(0,1,0),
                    breaking?.48f:.34f,.035f+visualRandom.nextFloat()*.06f,ION);
        }
        for(int i=0;i<8;i++)emit(centre.add(randomDirection(.45f)),randomDirection(.7f),ION,.25f,.05f,.12f,0);
    }
    private void explosion(Vector3f centre,Vector3f normal,String kind) {
        boolean mine=kind.equals("mine"),cannon=kind.startsWith("cannon"),ballistic=kind.equals("ballistic");
        float scale=switch(kind){case "destroyed"->1.5f;case "power"->1.3f;case "mine","ballistic"->1.15f;case "cannon-ricochet"->.7f;default->1;};
        int flameCount=cannon?16:ballistic?32:26,fragmentCount=cannon?24:mine?18:14;
        emit(centre.add(normal.mult(.12f)),Vector3f.ZERO,new ColorRGBA(1,.9f,.61f,1),.12f,.9f*scale,4,0);
        for(int i=0;i<flameCount;i++) {
            Vector3f velocity=randomDirection((ballistic?5.2f:3.8f)*scale);
            if(mine){velocity.x*=.65f;velocity.z*=.65f;velocity.y=Math.abs(velocity.y)+3;}
            else velocity.addLocal(normal.mult(cannon?2:1.2f));
            emit(centre.add(randomDirection(.15f)),velocity,BLAST_FLAME,.6f,.24f*scale,1.45f,2);
        }
        for(int i=0;i<14;i++) {
            Vector3f drift=randomDirection(1.7f*scale);drift.y=Math.abs(drift.y)+1.5f;
            emit(centre.add(normal.mult(.25f)),drift,BLAST_SMOKE,1.5f,.32f,.68f,0);
        }
        for(int i=0;i<fragmentCount;i++) {
            Vector3f velocity=randomDirection((cannon?8:6)*scale).addLocal(normal.mult(2));
            addShard(centre,velocity,.6f+visualRandom.nextFloat()*.25f,.045f+visualRandom.nextFloat()*.065f,METAL);
            emit(centre,velocity.mult(1.3f),AMBER,.38f,.065f,0,7);
        }
        Quaternion surface=surfaceRotation(normal);
        for(int i=0;i<12;i++) {
            float angle=i*FastMath.TWO_PI/12;
            Vector3f out=surface.mult(new Vector3f(FastMath.cos(angle),.1f,FastMath.sin(angle)));
            emit(centre.add(normal.mult(.06f)),out.mult(3.7f*scale),DUST,.55f,.2f,.8f,0);
        }
        if(!kind.equals("cannon-ricochet"))lightBlast(centre,scale);
    }
    private void emit(Vector3f position,Vector3f velocity,ColorRGBA color,float lifetime,float size,float growth,float gravity) {
        if(particles.size()>=PARTICLE_LIMIT) {
            int victim=-1;float incoming=importance(position,emissionPriority,0),lowest=incoming;
            for(int i=0;i<particles.size();i++) {Particle p=particles.get(i);float score=importance(p.position,p.priority,p.age/p.lifetime);
                if(score<=lowest){lowest=score;victim=i;}}
            if(victim<0)return;particles.remove(victim);
        }
        particles.add(new Particle(position,velocity,color,lifetime,size,growth,gravity,emissionPriority));
    }
    private void addShard(Vector3f position,Vector3f velocity,float life,float size,ColorRGBA color) {
        if(shards.size()>=SHARD_LIMIT) {
            int victim=-1;float lowest=importance(position,emissionPriority,0);
            for(int i=0;i<shards.size();i++){Shard s=shards.get(i);float score=importance(s.position,s.priority,s.age/s.life);if(score<=lowest){lowest=score;victim=i;}}
            if(victim<0)return;shards.remove(victim);
        }
        shards.add(new Shard(position,velocity,new Quaternion().fromAngles(visualRandom.nextFloat()*3,visualRandom.nextFloat()*3,visualRandom.nextFloat()*3),life,size,color,emissionPriority));
    }
    private float importance(Vector3f position,int priority,float consumedLife) {
        return priority*80-Math.min(250,position.distance(observer))*.5f-consumedLife*30;
    }
    private void addCosmeticFire(GameEvent event) {
        if(cosmeticFires.size()>=COSMETIC_FIRE_LIMIT) {
            int victim=-1;float lowest=importance(event.position(),emissionPriority,0);
            for(int i=0;i<cosmeticFires.size();i++){CosmeticFire f=cosmeticFires.get(i);float score=importance(f.position,f.priority,f.age/2);if(score<=lowest){lowest=score;victim=i;}}
            if(victim<0)return;cosmeticFires.remove(victim);
        }
        cosmeticFires.add(new CosmeticFire(event,emissionPriority));
    }
    private void lightBlast(Vector3f position,float scale) {
        if(position.distanceSquared(observer)>45*45)return;
        BlastLight chosen=null;float lowest=importance(position,emissionPriority,0);
        for(BlastLight light:lights) {
            if(!light.light.isEnabled()){chosen=light;break;}
            float score=importance(light.light.getPosition(),light.priority,light.age/.22f);
            if(score<=lowest){lowest=score;chosen=light;}
        }
        if(chosen==null)return;
        chosen.age=0;chosen.priority=emissionPriority;chosen.light.setPosition(position.add(0,.5f,0));
        chosen.light.setRadius(11*scale);chosen.light.setColor(new ColorRGBA(3.5f,1.3f,.24f,1));chosen.light.setEnabled(true);
    }
    private static Vector3f contactNormal(GameEvent event) {
        Vector3f normal=event.normal();return normal.lengthSquared()<.1f?Vector3f.UNIT_Y.clone():normal.normalizeLocal();
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
            float size=Math.clamp(p.size+p.age*p.growth,.015f,p.smoke?(p.blast?1.6f:SMOKE_RADIUS_LIMIT):FLASH_RADIUS_LIMIT);
            float life=p.age/p.lifetime;
            float fade=p.criticalSmoke?Math.min(1,p.age/.12f)*Math.clamp((p.lifetime-p.age)/.65f,0,1):
                    p.blast&&p.smoke?Math.min(1,p.age/.18f)*Math.clamp((p.lifetime-p.age)/.6f,0,1):
                    (1-life)*(p.smoke?Math.min(1,life*12):1);
            float alpha=p.color.a*fade;
            particleBatch.sprite(p.position,size,p.color,alpha,p.criticalSmoke||p.blast&&p.smoke?3:p.smoke?1:p.blast?4:2);
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
            boolean carrier="ballistic".equals(rocket.kind()),drop="ballistic-fall".equals(rocket.kind());
            Vector3f centre=rocket.position(),direction=rocket.direction();
            Quaternion rotation=new Quaternion().lookAt(direction,Math.abs(direction.y)>.98f?Vector3f.UNIT_Z:Vector3f.UNIT_Y);
            if("cannon".equals(rocket.kind())) {
                // The renderer follows the authoritative rebounding centre/direction; it never simulates a bounce.
                for(int band=0;band<6;band++)for(int segment=0;segment<12;segment++) {
                    float a=segment*FastMath.TWO_PI/12,b=(segment+1)*FastMath.TWO_PI/12,
                            v=-FastMath.HALF_PI+band*FastMath.PI/6,w=-FastMath.HALF_PI+(band+1)*FastMath.PI/6;
                    ColorRGBA metal=band==2||band==3?new ColorRGBA(.76f,.34f,.065f,1):new ColorRGBA(.20f,.21f,.23f,1);
                    rocketBatch.quad(ballPoint(centre,rotation,a,v),ballPoint(centre,rotation,b,v),
                            ballPoint(centre,rotation,b,w),ballPoint(centre,rotation,a,w),metal,1);
                }
                continue;
            }
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
            float radius=carrier?.34f:drop?.20f:power?.19f:napalm?.25f:.11f,
                    tail=carrier?-.85f:drop?-.42f:power?-.5f:napalm?-.28f:-.35f,nose=carrier?.9f:drop?.55f:power?.52f:napalm?.36f:.55f;
            ColorRGBA color=carrier?new ColorRGBA(.32f,.34f,.37f,1):drop?new ColorRGBA(.82f,.38f,.04f,1):power?HOT:napalm?AMBER:ION;
            for(int i=0;i<6;i++) {
                float a=i*FastMath.TWO_PI/6,b=(i+1)*FastMath.TWO_PI/6;
                Vector3f p=local(centre,rotation,FastMath.cos(a)*radius,FastMath.sin(a)*radius,tail);
                Vector3f q=local(centre,rotation,FastMath.cos(b)*radius,FastMath.sin(b)*radius,tail);
                Vector3f r=local(centre,rotation,FastMath.cos(b)*radius,FastMath.sin(b)*radius,.23f);
                Vector3f s=local(centre,rotation,FastMath.cos(a)*radius,FastMath.sin(a)*radius,.23f);
                rocketBatch.quad(p,q,r,s,color,1);
                rocketBatch.triangle(s,r,local(centre,rotation,0,0,nose),AMBER,1);
            }
            int fins=carrier||drop||power?4:3;
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
    private static Vector3f ballPoint(Vector3f centre,Quaternion rotation,float longitude,float latitude) {
        float r=FastMath.cos(latitude)*.25f;
        return local(centre,rotation,FastMath.cos(longitude)*r,FastMath.sin(longitude)*r,FastMath.sin(latitude)*.25f);
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
            fragmentBatch.triangle(a,b,c,shard.color,alpha);fragmentBatch.triangle(a,c,d,shard.color,alpha);
            fragmentBatch.triangle(a,d,b,shard.color,alpha);fragmentBatch.triangle(b,d,c,shard.color,alpha);
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
        // Warning geometry reserves its vertices before lower-priority scorch and cosmetic fields.
        renderWarnings();
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
        for(CosmeticFire fire:cosmeticFires) {
            Quaternion pose=surfaceRotation(fire.normal);Vector3f centre=fire.position.add(fire.normal.mult(.035f));
            for(int segment=0;segment<12;segment++) {
                float a=segment*FastMath.TWO_PI/12,b=(segment+1)*FastMath.TWO_PI/12;
                fieldBatch.triangle(centre,local(centre,pose,FastMath.cos(a)*.82f,0,FastMath.sin(a)*.82f),
                        local(centre,pose,FastMath.cos(b)*.82f,0,FastMath.sin(b)*.82f),new ColorRGBA(.18f,.058f,.012f,1),.6f*(1-fire.age/2));
            }
        }
        fieldBatch.end();
    }
    private void renderWarnings() {
        for(var warning:warnings) {
            Vector3f normal=warning.normal(),centre=warning.point().add(normal.mult(.045f));float radius=warning.radius();
            Quaternion pose=surfaceRotation(normal);
            float pulse=.56f+.25f*FastMath.sin(warning.remainingTicks()*FastMath.TWO_PI/36);
            for(int segment=0;segment<32;segment++) {
                float a=segment*FastMath.TWO_PI/32,b=(segment+1)*FastMath.TWO_PI/32;
                fieldBatch.quad(local(centre,pose,FastMath.cos(a)*radius,0,FastMath.sin(a)*radius),
                        local(centre,pose,FastMath.cos(b)*radius,0,FastMath.sin(b)*radius),
                        local(centre,pose,FastMath.cos(b)*(radius-.11f),0,FastMath.sin(b)*(radius-.11f)),
                        local(centre,pose,FastMath.cos(a)*(radius-.11f),0,FastMath.sin(a)*(radius-.11f)),HOT,pulse);
            }
            for(int axis=0;axis<2;axis++) {
                Vector3f length=axis==0?new Vector3f(.6f,0,0):new Vector3f(0,0,.6f),width=axis==0?new Vector3f(0,0,.05f):new Vector3f(.05f,0,0);
                pose.multLocal(length);pose.multLocal(width);
                fieldBatch.quad(centre.subtract(length).subtractLocal(width),centre.add(length).subtractLocal(width),
                        centre.add(length).addLocal(width),centre.subtract(length).addLocal(width),AMBER,pulse);
            }
        }
    }
    private static Quaternion surfaceRotation(Vector3f normal) {
        Vector3f axis=Vector3f.UNIT_Y.cross(normal);
        return axis.lengthSquared()<.00001f?new Quaternion().fromAngleAxis(normal.y<0?FastMath.PI:0,Vector3f.UNIT_X):
                new Quaternion().fromAngleAxis(FastMath.acos(Math.clamp(normal.y,-1,1)),axis.normalizeLocal());
    }
    private static Vector3f local(Vector3f centre,Quaternion rotation,float x,float y,float z) {
        return rotation.mult(new Vector3f(x,y,z)).addLocal(centre);
    }
    public int effectCount(){return particles.size()+shots.size()+shards.size()+hitFlares.size()+cosmeticFires.size();}
    int cosmeticFireCount(){return cosmeticFires.size();}
    public int projectileCount(){return Math.min(projectiles.size(),PROJECTILE_LIMIT);}
    @Override public void close(){if(closed)return;closed=true;root.removeFromParent();for(BlastLight light:lights){light.light.setEnabled(false);scene.removeLight(light.light);}
        particles.clear();shots.clear();destroyedTargets.clear();shards.clear();hitFlares.clear();cosmeticFires.clear();recentEvents.clear();trailHeads.clear();
        projectiles=List.of();mines=List.of();fires=List.of();warnings=List.of();}

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
            if(positions.remaining()<9)return;
            for(Vector3f p:List.of(a,b,c))vertex(p.x,p.y,p.z,color,alpha);
        }
        void quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,ColorRGBA color,float alpha) {
            if(positions.remaining()<18)return;
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
