package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import game.wreckriff.combat.CombatSystem.SpecialBombView;
import game.wreckriff.combat.SpecialRules;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.simulation.GameEvent;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.PhysicsWorld;
import game.wreckriff.simulation.VehicleState;
import game.wreckriff.simulation.WorldQuery;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Two bounded draws for chassis specials. Simulation owns contacts, damage, fuse and every timer. */
public final class SpecialPresentation implements AutoCloseable {
    public static final int PULSE_LIMIT=24, BOMB_LIMIT=SpecialRules.MAXIMUM_BOMBS, SPARK_LIMIT=128;
    private static final int VEHICLE_LIMIT=32, EVENT_HISTORY_LIMIT=512, PULSE_LIFETIME_TICKS=24;
    private static final ColorRGBA PULSE=new ColorRGBA(1,.86f,.32f,1), GRINDER=new ColorRGBA(1,.36f,.055f,1);
    private static final ColorRGBA JET=new ColorRGBA(.16f,.88f,1,1), WHITE=new ColorRGBA(1,.96f,.80f,1);
    private static final ColorRGBA SHELL=new ColorRGBA(.16f,.19f,.21f,1), BOMB=new ColorRGBA(1,.21f,.04f,1);
    private record EventKey(UUID sessionId,long id,int subject) {}
    private record Pose(Vector3f position,Quaternion rotation) {
        Vector3f at(Vector3f local) {return position.add(rotation.mult(local));}
    }
    private static final class Pulse {
        final GameEvent event;
        final Vector3f origin,end;
        long startedTick=-1;
        Pulse(GameEvent event,Vector3f end) {this.event=event;origin=event.origin();this.end=end;}
    }
    private static final class Rollers {
        final Spatial left,right;
        final Quaternion leftRest,rightRest;
        long lastTick=-1;
        float angle;
        Rollers(Spatial left,Spatial right) {
            this.left=left;this.right=right;leftRest=left.getLocalRotation().clone();rightRest=right.getLocalRotation().clone();
        }
        void reset() {left.setLocalRotation(leftRest.clone());right.setLocalRotation(rightRest.clone());angle=0;lastTick=-1;}
    }

    private final Node root=new Node("special-presentation");
    private final WorldQuery world;
    private final Batch signals,bombModels;
    private final Map<Integer,Node> models=new LinkedHashMap<>();
    private final Map<Integer,Rollers> rollers=new LinkedHashMap<>();
    private final LinkedHashMap<EventKey,Pulse> pulses=new LinkedHashMap<>();
    private final LinkedHashSet<EventKey> seen=new LinkedHashSet<>();
    private UUID sessionId;
    private long lastTick=-1;
    private float flashIntensity=1;
    private int sparks;
    private boolean closed;

    public SpecialPresentation(AssetManager assets,Node parent,WorldQuery world) {
        this.world=Objects.requireNonNull(world);
        root.setShadowMode(RenderQueue.ShadowMode.Off);
        signals=new Batch(assets,root,"special-signals",32768,true);
        bombModels=new Batch(assets,root,"special-bomb-models",BOMB_LIMIT*96,false);
        Objects.requireNonNull(parent).attachChild(root);
    }

    /** Bind scene models whose local pose is in world coordinates; call update after their pose update. */
    public void bindModels(Map<Integer,Node> models) {
        if(closed)return;
        rollers.values().forEach(Rollers::reset);rollers.clear();
        this.models.clear();this.models.putAll(models);
        for(var entry:models.entrySet()) {
            if(rollers.size()>=VEHICLE_LIMIT)break;
            Spatial left=entry.getValue().getChild("grinder-roller-left"),right=entry.getValue().getChild("grinder-roller-right");
            if(left!=null&&right!=null)rollers.put(entry.getKey(),new Rollers(left,right));
        }
    }

    /** Minimum flash keeps solid threat markers and steady lights readable. */
    public void setFlashIntensity(float intensity) {
        if(!Float.isFinite(intensity)||intensity<0||intensity>1)throw new IllegalArgumentException("Flash intensity 0..1 required");
        flashIntensity=intensity;
    }

