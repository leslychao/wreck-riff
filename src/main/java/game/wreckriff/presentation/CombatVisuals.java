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
import com.jme3.texture.Texture;

/** Three bounded effect batches, pooled lit ordnance, and at most two brief shadowless blast lights. */
public final class CombatVisuals implements AutoCloseable {
    public static final int PARTICLE_LIMIT=768, SHOT_LIMIT=128, SHARD_LIMIT=192, FLARE_LIMIT=24, PROJECTILE_LIMIT=64, COSMETIC_FIRE_LIMIT=16;
    public static final float TRACER_LENGTH=1.5f;
    private static final ColorRGBA AMBER=new ColorRGBA(1,.62f,.10f,1), HOT=new ColorRGBA(1,.24f,.035f,1);
    private static final ColorRGBA ION=new ColorRGBA(.12f,.9f,1,1), SMOKE=new ColorRGBA(.17f,.18f,.19f,.24f);
    private static final ColorRGBA CRITICAL_SMOKE=new ColorRGBA(.012f,.014f,.017f,.78f);
    private static final ColorRGBA BLAST_SMOKE=new ColorRGBA(.075f,.062f,.053f,.66f), BLAST_FLAME=new ColorRGBA(1,.27f,.025f,.95f);
    private static final ColorRGBA FIRE_FLAME=new ColorRGBA(1,.62f,.24f,.88f);
    private static final ColorRGBA METAL=new ColorRGBA(.47f,.43f,.35f,1);
    private static final ColorRGBA DUST=new ColorRGBA(.42f,.34f,.25f,.28f);
    private static final ColorRGBA SCORCH=new ColorRGBA(.085f,.060f,.038f,1);
    private static final int FIELD_VERTEX_LIMIT=20000, CACHED_FIRE_POINT_LIMIT=FIELD_VERTEX_LIMIT/6;
    private static final float SMOKE_RADIUS_LIMIT=.55f, FLASH_RADIUS_LIMIT=2.4f;
    private static final float[] SPRITE_UV={0,0,1,0,1,1,0,0,1,1,0,1};
    private static final class Particle {
        final Vector3f position=new Vector3f(),velocity=new Vector3f();
        final ColorRGBA color=new ColorRGBA();
        boolean smoke,criticalSmoke,blast,dust,flame;
        int priority;
        float lifetime,size,growth,gravity,rotation,variation;
        float age;
        void reset(Vector3f position,Vector3f velocity,ColorRGBA color,float lifetime,float size,float growth,float gravity,int priority,float rotation,float variation) {
            this.position.set(position);this.velocity.set(velocity);this.criticalSmoke=color==CRITICAL_SMOKE;age=0;
            dust=color==DUST;flame=color==FIRE_FLAME;
            this.blast=color==BLAST_SMOKE||color==BLAST_FLAME;this.smoke=color==SMOKE||criticalSmoke||dust||color==BLAST_SMOKE;this.color.set(color);this.priority=priority;
            this.lifetime=lifetime;this.size=size;this.growth=growth;this.gravity=gravity;
            this.rotation=rotation;this.variation=variation;
        }
    }
    private static final class GunShot {
        final long id;final Vector3f origin,end,direction;float distance;final float flight;final boolean tracer;
        final double born;float age;int target=-1;VehicleContact contact;boolean occluded;
        GunShot(GameEvent event,boolean tracer,double clock) {
            id=event.eventId();origin=event.origin().clone();end=event.position().clone();
            direction=end.subtract(origin);distance=direction.length();if(distance>0)direction.divideLocal(distance);
            flight=event.cosmeticImpactDelaySeconds();this.tracer=tracer;
            born=event.simulationTick()>=0?event.simulationTick()/(double)MatchSession.TICKS_PER_SECOND:clock;
        }
    }
    private static final class Shard {
        final Vector3f position,velocity;final Quaternion rotation;final float life,size;final ColorRGBA color;final int priority;float age;
        Vector3f halfExtents;int bounces;
        Shard(Vector3f position,Vector3f velocity,Quaternion rotation,float life,float size,ColorRGBA color,int priority) {
            this.position=position.clone();this.velocity=velocity;this.rotation=rotation;this.life=life;this.size=size;
            this.color=color;this.priority=priority;
            halfExtents=new Vector3f(size*.75f,size*.22f,size*1.5f);
        }
    }
    private static final class CosmeticFire {
        final Vector3f position,normal;final int priority;float age,clock;
        CosmeticFire(GameEvent event,int priority) {position=event.position();normal=contactNormal(event);this.priority=priority;}
    }
    /** Static surface data only: remaining lifetime and flame animation never invalidate it. */
    private static final class FireSurface {
        final long id;
        final Vector3f origin,normal;
        final float[] support,vertices;
        final byte[] edges;
        FireSurface(CombatSystem.FireZoneView fire) {
            id=fire.id();origin=fire.position().clone();normal=fire.normal().clone();
            var points=fire.surfacePoints();int count=Math.min(points.size(),CACHED_FIRE_POINT_LIMIT);
            support=new float[count*3];vertices=new float[count*18];edges=new byte[count];
            // Primitive sorted keys avoid Long boxing and the x^z hash collisions of packed grid cells.
            long[] cells=new long[points.size()];
            for(int i=0;i<points.size();i++) {
                Vector3f point=points.get(i);
                cells[i]=gridCell(Math.round(point.x-origin.x),Math.round(point.z-origin.z));
            }
            Arrays.sort(cells);
            for(int i=0;i<count;i++) {
                Vector3f point=points.get(i);support[i*3]=point.x;support[i*3+1]=point.y;support[i*3+2]=point.z;
                int x=Math.round(point.x-origin.x),z=Math.round(point.z-origin.z),mask=0;
                for(int neighbour=0;neighbour<8;neighbour++) {
                    int dx=switch(neighbour){case 0,4,6->1;case 1,5,7->-1;default->0;};
                    int dz=switch(neighbour){case 2,4,5->1;case 3,6,7->-1;default->0;};
                    if(Arrays.binarySearch(cells,gridCell(x+dx,z+dz))<0)mask|=1<<neighbour;
                }
                edges[i]=(byte)mask;
                Vector3f a=surfacePoint(point,normal,-.5f,-.5f,.028f),b=surfacePoint(point,normal,.5f,-.5f,.028f);
                Vector3f c=surfacePoint(point,normal,.5f,.5f,.028f),d=surfacePoint(point,normal,-.5f,.5f,.028f);
                Vector3f[] quad={a,b,c,a,c,d};
                for(int vertex=0;vertex<6;vertex++) {
                    int offset=i*18+vertex*3;Vector3f p=quad[vertex];
                    vertices[offset]=p.x;vertices[offset+1]=p.y;vertices[offset+2]=p.z;
                }
            }
        }
        boolean matches(CombatSystem.FireZoneView fire) {
            var points=fire.surfacePoints();
            if(!origin.equals(fire.position())||!normal.equals(fire.normal())||points.size()*3!=support.length)return false;
            for(int i=0;i<points.size();i++) {
                Vector3f point=points.get(i);
                if(point.x!=support[i*3]||point.y!=support[i*3+1]||point.z!=support[i*3+2])return false;
            }
            return true;
        }
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
    private final ArrayDeque<Particle> freeParticles=new ArrayDeque<>();
    private final Particle[] sortedParticles=new Particle[PARTICLE_LIMIT];
    private final Vector3f sortEye=new Vector3f(),sortForward=new Vector3f();
    private final Comparator<Particle> particleOrder=(a,b)->Float.compare(depth(b),depth(a));
    private final LinkedHashMap<Long,GunShot> shots=new LinkedHashMap<>();
    private final Set<Integer> destroyedTargets=new HashSet<>();
    private record EventKey(GameEvent.Type type,long id,int subject) {}
    private final LinkedHashSet<EventKey> recentEvents=new LinkedHashSet<>();
    private final LinkedHashSet<EventKey> presentedEvents=new LinkedHashSet<>();
    private static final int EVENT_HISTORY_LIMIT=2048;
    private final List<Shard> shards=new ArrayList<>();
    private final List<HitFlare> hitFlares=new ArrayList<>();
    private final List<CosmeticFire> cosmeticFires=new ArrayList<>();
    private final List<FireSurface> fireSurfaces=new ArrayList<>();
    private int cachedFirePoints,fireTopologyBuilds;
    private final BlastLight[] lights={new BlastLight(),new BlastLight()};
    private final Map<Integer,Integer> gunShotCount=new HashMap<>();
    private final Map<Long,Vector3f> trailHeads=new HashMap<>();
    private static final class Emitter { float previousTurbo=100,smokeClock,turboClock; }
    private final Map<Integer,Emitter> emitters=new HashMap<>();
    private final Random visualRandom=new Random(0x56495355414cL); // Never touches combat RNG.
    private final Batch particleBatch,sparkBatch,fragmentBatch,fieldBatch;
    private final CombatVfxAtlas atlas;
    private final Vector3f lightDirection=new Vector3f(-.72f,-.7f,.38f).normalizeLocal();
    private final OrdnancePresentation ordnance;
    private List<CombatSystem.MineView> mines=List.of();
    private List<CombatSystem.FireZoneView> fires=List.of();
    private List<CombatSystem.BallisticWarningView> warnings=List.of();
    private float flameClock;
    private float burningClock;
    private List<CombatSystem.FireExposureView> fireExposures=List.of();
    private int debrisQueryCursor;
    public static final int DEBRIS_QUERY_LIMIT=24;
    private int debrisQueries;
    private double presentationTime;
    private boolean externalPresentationTime;
    private Map<Integer,Node> vehicleModels=Map.of();
    public static final int TRACER_QUERY_LIMIT=16;
    private float flashIntensity=1;
    private int emissionPriority;
    private List<ProjectileState> projectiles=List.of();
    private boolean closed;