    public void accept(List<GameEvent> events) {
        if(closed)return;
        // A death anywhere in the batch wins over a late personal pulse from the same participant.
        var destroyed=new LinkedHashSet<Integer>();
        for(var event:events)if(belongsToSession(event)&&event.type()==GameEvent.Type.DESTROYED)destroyed.add(event.subjectId());
        pulses.values().removeIf(pulse->destroyed.contains(pulse.event.sourceId()));
        for(var event:events) {
            if(!belongsToSession(event)||event.type()!=GameEvent.Type.SPECIAL_HIT||!"pulse".equals(event.kind()))continue;
            EventKey key=new EventKey(event.sessionId(),event.eventId(),event.subjectId());
            if(!seen.add(key))continue;
            if(seen.size()>EVENT_HISTORY_LIMIT)seen.removeFirst();
            if(destroyed.contains(event.sourceId()))continue;
            Vector3f end=event.position();
            // A miss has a range endpoint, not a surface hit: do not draw that beam through a wall.
            var obstruction=world.ray(event.origin(),end,event.sourceId());
            if(obstruction!=null&&obstruction.fraction()<1)end=obstruction.point().clone();
            if(pulses.size()>=PULSE_LIMIT)pulses.remove(pulses.keySet().iterator().next());
            pulses.put(key,new Pulse(event,end));
        }
    }

    /** Alpha affects native pose interpolation only; an unchanged simulation tick cannot age an effect. */
    public void update(MatchSession session,List<SpecialBombView> bombs,float alpha) {
        if(closed)return;
        Objects.requireNonNull(session);Objects.requireNonNull(bombs);
        if(sessionId!=null&&(!sessionId.equals(session.sessionId)||session.tick<lastTick)) {
            pulses.clear();seen.clear();rollers.values().forEach(Rollers::reset);
        }
        sessionId=session.sessionId;lastTick=session.tick;
        signals.begin();bombModels.begin();sparks=0;
        updateRollers(session);
        if(session.outcome!=MatchSession.Outcome.NONE) {
            pulses.clear();signals.end();bombModels.end();return;
        }
        int vehicles=0;
        for(var state:session.vehicles) {
            if(!state.alive()||state.boss||!state.specialActive())continue;
            if(vehicles++>=VEHICLE_LIMIT)break;
            Pose pose=pose(state.id,alpha);VehicleProfile profile=world.profile(state.id);
            switch(state.specialPhase) {
                case PULSE_WINDUP -> grille(state.id,pose,profile,PULSE,progress(state.specialTicks,SpecialRules.PULSE_WINDUP),false);
                case GRINDER_WINDUP -> grille(state.id,pose,profile,GRINDER,progress(state.specialTicks,SpecialRules.GRINDER_WINDUP),false);
                case GRINDER_SEARCH, GRINDER_CONTACT -> {
                    grille(state.id,pose,profile,GRINDER,1,true);
                    if(state.specialPhase==VehicleState.SpecialPhase.GRINDER_CONTACT
                            &&session.containsParticipant(state.specialTargetId)&&session.vehicle(state.specialTargetId).alive()
                            &&world.grounded(state.id)&&world.grounded(state.specialTargetId)
                            &&world.touchingVehicles(state.id,state.specialTargetId))grinding(state,pose,profile,session.tick);
                }
                case DASH -> {if(world.dashActive(state.id))jets(state,pose,profile,session.tick);}
                case READY -> { }
            }
        }
        pulses.values().removeIf(pulse->!belongsToSession(pulse.event)
                ||!session.containsParticipant(pulse.event.sourceId())||!session.vehicle(pulse.event.sourceId()).alive());
        for(var iterator=pulses.values().iterator();iterator.hasNext();) {
            Pulse pulse=iterator.next();if(pulse.startedTick<0)pulse.startedTick=session.tick;
            long age=session.tick-pulse.startedTick;
            if(age>=PULSE_LIFETIME_TICKS) {iterator.remove();continue;}
            pulse(pulse,age/(float)PULSE_LIFETIME_TICKS);
        }
        // Launched bombs remain dangerous after the owner dies, and remain visible for the same lifetime.
        int count=0;
        var renderedBombs=new LinkedHashSet<Long>();
        for(var bomb:bombs) {
            if(bomb.remainingTicks()<=0||!renderedBombs.add(bomb.id()))continue;
            if(count++>=BOMB_LIMIT)break;
            bomb(bomb);
        }
        signals.end();bombModels.end();
    }