    public CombatVisuals(AssetManager assets,Node scene,WorldQuery world) {
        this.world=Objects.requireNonNull(world);this.scene=scene;
        atlas=CombatVfxAtlas.load(assets);
        root.setShadowMode(RenderQueue.ShadowMode.Off); // Emissive particles and screen-facing quads are not shadow casters/receivers.
        particleBatch=new Batch(root,"particles-and-tracers",assets,PARTICLE_LIMIT*6+SHOT_LIMIT*12,true,true);
        sparkBatch=new Batch(root,"sparks-and-tracers",assets,PARTICLE_LIMIT*6+SHOT_LIMIT*12,true,true);
        sparkBatch.geometry.getMaterial().getAdditionalRenderState().setBlendMode(RenderState.BlendMode.AlphaAdditive);
        particleBatch.geometry.getMaterial().setFloat("FlashIntensity",flashIntensity);
        sparkBatch.geometry.getMaterial().setFloat("FlashIntensity",flashIntensity);
        atlas.bind(particleBatch.geometry.getMaterial());
        atlas.bind(sparkBatch.geometry.getMaterial());
        setLighting(lightDirection,new ColorRGBA(.99f,.82f,.62f,1),new ColorRGBA(.28f,.33f,.43f,1));
        ordnance=new OrdnancePresentation(assets,root);
        fragmentBatch=new Batch(root,"impact-fragments",assets,SHARD_LIMIT*36+FLARE_LIMIT*16*3,true,false);
        Material fragmentMaterial=SurfaceMaterials.lit(assets,ColorRGBA.White,24,.32f);
        fragmentMaterial.setBoolean("UseVertexColor",true);
        fragmentMaterial.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        fragmentMaterial.getAdditionalRenderState().setDepthWrite(false);
        fragmentBatch.geometry.setMaterial(fragmentMaterial);
        fragmentBatch.geometry.setShadowMode(RenderQueue.ShadowMode.Receive);
        fieldBatch=new Batch(root,"ground-fire",assets,FIELD_VERTEX_LIMIT,true,true);
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
            // Delivery of contacts belongs to ContactPresentationTimeline, including audio/deformation.
            if(event.type()==GameEvent.Type.IMPACT||event.type()==GameEvent.Type.SHIELD_HIT)continue;
            if(!recentEvents.add(new EventKey(event.type(),event.eventId(),event.subjectId())))continue;
            if(recentEvents.size()>EVENT_HISTORY_LIMIT)recentEvents.remove(recentEvents.iterator().next());
            fresh.add(event);
        }
        // Death cancels pending target contacts, including contacts delivered later in this batch.
        // Launched tracers and muzzle flashes still finish independently of the target/shooter lifecycle.
        for(GameEvent event:fresh)if(event.type()==GameEvent.Type.DESTROYED) {
            destroyedTargets.add(event.subjectId());
        }
        // Match IMPACT to SHOT even if their transport order changes inside a drained event batch.
        for(GameEvent event:fresh)if(event.type()==GameEvent.Type.SHOT&&"machine-gun".equals(event.kind())&&!shots.containsKey(event.eventId())) {
            if(shots.size()>=SHOT_LIMIT)shots.remove(shots.keySet().iterator().next());
            int count=gunShotCount.merge(event.sourceId(),1,Integer::sum);shots.put(event.eventId(),new GunShot(event,count%3==0,presentationTime));
            emissionPriority=event.sourceId()==0?3:1;
            emit(event.origin(),Vector3f.ZERO,AMBER,.055f,.19f,1.4f,0);
            Vector3f forward=event.emission()==null?world.forward(event.sourceId()):event.emission().direction();
            Vector3f inherited=event.emission()==null?world.velocity(event.sourceId()):event.emission().sourceVelocity();
            Vector3f right=forward.cross(Vector3f.UNIT_Y).negateLocal().normalizeLocal();
            if(event.sourceId()==0||event.origin().distanceSquared(observer)<35*35) {
                addShard(event.origin().subtract(forward.mult(.28f)),right.mult(2.1f).addLocal(0,1.2f,0).addLocal(inherited),.8f,.025f,new ColorRGBA(.65f,.42f,.13f,1));
                emit(event.origin().subtract(forward.mult(.04f)),inherited.mult(.15f).addLocal(0,.28f,0),SMOKE,.16f,.065f,.3f,0);
            }
        }
        for(GameEvent event:fresh) {
            emissionPriority=event.sourceId()==0||event.subjectId()==0?3:2;
            switch(event.type()) {
                case SHOT -> {
                    if(!"machine-gun".equals(event.kind()))emit(event.origin(),world.forward(event.sourceId()).mult(-2),
                            "freeze".equals(event.kind())?ION:HOT,.12f,.14f,.5f,0);
                }
                case IMPACT, SHIELD_HIT -> { }
                case SHIELD_ENDED -> { }
                case EXPLOSION -> {
                    if("cannon-ricochet".equals(event.kind()))ricochet(event);
                    else explosion(event.position(),contactNormal(event),event.kind(),event.normal().lengthSquared()>.1f);
                    if("ballistic".equals(event.kind())&&event.normal().lengthSquared()>.1f)addCosmeticFire(event);
                }
                case RAM -> {if(event.value()>=3)ram(event);}
                case FREEZE -> ice(event.position(),false);
                case CONTROL_ENDED -> {if("freeze".equals(event.kind()))ice(event.position(),true);}
                case SHIELD -> {for(int i=0;i<8;i++)emit(event.position(),randomDirection(1.4f),new ColorRGBA(.15f,.62f,1,.65f),.22f,.07f,.1f,0);}
                case DESTROYED -> explosion(event.position(),Vector3f.UNIT_Y,"destroyed",true);
                default -> { }
            }
        }
        emissionPriority=0;
    }