    private boolean belongsToSession(GameEvent event) {
        return sessionId==null||event.sessionId()==null||sessionId.equals(event.sessionId());
    }

    private Pose pose(int id,float alpha) {
        Node model=models.get(id);
        if(model!=null)return new Pose(model.getLocalTranslation(),model.getLocalRotation());
        if(world instanceof PhysicsWorld physics) {
            var pose=physics.interpolatedPose(id,Math.clamp(alpha,0,1));return new Pose(pose.position(),pose.rotation());
        }
        return new Pose(world.position(id),world.rotation(id));
    }

    private static float progress(int remainingTicks,float duration) {
        return Math.clamp(1-remainingTicks/(duration*MatchSession.TICKS_PER_SECOND),0,1);
    }

    private Vector3f anchor(int id,String name,Vector3f fallback) {
        Node model=models.get(id);Spatial socket=model==null?null:model.getChild(name);
        return socket==null?fallback:socket.getLocalTranslation().clone();
    }

    private void updateRollers(MatchSession session) {
        for(var entry:rollers.entrySet()) {
            Rollers shafts=entry.getValue();float rate=0;
            if(session.outcome==MatchSession.Outcome.NONE&&session.containsParticipant(entry.getKey())) {
                VehicleState state=session.vehicle(entry.getKey());
                if(state.alive())rate=switch(state.specialPhase) {
                    case GRINDER_WINDUP -> 24*progress(state.specialTicks,SpecialRules.GRINDER_WINDUP);
                    case GRINDER_SEARCH, GRINDER_CONTACT -> 24;
                    default -> 0;
                };
            }
            if(shafts.lastTick>=0)shafts.angle=(float)((shafts.angle+rate*Math.max(0,session.tick-shafts.lastTick)*MatchSession.DT)%FastMath.TWO_PI);
            shafts.lastTick=session.tick;
            shafts.left.setLocalRotation(shafts.leftRest.mult(new Quaternion().fromAngleAxis(shafts.angle,Vector3f.UNIT_X)));
            shafts.right.setLocalRotation(shafts.rightRest.mult(new Quaternion().fromAngleAxis(-shafts.angle,Vector3f.UNIT_X)));
        }
    }

    private void grille(int id,Pose pose,VehicleProfile profile,ColorRGBA color,float charge,boolean armed) {
        Vector3f intake=anchor(id,"rivet".equals(profile.id())?"rivet-impulse-emitter":"grinder-intake",profile.grinderIntake());
        float halfWidth=profile.width()*.32f;
        float brightness=armed?.85f:.35f+.55f*charge;
        for(int bar=0;bar<6;bar++) {
            float x=-halfWidth+bar*(halfWidth*2/6),width=halfWidth*2/6*.74f;
            float alpha=bar<1+(int)(charge*5)?brightness:.16f;
            Vector3f a=new Vector3f(x,intake.y+.17f,intake.z+.055f),b=a.add(width,0,0);
            signals.quad(pose.at(a),pose.at(b),pose.at(b.add(0,.12f,0)),pose.at(a.add(0,.12f,0)),color,alpha);
        }
        // Two compact arrows at the actual intake indicate the direction, not an area-wide stun field.
        for(int side:new int[]{-1,1}) {
            float x=side*halfWidth*.84f;
            Vector3f a=new Vector3f(x-.14f,intake.y+.09f,intake.z+.10f);
            Vector3f b=new Vector3f(x+.14f,intake.y+.09f,intake.z+.10f);
            Vector3f tip=new Vector3f(x,intake.y+.09f,intake.z+.57f);
            signals.triangle(pose.at(a),pose.at(b),pose.at(tip),color,armed?.60f:.25f+.35f*charge);
        }
    }

    private void grinding(VehicleState state,Pose pose,VehicleProfile profile,long tick) {
        Vector3f intake=pose.at(profile.grinderIntake());
        Vector3f contact=world.closestHullPoint(state.specialTargetId,intake);
        for(int i=0;i<12&&sparks<SPARK_LIMIT;i++,sparks++) {
            float age=Math.floorMod(tick+i*5,19)/(float)MatchSession.TICKS_PER_SECOND;
            float angle=i*2.399963f;
            Vector3f direction=pose.rotation.mult(new Vector3f(FastMath.cos(angle)*3.4f,2.7f+FastMath.sin(angle)*1.1f,-.8f));
            Vector3f head=contact.add(direction.mult(age)).addLocal(0,-9*age*age,0);
            Vector3f tail=head.subtract(direction.mult(.028f));
            beam(signals,tail,head,.026f,WHITE,(.85f-age*3)*(.25f+.75f*flashIntensity));
        }
    }