    /** Already timed contacts only: no second delay and no gameplay state mutation. */
    public void acceptPresented(List<GameEvent> events) {
        if(closed)return;
        for(GameEvent event:events) {
            if(event.type()!=GameEvent.Type.IMPACT&&event.type()!=GameEvent.Type.SHIELD_HIT)continue;
            if(destroyedTargets.contains(event.subjectId()))continue;
            if(!presentedEvents.add(new EventKey(event.type(),event.eventId(),event.subjectId())))continue;
            if(presentedEvents.size()>EVENT_HISTORY_LIMIT)presentedEvents.remove(presentedEvents.iterator().next());
            GunShot shot=shots.get(event.eventId());
            if(shot!=null)retarget(shot,event.position());
            emissionPriority=event.sourceId()==0||event.subjectId()==0?3:2;
            if(event.type()==GameEvent.Type.SHIELD_HIT)shieldFlare(event);else impact(event);
        }
        emissionPriority=0;
    }
    public void setFireExposures(List<CombatSystem.FireExposureView> exposures) {fireExposures=List.copyOf(exposures);}
    public void setPresentationTime(double seconds) {
        if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Finite nonnegative presentation time required");
        presentationTime=seconds;externalPresentationTime=true;
    }
    /** Only reads poses; the application remains owner of vehicle transform updates. */
    public void bindVehicleModels(Map<Integer,Node> models) {vehicleModels=Objects.requireNonNull(models);}
    public void registerContacts(List<GameEvent> events) {
        for(GameEvent event:events)if(event.type()==GameEvent.Type.IMPACT&&"machine-gun".equals(event.kind())) {
            GunShot shot=shots.get(event.eventId());
            if(shot!=null&&event.vehicleContact()!=null){shot.target=event.subjectId();shot.contact=event.vehicleContact();}
        }
    }
    private static void retarget(GunShot shot,Vector3f end) {
        shot.end.set(end);shot.direction.set(end).subtractLocal(shot.origin);shot.distance=shot.direction.length();
        if(shot.distance>.00001f)shot.direction.divideLocal(shot.distance);
    }
    public void setLighting(Vector3f direction,ColorRGBA key,ColorRGBA fill) {
        lightDirection.set(direction);
        for(Batch batch:new Batch[]{particleBatch,sparkBatch}) {
            Material material=batch.geometry.getMaterial();
            material.setVector3("LightDirection",direction);material.setColor("KeyLight",key);material.setColor("FillLight",fill);
        }
    }
    void bindSoftDepth(Texture depth) {
        for(Batch batch:new Batch[]{particleBatch,sparkBatch}) {
        Material material=batch.geometry.getMaterial();
        material.setBoolean("SoftParticles",depth!=null);
        material.getAdditionalRenderState().setDepthTest(depth==null);
        batch.geometry.setQueueBucket(depth==null?RenderQueue.Bucket.Transparent:RenderQueue.Bucket.Translucent);
        if(depth==null){material.clearParam("SceneDepth");material.clearParam("NumSamplesDepth");}
        else {
            material.setTexture("SceneDepth",depth);
            if(depth.getImage().getMultiSamples()>1)material.setInt("NumSamplesDepth",depth.getImage().getMultiSamples());
            else material.clearParam("NumSamplesDepth");
        }
        }
    }
    void prepareSoftCamera(Camera camera) {
        for(Batch batch:new Batch[]{particleBatch,sparkBatch}) {
        Material material=batch.geometry.getMaterial();
        material.setVector2("CameraPlanes",new Vector2f(camera.getFrustumNear(),camera.getFrustumFar()));
        material.setVector3("LightDirection",camera.getViewMatrix().multNormal(lightDirection,new Vector3f()).normalizeLocal());
        }
    }
    private float depth(Particle particle) {
        return (particle.position.x-sortEye.x)*sortForward.x+(particle.position.y-sortEye.y)*sortForward.y+(particle.position.z-sortEye.z)*sortForward.z;
    }

    /** Called once by the pickup owner after its successful, deduplicated authoritative event. */
    public void pickupBurst(String kind,Vector3f position) {
        if(closed)return;
        ColorRGBA color=switch(kind) {
            case "homing-ammo" -> new ColorRGBA(1,.67f,.18f,1);
            case "power-ammo" -> new ColorRGBA(1,.21f,.12f,1);
            case "mine-ammo" -> new ColorRGBA(.89f,.51f,.26f,1);
            case "napalm-ammo" -> new ColorRGBA(1,.43f,.06f,1);
            case "ballistic-ammo" -> new ColorRGBA(.30f,.55f,1,1);
            case "cannon-ammo" -> new ColorRGBA(.76f,.84f,.91f,1);
            case "repair" -> new ColorRGBA(.29f,1,.43f,1);
            case "turbo" -> ION;
            default -> throw new IllegalArgumentException("Unknown pickup kind "+kind);
        };
        emissionPriority=2;observer.set(world.position(0));
        for(int i=0;i<10;i++) {
            float angle=i*FastMath.TWO_PI/10;
            Vector3f radial=new Vector3f(FastMath.cos(angle),0,FastMath.sin(angle));
            float lift=switch(kind){case "mine-ammo","cannon-ammo"->.45f;case "napalm-ammo","turbo"->2.2f;default->1.2f;};
            emit(position.add(radial.mult(.15f)),radial.mult(1.6f).addLocal(0,lift,0),color,.24f+i%3*.025f,.055f,.10f,0);
        }
        emissionPriority=0;
    }