    private void jets(VehicleState state,Pose pose,VehicleProfile profile,long tick) {
        if(state.dashDirection.lengthSquared()<.01f)return;
        Vector3f exhaust=state.dashDirection.normalize().negateLocal();
        float side=exhaust.dot(pose.rotation.mult(Vector3f.UNIT_X))<0?-1:1;
        float flicker=.92f+.08f*flashIntensity*FastMath.sin((tick%120)*2.1f);
        Vector3f socket=anchor(state.id,"spark-nozzle-"+(side<0?"left":"right"),new Vector3f(side*(profile.width()*.5f+.095f),.11f,-.21f));
        Vector3f nozzle=pose.at(socket),end=nozzle.add(exhaust.mult(1.15f*flicker));
        beam(signals,nozzle,end,.15f,JET,.55f);
        beam(signals,nozzle,nozzle.add(exhaust.mult(.57f*flicker)),.065f,WHITE,.90f);
    }

    private void pulse(Pulse pulse,float age) {
        float strength=(1-age)*(.3f+.7f*flashIntensity);
        beam(signals,pulse.origin,pulse.end,.14f*(1-age*.65f),PULSE,strength*.45f);
        beam(signals,pulse.origin,pulse.end,.035f,WHITE,strength);
        if(pulse.event.subjectId()<0)return;
        Vector3f direction=pulse.end.subtract(pulse.origin);
        if(direction.lengthSquared()<.001f)return;
        ring(signals,pulse.end,direction.normalizeLocal(),.19f+age*.65f,.07f,PULSE,strength*.85f,16);
    }

    private void bomb(SpecialBombView bomb) {
        Vector3f normal=bomb.normal().clone();if(normal.lengthSquared()<.01f)normal.set(Vector3f.UNIT_Y);else normal.normalizeLocal();
        Quaternion rotation=new Quaternion();rotation.lookAt(normal,Math.abs(normal.y)>.95f?Vector3f.UNIT_Z:Vector3f.UNIT_Y);
        // Local cylinder axis Z follows the exact supporting surface normal, including banked road.
        Vector3f base=bomb.position().add(normal.mult(.035f));
        Vector3f top=base.add(normal.mult(.24f));
        for(int i=0;i<8;i++) {
            float a=i*FastMath.TWO_PI/8,b=(i+1)*FastMath.TWO_PI/8;
            Vector3f radialA=rotation.mult(new Vector3f(FastMath.cos(a)*.23f,FastMath.sin(a)*.23f,0));
            Vector3f radialB=rotation.mult(new Vector3f(FastMath.cos(b)*.23f,FastMath.sin(b)*.23f,0));
            bombModels.quad(base.add(radialA),base.add(radialB),top.add(radialB),top.add(radialA),SHELL,1);
            bombModels.triangle(top,top.add(radialA),top.add(radialB),i%2==0?BOMB:SHELL,1);
        }
        int period=bomb.remainingTicks()>28?12:6;
        float lamp=.38f+flashIntensity*(bomb.remainingTicks()/period%2==0?.62f:.08f);
        ring(signals,top.add(normal.mult(.012f)),normal,.13f,.065f,WHITE,lamp,12);
        float fraction=Math.clamp(bomb.remainingTicks()/(SpecialRules.BOMB_FUSE*MatchSession.TICKS_PER_SECOND),0,1);
        // A small fuse clock stays on the canister: no false full-radius field through other floors.
        ring(signals,base.add(normal.mult(.018f)),normal,.43f,.055f,BOMB,.52f,24);
        int segments=Math.max(1,(int)Math.ceil(fraction*24));
        for(int i=0;i<segments;i++) {
            float angle=i*FastMath.TWO_PI/24,next=(i+.72f)*FastMath.TWO_PI/24;
            Vector3f a=rotation.mult(new Vector3f(FastMath.cos(angle)*.43f,FastMath.sin(angle)*.43f,0));
            Vector3f b=rotation.mult(new Vector3f(FastMath.cos(next)*.43f,FastMath.sin(next)*.43f,0));
            beam(signals,base.add(a).addLocal(normal.mult(.025f)),base.add(b).addLocal(normal.mult(.025f)),.028f,WHITE,.78f);
        }
    }