    /** Leaves stable warning and ground-boundary geometry unchanged at minimum flash strength. */
    public void setFlashIntensity(float intensity) {
        if(!Float.isFinite(intensity)||intensity<0||intensity>1)throw new IllegalArgumentException("Flash intensity 0..1 required");
        flashIntensity=intensity;particleBatch.geometry.getMaterial().setFloat("FlashIntensity",intensity);
        sparkBatch.geometry.getMaterial().setFloat("FlashIntensity",intensity);
        if(intensity==0)for(BlastLight light:lights)light.light.setEnabled(false);
    }

    public void update(List<ProjectileState> states,List<CombatSystem.MineView> mines,
            List<CombatSystem.FireZoneView> fires,List<CombatSystem.BallisticWarningView> warnings,MatchSession session,float dt) {
        if(closed)return;
        observer.set(world.position(0));
        dt=Math.max(0,Math.min(.1f,dt));
        if(!externalPresentationTime)presentationTime+=dt;
        projectiles=states;this.mines=mines;this.fires=fires;this.warnings=warnings;emissionPriority=0;
        if(dt<=0){render();return;}
        for(BlastLight light:lights)if(light.light.isEnabled()) {
            light.age+=dt;
            if(light.age>=.22f)light.light.setEnabled(false);
            else light.light.setColor(new ColorRGBA(1,.37f,.07f,1).mult(3.5f*flashIntensity*(1-light.age/.22f)));
        }
        for(Iterator<CosmeticFire> it=cosmeticFires.iterator();it.hasNext();) {
            CosmeticFire fire=it.next();fire.age+=dt;fire.clock-=dt;
            if(fire.age>=2){it.remove();continue;}
            if(fire.clock<=0) {
                emissionPriority=fire.priority;
                Quaternion surface=surfaceRotation(fire.normal);
                for(int i=0;i<4;i++) {
                    Vector3f at=fire.position.add(surface.mult(new Vector3f((visualRandom.nextFloat()-.5f)*1.3f,.08f,(visualRandom.nextFloat()-.5f)*1.3f)));
                    emit(at,fire.normal.mult(1.1f+visualRandom.nextFloat()),FIRE_FLAME,Math.min(.32f,2-fire.age),.18f,-.1f,0);
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
                    Vector3f at=surfacePoint(points.get(visualRandom.nextInt(points.size())),fire.normal(),
                            (visualRandom.nextFloat()-.5f)*.6f,(visualRandom.nextFloat()-.5f)*.6f,.1f);
                    emit(at,fire.normal().mult(1.2f+visualRandom.nextFloat()),FIRE_FLAME,.48f,.25f,.12f,0);
                    if(i%4==0)emit(at.add(0,.4f,0),new Vector3f(.15f,.65f,0),SMOKE,.6f,.20f,.25f,0);
                }
            }
            flameClock=.065f;
        }
        burningClock-=dt;
        if(burningClock<=0) {
            for(var exposure:fireExposures) {
                int id=exposure.vehicleId();
                if(session==null||!session.vehicle(id).alive())continue;
                emissionPriority=id==0?3:1;
                Vector3f at=world.position(id).add(world.rotation(id).mult(exposure.contact().localPoint()));
                Vector3f velocity=world.velocity(id).mult(.15f).addLocal(0,1.3f,0);
                emit(at,velocity,FIRE_FLAME,.6f,.23f,.26f,0);
                emit(at.add(0,.15f,0),velocity.mult(.5f),BLAST_SMOKE,1.1f,.17f,.3f,0);
            }
            burningClock=.075f;
        }
        for(Iterator<Particle> it=particles.iterator();it.hasNext();) {
            Particle p=it.next();p.age+=dt;
            if(p.age>=p.lifetime){it.remove();freeParticles.addLast(p);continue;}
            p.velocity.y-=p.gravity*dt;p.position.addLocal(p.velocity.x*dt,p.velocity.y*dt,p.velocity.z*dt);
        }
        int tracerQueries=0;
        for(Iterator<GunShot> it=shots.values().iterator();it.hasNext();) {
            GunShot shot=it.next();shot.age=(float)Math.max(0,presentationTime-shot.born);
            if(shot.contact!=null&&!destroyedTargets.contains(shot.target)) {
                Node target=vehicleModels.get(shot.target);
                Vector3f end=target==null?world.position(shot.target).add(world.rotation(shot.target).mult(shot.contact.localPoint())):
                        target.getLocalRotation().mult(shot.contact.localPoint()).addLocal(target.getLocalTranslation());
                retarget(shot,end);
                if(shot.tracer) {
                    shot.occluded=true;
                    if(tracerQueries++<TRACER_QUERY_LIMIT) {
                        WorldQuery.Hit obstruction=world.staticSweep(shot.origin,shot.end,.01f);
                        shot.occluded=obstruction!=null&&obstruction.fraction()<.995f;
                    }
                }
            }
            if(shot.age>=shot.flight+.035f)it.remove();
        }
        emissionPriority=0;
        debrisQueries=0;
        int shardIndex=0,start=shards.isEmpty()?0:debrisQueryCursor%shards.size();
        for(Iterator<Shard> it=shards.iterator();it.hasNext();) {
            Shard shard=it.next();shard.age+=dt;if(shard.age>=shard.life){it.remove();continue;}
            shard.velocity.y-=9.81f*dt;
            Vector3f next=shard.position.add(shard.velocity.mult(dt));
            int rank=(shardIndex++-start+shards.size())%Math.max(1,shards.size());
            boolean nearby=shard.position.distanceSquared(observer)<45*45;
            if(nearby&&rank<DEBRIS_QUERY_LIMIT&&shard.bounces<2) {
                debrisQueries++;
                WorldQuery.Hit hit=world.staticSweep(shard.position,next,Math.min(.18f,shard.size));
                if(hit!=null) {
                    Vector3f normal=hit.normal().normalize();
                    next=hit.point().add(normal.mult(Math.min(.18f,shard.size)+.015f));
                    float into=shard.velocity.dot(normal);
                    if(into<0)shard.velocity.subtractLocal(normal.mult(1.22f*into)).multLocal(.48f);
                    shard.bounces++;
                    if(shard.velocity.lengthSquared()<.3f||shard.bounces>=2){shard.age=shard.life-.2f;shard.velocity.set(0,0,0);}
                }
            } else if(nearby&&shard.bounces<2) {
                // Never advance an unchecked fragment through a wall. Secondary
                // shards fade at their last checked position when the budget is full.
                next.set(shard.position);shard.age=Math.max(shard.age,shard.life-.12f);
            }
            shard.position.set(next);shard.rotation.multLocal(new Quaternion().fromAngles(dt*2.1f,dt*3.7f,dt*.9f));
        }
        debrisQueryCursor+=DEBRIS_QUERY_LIMIT;
        for(Iterator<HitFlare> it=hitFlares.iterator();it.hasNext();) {HitFlare flare=it.next();flare.age+=dt;if(flare.age>=.2f)it.remove();}
        Set<Long> live=new HashSet<>();
        for(ProjectileState rocket:states) {
            emissionPriority=rocket.ownerId()==0?1:0;
            live.add(rocket.id());Vector3f position=OrdnancePresentation.trailAnchor(rocket.kind(),rocket.position(),rocket.direction());
            Vector3f previous=trailHeads.get(rocket.id());
            if(previous==null)previous=OrdnancePresentation.trailAnchor(rocket.kind(),rocket.previousPosition(),rocket.direction());
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
                    else emit(at,Vector3f.ZERO,AMBER,.25f,.065f,.12f,0);
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
                    var profile=world.profile(id);
                    Vector3f bonnet=world.position(id).add(world.rotation(id).mult(new Vector3f(
                            (visualRandom.nextFloat()-.5f)*.06f,profile.id().equals("rivet")?.72f:profile.id().equals("grinder")?
                                    profile.hullBounds().maxY()+.12f:profile.hullBounds().maxY()*.6f,
                            profile.length()*.228f+(visualRandom.nextFloat()-.5f)*.06f)));
                    Vector3f rise=new Vector3f((visualRandom.nextFloat()-.5f)*.14f,1.10f,(visualRandom.nextFloat()-.5f)*.10f);
                    emit(bonnet,rise.addLocal(world.velocity(id).mult(.12f)),CRITICAL_SMOKE,1.8f,.18f,.25f,0);
                }
            } else emitter.smokeClock=0;
            if(vehicle.alive() && vehicle.turbo<emitter.previousTurbo-.01f && emitter.turboClock<=0) {
                var profile=world.profile(id);
                for(int side:new int[]{-1,1}) {
                    Vector3f localExhaust=profile.id().equals("rivet")?new Vector3f(side*.72f,-.11f,-2.49f):
                            new Vector3f(side*profile.width()*.34f,-.04f*profile.height()/1.07f,-profile.length()*.5225f);
                    Vector3f exhaust=world.position(id).add(world.rotation(id).mult(localExhaust));
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
        Vector3f normal=contactNormal(event);
        ContactSurface surface=event.surface();
        if(surface==ContactSurface.UNKNOWN)surface=event.subjectId()>=0?ContactSurface.METAL:ContactSurface.CONCRETE;
        boolean metal=surface==ContactSurface.METAL,glass=surface==ContactSurface.GLASS,rubber=surface==ContactSurface.RUBBER;
        int count=metal?12:glass?10:rubber?4:8;
        for(int i=0;i<count;i++) {
            Vector3f velocity=normal.mult(metal?4.8f:glass?2.3f:1.2f).addLocal(randomDirection(metal?3.2f:1.1f));
            float inward=velocity.dot(normal);if(inward<0)velocity.subtractLocal(normal.mult(inward));
            if(metal)emit(event.position().add(normal.mult(.025f)),velocity,AMBER,.28f,.052f,-.035f,8);
            else if(glass)addShard(event.position(),velocity,.52f,.022f,new ColorRGBA(.62f,.83f,.89f,.75f));
            else emit(event.position().add(normal.mult(.025f)),velocity,rubber?SMOKE:DUST,.45f,rubber?.06f:.12f,.30f,0);
        }
        if(metal)for(int i=0;i<3;i++)addShard(event.position(),normal.mult(2).addLocal(randomDirection(2.2f)),.38f,.025f,METAL);
        if(surface==ContactSurface.WOOD)for(int i=0;i<4;i++)addShard(event.position(),normal.mult(1.5f).addLocal(randomDirection(2)),.7f,.045f,new ColorRGBA(.39f,.22f,.10f,1));
    }
    private void ram(GameEvent event) {
        Vector3f normal=contactNormal(event);float force=Math.clamp(event.value()/12,.3f,1.8f);
        for(int i=0;i<(int)(12*force);i++) {
            Vector3f velocity=randomDirection(3*force).addLocal(normal.mult(1.5f)).addLocal(0,1,0);
            emit(event.position(),velocity,i%3==0?DUST:AMBER,i%3==0?.5f:.32f,i%3==0?.16f:.065f,.1f,i%3==0?0:7);
        }
        for(int i=0;i<(int)(5*force);i++)addShard(event.position(),randomDirection(3*force).addLocal(0,2,0),.6f,.045f,METAL);
    }
    private void ricochet(GameEvent event) {
        Vector3f normal=contactNormal(event),incoming=event.position().subtract(event.origin()).normalizeLocal();
        Vector3f reflected=incoming.subtract(normal.mult(2*incoming.dot(normal)));
        if(reflected.lengthSquared()<.1f)reflected=normal.clone();
        emit(event.position(),Vector3f.ZERO,AMBER,.055f,.22f,.2f,0);
        for(int i=0;i<16;i++) {
            Vector3f speed=reflected.mult(6+visualRandom.nextFloat()*5).addLocal(randomDirection(2.4f));
            float into=speed.dot(normal);if(into<0)speed.subtractLocal(normal.mult(into));
            emit(event.position().add(normal.mult(.025f)),speed,AMBER,.17f+i%3*.025f,.042f,-.08f,5);
            if(i<12)addShard(event.position(),speed.mult(.6f),.32f,.024f+visualRandom.nextFloat()*.024f,METAL);
        }
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
    private void explosion(Vector3f centre,Vector3f normal,String kind,boolean surfaceContact) {
        boolean mine=kind.equals("mine"),cannon=kind.startsWith("cannon"),ballistic=kind.equals("ballistic");
        String recipeKind=cannon?"cannon":kind.equals("ballistic-fall")?"ballistic":kind;
        if(kind.equals("freeze")){ice(centre,true);return;}
        var recipe=atlas.explosion(recipeKind);float scale=recipe.scale();
        int flameCount=recipe.flames(),fragmentCount=recipe.fragments();
        emit(centre.add(normal.mult(.12f)),Vector3f.ZERO,new ColorRGBA(1,.9f,.61f,1),recipe.flashSeconds(),.7f*scale,4,0);
        for(int i=0;i<flameCount;i++) {
            Vector3f velocity=randomDirection((ballistic?5.2f:3.8f)*scale);
            if(mine){velocity=surfaceRotation(normal).mult(new Vector3f(velocity.x*1.4f,.35f+Math.abs(velocity.y)*.18f,velocity.z*1.4f));}
            else velocity.addLocal(normal.mult(cannon?2:1.2f));
            if(surfaceContact){float inward=velocity.dot(normal);if(inward<0)velocity.subtractLocal(normal.mult(inward));}
            if(ballistic)velocity.y+=2.4f;
            Vector3f offset=randomDirection(.2f);
            if(kind.equals("destroyed"))offset.z+=(i%2==0?-.65f:.65f);
            emit(centre.add(offset),velocity,BLAST_FLAME,recipe.flameSeconds(),.28f*scale,1.45f,2);
        }
        for(int i=0;i<14;i++) {
            Vector3f drift=randomDirection(1.7f*scale);drift.y=Math.abs(drift.y)+1.5f;
            emit(centre.add(normal.mult(.25f)),drift,BLAST_SMOKE,recipe.smokeSeconds(),.32f,.55f,0);
        }
        for(int i=0;i<fragmentCount;i++) {
            Vector3f velocity=randomDirection((cannon?8:6)*scale).addLocal(normal.mult(2));
            if(mine)velocity=surfaceRotation(normal).mult(new Vector3f(velocity.x,.7f+Math.abs(velocity.y)*.2f,velocity.z));
            addShard(centre,velocity,.6f+visualRandom.nextFloat()*.25f,.045f+visualRandom.nextFloat()*.065f,METAL);
            emit(centre,velocity.mult(1.3f),AMBER,.38f,.065f,0,7);
        }
        Quaternion surface=surfaceRotation(normal);
        for(int i=0;surfaceContact&&i<recipe.dust();i++) {
            float angle=i*FastMath.TWO_PI/recipe.dust();
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
            if(victim<0)return;freeParticles.addLast(particles.remove(victim));
        }
        Particle particle=freeParticles.pollFirst();if(particle==null)particle=new Particle();
        particle.reset(position,velocity,color,lifetime,size,growth,gravity,emissionPriority,
                color==FIRE_FLAME?(visualRandom.nextFloat()-.5f)*.2f:visualRandom.nextFloat()*FastMath.TWO_PI,visualRandom.nextFloat());particles.add(particle);
    }
    private void addShard(Vector3f position,Vector3f velocity,float life,float size,ColorRGBA color) {
        if(shards.size()>=SHARD_LIMIT) {
            int victim=-1;float lowest=importance(position,emissionPriority,0);
            for(int i=0;i<shards.size();i++){Shard s=shards.get(i);float score=importance(s.position,s.priority,s.age/s.life);if(score<=lowest){lowest=score;victim=i;}}
            if(victim<0)return;shards.remove(victim);
        }
        shards.add(new Shard(position,velocity,new Quaternion().fromAngles(visualRandom.nextFloat()*3,visualRandom.nextFloat()*3,visualRandom.nextFloat()*3),life,size,color,emissionPriority));
    }
    /** Decorative detached panels stay in the presentation budget and never become Bullet bodies. */
    public void detachPanel(Vector3f position,Quaternion rotation,Vector3f inheritedVelocity,Vector3f halfExtents,ContactSurface material,int sourceId) {
        if(closed||!Vector3f.isValidVector(position)||!Vector3f.isValidVector(inheritedVelocity)||!Vector3f.isValidVector(halfExtents))return;
        emissionPriority=sourceId==0?3:2;
        if(shards.size()>=SHARD_LIMIT)shards.remove(0);
        Vector3f velocity=inheritedVelocity.clone();if(velocity.lengthSquared()>80*80)velocity.normalizeLocal().multLocal(80);
        Shard panel=new Shard(position,velocity,rotation.clone(),2.8f,Math.min(.18f,halfExtents.length()),
                material==ContactSurface.GLASS?new ColorRGBA(.5f,.7f,.75f,.72f):METAL,emissionPriority);
        panel.halfExtents=new Vector3f(Math.clamp(halfExtents.x,.005f,.8f),Math.clamp(halfExtents.y,.005f,.7f),Math.clamp(halfExtents.z,.005f,1));
        shards.add(panel);emissionPriority=0;
    }
    public int debrisQueriesLastFrame() {return debrisQueries;}
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
        if(flashIntensity==0||position.distanceSquared(observer)>45*45)return;
        BlastLight chosen=null;float lowest=importance(position,emissionPriority,0);
        for(BlastLight light:lights) {
            if(!light.light.isEnabled()){chosen=light;break;}
            float score=importance(light.light.getPosition(),light.priority,light.age/.22f);
            if(score<=lowest){lowest=score;chosen=light;}
        }
        if(chosen==null)return;
        chosen.age=0;chosen.priority=emissionPriority;chosen.light.setPosition(position.add(0,.5f,0));
        chosen.light.setRadius(11*scale);chosen.light.setColor(new ColorRGBA(3.5f,1.3f,.24f,1).mult(flashIntensity));chosen.light.setEnabled(true);
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
        ordnance.update(projectiles,mines,observer);renderFields();renderFragments();
    }
    private void renderParticles(Camera camera) {
        int count=particles.size();for(int i=0;i<count;i++)sortedParticles[i]=particles.get(i);
        if(camera!=null && count>1) {sortEye.set(camera.getLocation());sortForward.set(camera.getDirection());Arrays.sort(sortedParticles,0,count,particleOrder);}
        particleBatch.begin();
        sparkBatch.begin();
        for(int i=0;i<count;i++) {
            Particle p=sortedParticles[i];
            float size=Math.clamp(p.size+p.age*p.growth,.015f,p.smoke?(p.blast?1.6f:SMOKE_RADIUS_LIMIT):FLASH_RADIUS_LIMIT);
            float life=p.age/p.lifetime;
            float fade=p.criticalSmoke?Math.min(1,p.age/.12f)*Math.clamp((p.lifetime-p.age)/.65f,0,1):
                    p.blast&&p.smoke?Math.min(1,p.age/.18f)*Math.clamp((p.lifetime-p.age)/.6f,0,1):
                    (1-life)*(p.smoke?Math.min(1,life*12):1);
            float alpha=p.color.a*fade;
            float shape=p.dust?7:p.flame?8:p.criticalSmoke||p.blast&&p.smoke?3:p.smoke?1:p.blast?4:2;
            Batch batch=p.smoke||p.blast||p.flame?particleBatch:sparkBatch;
            batch.sprite(p.position,size,p.color,alpha,shape,p.rotation,p.variation,CombatVfxAtlas.frame(p.age,p.lifetime),p.smoke?.35f:p.blast?.22f:.045f);
        }
        for(TracerSegment tracer:tracerSegments()) {
            Vector3f direction=tracer.to.subtract(tracer.from).normalizeLocal();
            Vector3f side=direction.cross(Vector3f.UNIT_Y);
            if(side.lengthSquared()<.001f)side=direction.cross(Vector3f.UNIT_Z);
            side.normalizeLocal().multLocal(.014f);
            sparkBatch.quad(tracer.from.subtract(side),tracer.from.add(side),tracer.to.add(side),tracer.to.subtract(side),AMBER,.9f);
            side=direction.cross(side).normalizeLocal().multLocal(.014f);
            sparkBatch.quad(tracer.from.subtract(side),tracer.from.add(side),tracer.to.add(side),tracer.to.subtract(side),AMBER,.9f);
        }
        particleBatch.end();
        sparkBatch.end();
    }
    List<TracerSegment> tracerSegments() {
        List<TracerSegment> result=new ArrayList<>();
        for(GunShot shot:shots.values())if(shot.tracer&&!shot.occluded&&shot.distance>.001f&&shot.age>0) {
            float head=Math.min(shot.distance,shot.flight>0?shot.distance*shot.age/shot.flight:shot.distance),tail=Math.max(0,head-TRACER_LENGTH);
            result.add(new TracerSegment(shot.id,shot.origin.add(shot.direction.mult(tail)),shot.origin.add(shot.direction.mult(head))));
        }
        return result;
    }
    private void renderFragments() {
        fragmentBatch.begin();
        for(Shard shard:shards) {
            float alpha=shard.color.a*Math.min(1,(shard.life-shard.age)/.2f);
            fragmentBatch.box(shard.position,shard.rotation,shard.halfExtents,shard.color,alpha);
        }
        for(HitFlare flare:hitFlares) {
            Quaternion rotation=surfaceRotation(flare.normal);Vector3f centre=flare.position.add(flare.normal.mult(.08f));
            float radius=.13f+flare.age*2.2f,alpha=.8f*(1-flare.age/.2f)*(.25f+.75f*flashIntensity);
            for(int segment=0;segment<16;segment++) {
                float a=segment*FastMath.TWO_PI/16,b=(segment+1)*FastMath.TWO_PI/16;
                fragmentBatch.triangle(centre,local(centre,rotation,FastMath.cos(a)*radius,0,FastMath.sin(a)*radius),
                        local(centre,rotation,FastMath.cos(b)*radius,0,FastMath.sin(b)*radius),new ColorRGBA(.3f,.7f,1,1),alpha);
            }
        }
        fragmentBatch.end();
    }
    private void renderFields() {
        pruneFireSurfaces();
        fieldBatch.begin();
        // Reserve every warning first, but draw it last so transparent soot cannot dim it.
        fieldBatch.reserveVertices(warnings.size()*(48*6+12));
        for(var fire:fires) {
            if(fieldBatch.positions.remaining()<18)break;
            if(!fire.surfacePoints().isEmpty())fieldBatch.groundPatches(fireSurface(fire));
        }
        for(CosmeticFire fire:cosmeticFires) {
            // Ballistic impacts can hit walls as well as driveable support surfaces.
            Quaternion pose=surfaceRotation(fire.normal);Vector3f centre=fire.position.add(fire.normal.mult(.035f));
            fieldBatch.maskedQuad(local(centre,pose,-.82f,0,-.82f),local(centre,pose,.82f,0,-.82f),
                    local(centre,pose,.82f,0,.82f),local(centre,pose,-.82f,0,.82f),
                    SCORCH,.40f*(1-fire.age/2),5,255);
        }
        fieldBatch.releaseReservedVertices();
        renderWarnings();
        fieldBatch.end();
    }
    private FireSurface fireSurface(CombatSystem.FireZoneView fire) {
        for(int i=0;i<fireSurfaces.size();i++) {
            FireSurface surface=fireSurfaces.get(i);
            if(surface.id!=fire.id())continue;
            if(surface.matches(fire))return surface;
            cachedFirePoints-=surface.edges.length;fireSurfaces.remove(i);break;
        }
        FireSurface surface=new FireSurface(fire);fireTopologyBuilds++;
        // Even oversized diagnostic snapshots cannot retain more than one ground buffer.
        // Uncached overflow uses the same prepared geometry and still respects draw priority.
        if(cachedFirePoints+surface.edges.length<=CACHED_FIRE_POINT_LIMIT&&fire.surfacePoints().size()<=CACHED_FIRE_POINT_LIMIT) {
            fireSurfaces.add(surface);cachedFirePoints+=surface.edges.length;
        }
        return surface;
    }
    private void pruneFireSurfaces() {
        for(int i=fireSurfaces.size()-1;i>=0;i--) {
            FireSurface surface=fireSurfaces.get(i);boolean live=false;
            for(int j=0;j<fires.size();j++) {
                var fire=fires.get(j);
                if(fire.id()==surface.id&&!fire.surfacePoints().isEmpty()){live=true;break;}
            }
            if(!live){cachedFirePoints-=surface.edges.length;fireSurfaces.remove(i);}
        }
    }
    private static Vector3f surfacePoint(Vector3f point,Vector3f normal,float x,float z,float lift) {
        return point.add(x,-(normal.x*x+normal.z*z)/Math.max(.6f,normal.y),z).addLocal(normal.mult(lift));
    }
    private static long gridCell(int x,int z) {return ((long)x<<32)|(z&0xffffffffL);}
    private void renderWarnings() {
        for(var warning:warnings) {
            Vector3f normal=warning.normal(),centre=warning.point().add(normal.mult(.045f));float radius=warning.radius();
            Quaternion pose=surfaceRotation(normal);
            float pulse=.55f+.10f*FastMath.sin(warning.remainingTicks()*FastMath.TWO_PI/36);
            for(int segment=0;segment<48;segment++) {
                float a=segment*FastMath.TWO_PI/48,b=(segment+1)*FastMath.TWO_PI/48;
                fieldBatch.maskedQuad(local(centre,pose,FastMath.cos(a)*radius,0,FastMath.sin(a)*radius),
                        local(centre,pose,FastMath.cos(b)*radius,0,FastMath.sin(b)*radius),
                        local(centre,pose,FastMath.cos(b)*(radius-.075f),0,FastMath.sin(b)*(radius-.075f)),
                        local(centre,pose,FastMath.cos(a)*(radius-.075f),0,FastMath.sin(a)*(radius-.075f)),AMBER,pulse,6,0);
            }
            for(int axis=0;axis<2;axis++) {
                Vector3f length=axis==0?new Vector3f(.23f,0,0):new Vector3f(0,0,.23f),width=axis==0?new Vector3f(0,0,.025f):new Vector3f(.025f,0,0);
                pose.multLocal(length);pose.multLocal(width);
                fieldBatch.maskedQuad(centre.subtract(length).subtractLocal(width),centre.add(length).subtractLocal(width),
                        centre.add(length).addLocal(width),centre.subtract(length).addLocal(width),AMBER,pulse,6,0);
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
    int fireTopologyBuildCount(){return fireTopologyBuilds;}
    int cachedFirePointCount(){return cachedFirePoints;}
    public int projectileCount(){return ordnance.projectileCount();}
    @Override public void close(){if(closed)return;closed=true;ordnance.close();root.removeFromParent();for(BlastLight light:lights){light.light.setEnabled(false);scene.removeLight(light.light);}
        particles.clear();freeParticles.clear();Arrays.fill(sortedParticles,null);shots.clear();destroyedTargets.clear();shards.clear();hitFlares.clear();cosmeticFires.clear();recentEvents.clear();presentedEvents.clear();trailHeads.clear();
        fireSurfaces.clear();cachedFirePoints=0;
        projectiles=List.of();mines=List.of();fires=List.of();warnings=List.of();fireExposures=List.of();vehicleModels=Map.of();}

    private static final class Batch {
        final Geometry geometry;
        final Mesh mesh=new Mesh();
        final FloatBuffer positions,colors,textureCoordinates,spriteData,spriteVariation,spriteAnimation,normals;
        private final Vector3f faceNormal=new Vector3f(0,1,0),faceEdge=new Vector3f();
        private final Vector3f[] boxPoints=new Vector3f[8];
        private static final int[] BOX_TRIANGLES={0,2,1,1,2,3,4,5,6,5,7,6,0,1,4,1,5,4,2,6,3,3,6,7,0,4,2,2,4,6,1,3,5,3,7,5};
        final boolean softSprites;
        Batch(Node root,String name,AssetManager assets,int vertices,boolean transparent,boolean softSprites) {
            this.softSprites=softSprites;
            positions=BufferUtils.createFloatBuffer(vertices*3);colors=BufferUtils.createFloatBuffer(vertices*4);
            mesh.setBuffer(VertexBuffer.Type.Position,3,positions);mesh.setBuffer(VertexBuffer.Type.Color,4,colors);mesh.setDynamic();
            normals=softSprites?null:BufferUtils.createFloatBuffer(vertices*3);
            if(normals!=null)mesh.setBuffer(VertexBuffer.Type.Normal,3,normals);
            for(int i=0;i<8;i++)boxPoints[i]=new Vector3f();
            textureCoordinates=softSprites?BufferUtils.createFloatBuffer(vertices*2):null;
            spriteData=softSprites?BufferUtils.createFloatBuffer(vertices*2):null;
            spriteVariation=softSprites?BufferUtils.createFloatBuffer(vertices*2):null;
            spriteAnimation=softSprites?BufferUtils.createFloatBuffer(vertices*2):null;
            if(softSprites) {
                mesh.setBuffer(VertexBuffer.Type.TexCoord,2,textureCoordinates);
                mesh.setBuffer(VertexBuffer.Type.TexCoord2,2,spriteData);
                mesh.setBuffer(VertexBuffer.Type.TexCoord3,2,spriteVariation);
                mesh.setBuffer(VertexBuffer.Type.TexCoord4,2,spriteAnimation);
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
        void begin(){positions.clear();colors.clear();if(normals!=null)normals.clear();if(softSprites){textureCoordinates.clear();spriteData.clear();spriteVariation.clear();spriteAnimation.clear();}}
        void reserveVertices(int count){positions.limit(positions.capacity()-Math.min(count,positions.capacity()/3)*3);}
        void releaseReservedVertices(){positions.limit(positions.capacity());}
        void vertex(float x,float y,float z,ColorRGBA color,float alpha) {
            vertex(x,y,z,color,alpha,0,0,0,0);
        }
        void vertex(float x,float y,float z,ColorRGBA color,float alpha,float u,float v,float radius,float shape) {
            if(positions.remaining()<3)return;
            positions.put(x).put(y).put(z);colors.put(color.r).put(color.g).put(color.b).put(alpha);
            if(normals!=null)normals.put(faceNormal.x).put(faceNormal.y).put(faceNormal.z);
            if(softSprites){textureCoordinates.put(u).put(v);spriteData.put(radius).put(shape);spriteVariation.put(0).put(0);spriteAnimation.put(0).put(.03f);}
        }
        void sprite(Vector3f centre,float radius,ColorRGBA color,float alpha,float shape,float rotation,float variation,float frame,float softness) {
            if(positions.remaining()<18)return;
            for(int i=0;i<SPRITE_UV.length;i+=2) {
                vertex(centre.x,centre.y,centre.z,color,alpha,SPRITE_UV[i],SPRITE_UV[i+1],radius,shape);
                spriteVariation.put(spriteVariation.position()-2,rotation);spriteVariation.put(spriteVariation.position()-1,variation);
                spriteAnimation.put(spriteAnimation.position()-2,frame);spriteAnimation.put(spriteAnimation.position()-1,softness);
            }
        }
        void triangle(Vector3f a,Vector3f b,Vector3f c,ColorRGBA color,float alpha) {
            if(positions.remaining()<9)return;
            if(normals!=null)faceNormal.set(b).subtractLocal(a).crossLocal(faceEdge.set(c).subtractLocal(a)).normalizeLocal();
            vertex(a.x,a.y,a.z,color,alpha);vertex(b.x,b.y,b.z,color,alpha);vertex(c.x,c.y,c.z,color,alpha);
        }
        void box(Vector3f centre,Quaternion rotation,Vector3f half,ColorRGBA color,float alpha) {
            if(positions.remaining()<36*3)return;
            for(int i=0;i<8;i++) {
                Vector3f p=boxPoints[i].set((i&1)==0?-half.x:half.x,(i&2)==0?-half.y:half.y,(i&4)==0?-half.z:half.z);
                rotation.mult(p,p);p.addLocal(centre);
            }
            for(int i=0;i<BOX_TRIANGLES.length;i+=3)triangle(boxPoints[BOX_TRIANGLES[i]],boxPoints[BOX_TRIANGLES[i+1]],boxPoints[BOX_TRIANGLES[i+2]],color,alpha);
        }
        void maskedQuad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,ColorRGBA color,float alpha,float shape,float mask) {
            if(positions.remaining()<18)return;
            Vector3f[] points={a,b,c,a,c,d};
            for(int i=0;i<points.length;i++) {
                Vector3f p=points[i];
                // Zero sprite radius leaves these vertices fixed to the support plane.
                vertex(p.x,p.y,p.z,color,alpha,SPRITE_UV[i*2],SPRITE_UV[i*2+1],0,shape);
                spriteVariation.put(spriteVariation.position()-2,mask);
            }
        }
        void groundPatches(FireSurface surface) {
            int count=Math.min(surface.edges.length,positions.remaining()/18);
            for(int patch=0;patch<count;patch++) {
                positions.put(surface.vertices,patch*18,18);textureCoordinates.put(SPRITE_UV);
                for(int vertex=0;vertex<6;vertex++) {
                    colors.put(SCORCH.r).put(SCORCH.g).put(SCORCH.b).put(.48f);
                    spriteData.put(0).put(5);spriteVariation.put(surface.edges[patch]&255).put(0);spriteAnimation.put(0).put(.03f);
                }
            }
        }
        void quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,ColorRGBA color,float alpha) {
            if(positions.remaining()<18)return;
            triangle(a,b,c,color,alpha);triangle(a,c,d,color,alpha);
        }
        void end() {
            positions.flip();colors.flip();
            mesh.getBuffer(VertexBuffer.Type.Position).updateData(positions);mesh.getBuffer(VertexBuffer.Type.Color).updateData(colors);
            if(normals!=null){normals.flip();mesh.getBuffer(VertexBuffer.Type.Normal).updateData(normals);}
            if(softSprites) {
                textureCoordinates.flip();spriteData.flip();spriteVariation.flip();spriteAnimation.flip();
                mesh.getBuffer(VertexBuffer.Type.TexCoord).updateData(textureCoordinates);
                mesh.getBuffer(VertexBuffer.Type.TexCoord2).updateData(spriteData);
                mesh.getBuffer(VertexBuffer.Type.TexCoord3).updateData(spriteVariation);
                mesh.getBuffer(VertexBuffer.Type.TexCoord4).updateData(spriteAnimation);
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