    private static void beam(Batch batch,Vector3f from,Vector3f to,float width,ColorRGBA color,float alpha) {
        Vector3f direction=to.subtract(from);if(direction.lengthSquared()<.000001f)return;direction.normalizeLocal();
        Vector3f across=direction.cross(Math.abs(direction.y)>.95f?Vector3f.UNIT_X:Vector3f.UNIT_Y).normalizeLocal().multLocal(width);
        Vector3f other=direction.cross(across).normalizeLocal().multLocal(width);
        batch.quad(from.subtract(across),from.add(across),to.add(across),to.subtract(across),color,alpha);
        batch.quad(from.subtract(other),from.add(other),to.add(other),to.subtract(other),color,alpha);
    }

    private static void ring(Batch batch,Vector3f center,Vector3f normal,float radius,float width,ColorRGBA color,float alpha,int segments) {
        Vector3f x=normal.cross(Math.abs(normal.y)>.95f?Vector3f.UNIT_Z:Vector3f.UNIT_Y).normalizeLocal();
        Vector3f y=normal.cross(x).normalizeLocal();
        for(int i=0;i<segments;i++) {
            float a=i*FastMath.TWO_PI/segments,b=(i+1)*FastMath.TWO_PI/segments;
            Vector3f first=x.mult(FastMath.cos(a)).addLocal(y.mult(FastMath.sin(a)));
            Vector3f second=x.mult(FastMath.cos(b)).addLocal(y.mult(FastMath.sin(b)));
            batch.quad(center.add(first.mult(radius-width)),center.add(first.mult(radius+width)),
                    center.add(second.mult(radius+width)),center.add(second.mult(radius-width)),color,alpha);
        }
    }

    @Override public void close() {
        if(closed)return;closed=true;
        root.removeFromParent();root.detachAllChildren();pulses.clear();seen.clear();
        rollers.values().forEach(Rollers::reset);rollers.clear();models.clear();
    }

    private static final class Batch {
        final Geometry geometry;
        final Mesh mesh=new Mesh();
        final FloatBuffer positions,colors;
        Batch(AssetManager assets,Node root,String name,int maximumVertices,boolean transparent) {
            positions=BufferUtils.createFloatBuffer(maximumVertices*3);colors=BufferUtils.createFloatBuffer(maximumVertices*4);
            mesh.setBuffer(VertexBuffer.Type.Position,3,positions);mesh.setBuffer(VertexBuffer.Type.Color,4,colors);mesh.setDynamic();
            geometry=new Geometry(name,mesh);
            Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");material.setBoolean("VertexColor",true);
            material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            if(transparent) {
                material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.AlphaAdditive);
                material.getAdditionalRenderState().setDepthWrite(false);geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
            }
            geometry.setMaterial(material);geometry.setCullHint(Spatial.CullHint.Always);root.attachChild(geometry);
        }
        void begin() {positions.clear();colors.clear();}
        void vertex(Vector3f p,ColorRGBA color,float alpha) {positions.put(p.x).put(p.y).put(p.z);colors.put(color.r).put(color.g).put(color.b).put(alpha);}
        void triangle(Vector3f a,Vector3f b,Vector3f c,ColorRGBA color,float alpha) {
            if(positions.remaining()<9)return;vertex(a,color,alpha);vertex(b,color,alpha);vertex(c,color,alpha);
        }
        void quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,ColorRGBA color,float alpha) {
            if(positions.remaining()<18)return;triangle(a,b,c,color,alpha);triangle(a,c,d,color,alpha);
        }
        void end() {
            positions.flip();colors.flip();mesh.getBuffer(VertexBuffer.Type.Position).updateData(positions);
            mesh.getBuffer(VertexBuffer.Type.Color).updateData(colors);mesh.updateCounts();
            geometry.setCullHint(positions.hasRemaining()?Spatial.CullHint.Dynamic:Spatial.CullHint.Always);
            if(positions.hasRemaining())geometry.updateModelBound();
        }
    }
}
