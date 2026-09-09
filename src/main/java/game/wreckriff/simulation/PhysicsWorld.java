package game.wreckriff.simulation;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.RotationOrder;
import com.jme3.bullet.joints.New6Dof;
import com.jme3.bullet.joints.motors.MotorParam;
import com.jme3.bullet.collision.*;
import com.jme3.bullet.collision.shapes.*;
import com.jme3.bullet.objects.*;
import com.jme3.math.*;
import com.jme3.system.NativeLibraryLoader;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.arena.ArenaDefinition;
import java.util.*;

/** Single-threaded native physics owner. No scene controls are attached to bodies. */
public final class PhysicsWorld implements WorldQuery, AutoCloseable {
    public record Pose(Vector3f position, Quaternion rotation) {}
    public record Ram(int first,int second,float closingSpeed,Vector3f point,Vector3f normal) {
        public Ram { point=point.clone();normal=normal.clone(); }
        @Override public Vector3f point() { return point.clone(); }
        @Override public Vector3f normal() { return normal.clone(); }
    }
    private final PhysicsSpace space;
    private final VehicleRules rules;
    private final VehicleProfile defaultProfile;
    private final Map<Integer,PhysicsVehicle> vehicles=new LinkedHashMap<>();
    private final Map<Integer,VehicleProfile> profiles=new HashMap<>();
    private final Map<Integer,RoadContext> roadContexts=new HashMap<>();
    private final Map<String,ArenaDefinition.Surface> surfacesByGeometry=new HashMap<>();
    private ArenaDefinition arenaDefinition;
    private final Map<Integer,Integer> wheelContactCounts=new HashMap<>();
    private final Map<Integer,Support[]> wheelSupports=new HashMap<>();
    private final Map<Integer,Set<Integer>> chassisSupports=new HashMap<>();
    private final Map<Integer,Set<Integer>> vehicleContacts=new HashMap<>();
    private final Map<PhysicsCollisionObject,Integer> identities=new IdentityHashMap<>();
    private final Map<Integer,Pose> previous=new HashMap<>();
    private final Map<Integer,Long> teleportGenerations=new HashMap<>();
    private final Map<Integer,Pose[]> previousWheels=new HashMap<>();
    private final Map<Integer,Vector3f> preStepVelocity=new HashMap<>();
    private final Map<Long,Ram> rams=new LinkedHashMap<>();
    private final Map<Float,SphereCollisionShape> sweepShapes=new HashMap<>();
    private final Map<Integer,PhysicsRigidBody> statics=new LinkedHashMap<>();
    private final Map<String,Integer> staticIds=new LinkedHashMap<>();
    private final Map<Integer,String> staticNames=new HashMap<>();
    private final Map<PhysicsCollisionObject,Integer> staticIdentities=new IdentityHashMap<>();
    private int nextStaticId;
    private final Map<Integer,New6Dof> immobilizers=new HashMap<>();
    private final Map<VehicleProfile,PhysicsGhostObject> recoveryProbes=new IdentityHashMap<>();
    private final PhysicsCollisionListener contactListener=this::contact;
    private boolean closed;

    public PhysicsWorld(VehicleRules rules) {
        this.rules=rules;
        defaultProfile=VehicleProfile.rivet(rules);
        NativeLibraryLoader.loadNativeLibrary("bulletjme",true);
        space=new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        space.setGravity(new Vector3f(0,-rules.gravity(),0));
        space.useDeterministicDispatch(true);
        space.addCollisionListener(contactListener);
        space.addOngoingCollisionListener(contactListener);
    }
    public PhysicsSpace space() { return space; }
    /** Bind road metadata once; all support observations still come from native wheel contacts. */
    public void configureArena(ArenaDefinition definition) {
        Objects.requireNonNull(definition);
        if(arenaDefinition!=null&&arenaDefinition!=definition)throw new IllegalStateException("Physics world already belongs to an arena");
        arenaDefinition=definition;surfacesByGeometry.clear();
        for(var surface:definition.surfaces())surfacesByGeometry.put(surface.geometryId(),surface);
        for(int id:vehicles.keySet())refreshRoadContext(id);
    }
    @Override public RoadContext roadContext(int id) { return roadContexts.getOrDefault(id,RoadContext.UNKNOWN); }
    public void beginLaunch(int id,String launchId,String sourceId,String targetId) {
        if(arenaDefinition==null)throw new IllegalStateException("Launch needs arena road metadata");
        var source=arenaDefinition.surfaces().stream().filter(s->s.id().equals(sourceId)).findFirst().orElseThrow();
        var target=arenaDefinition.surfaces().stream().filter(s->s.id().equals(targetId)).findFirst().orElseThrow();
        roadContexts.put(id,new RoadContext(source.id(),source.level(),source.grip(),RoadContext.Motion.LAUNCH,launchId,target.id(),target.level()));
    }
    public void endLaunch(int id) {
        roadContexts.put(id,roadContext(id).airborne());
        if(vehicles.containsKey(id))refreshRoadContext(id);
    }
    public PhysicsRigidBody addStatic(CollisionShape shape,Vector3f position,Quaternion rotation) {
        return addStatic("static-"+nextStaticId,shape,position,rotation);
    }
    /** Surface IDs never move or get reused when another collider is removed. */
    public PhysicsRigidBody addStatic(String objectId,CollisionShape shape,Vector3f position,Quaternion rotation) {
        if(objectId==null||objectId.isBlank()||staticIds.containsKey(objectId))throw new IllegalArgumentException("Duplicate/invalid static object "+objectId);
        PhysicsRigidBody body=new PhysicsRigidBody(shape,0);
        body.setPhysicsLocation(position); body.setPhysicsRotation(rotation);
        body.setFriction(0.8f); body.setRestitution(0); space.addCollisionObject(body);
        int surfaceId=nextStaticId++;
        statics.put(surfaceId,body);staticIds.put(objectId,surfaceId);staticNames.put(surfaceId,objectId);staticIdentities.put(body,surfaceId);
        return body;
    }
    public String staticObjectId(int surfaceId) { return staticNames.get(surfaceId); }
    public boolean removeStatic(String objectId) {
        Integer surfaceId=staticIds.remove(objectId);if(surfaceId==null)return false;
        PhysicsRigidBody body=statics.remove(surfaceId);
        space.removeCollisionObject(body);staticIdentities.remove(body);staticNames.remove(surfaceId);
        chassisSupports.values().forEach(supports->supports.remove(surfaceId));
        chassisSupports.values().removeIf(Set::isEmpty);
        for(var supports:wheelSupports.values())for(int wheel=0;wheel<supports.length;wheel++)
            if(supports[wheel]!=null&&supports[wheel].surfaceId()==surfaceId)supports[wheel]=null;
        return true;
    }
    public PhysicsVehicle addVehicle(int id,Vector3f position,Quaternion orientation) {
        return addVehicle(id,position,orientation,defaultProfile);
    }
    public PhysicsVehicle addVehicle(int id,Vector3f position,Quaternion orientation,VehicleProfile profile) {
        if (vehicles.containsKey(id)) throw new IllegalArgumentException("Duplicate vehicle "+id);
        Objects.requireNonNull(profile);
        CompoundCollisionShape shape=chassisShape(profile,0);
        PhysicsVehicle body=new PhysicsVehicle(shape,profile.mass());
        body.setPhysicsLocation(position); body.setPhysicsRotation(orientation);
        body.setDamping(0.02f,0.18f); body.setFriction(0.6f);
        // Bullet multiplies the two materials: only car/car contacts bounce.
        body.setRestitution((float)Math.sqrt(rules.carPairRestitution()));
        body.setEnableSleep(false);
        body.setCcdMotionThreshold(0.3f); body.setCcdSweptSphereRadius(0.25f);
        body.setSuspensionStiffness(rules.suspensionStiffness());
        body.setSuspensionCompression(rules.suspensionCompression());
        body.setSuspensionDamping(rules.suspensionDamping());
        body.setMaxSuspensionForce(rules.maxSuspensionForce()*profile.massRatio());
        body.setMaxSuspensionTravelCm(profile.suspensionRestLength()*100);
        body.setFrictionSlip(rules.frictionSlip());
        for (int wheel=0;wheel<4;wheel++) {
            body.addWheel(profile.wheelConnection(wheel),new Vector3f(0,-1,0),new Vector3f(-1,0,0),
                    profile.suspensionRestLength(),profile.wheelRadius(),wheel<2);
            body.setRollInfluence(wheel,rules.rollInfluence());
        }
        space.addCollisionObject(body);
        body.getController().setCoordinateSystem(0,1,2);
        vehicles.put(id,body); identities.put(body,id);
        profiles.put(id,profile);
        recoveryProbes.computeIfAbsent(profile,key->new PhysicsGhostObject(chassisShape(key,.12f)));
        teleportGenerations.put(id,0L);
        clearBodyContacts(id);
        body.updateWheels(); refreshWheelContacts(id,body); resetInterpolation(id);
        return body;
    }
    private CompoundCollisionShape chassisShape(VehicleProfile profile,float extra) {
        CompoundCollisionShape shape=new CompoundCollisionShape(profile.hullBoxes().size());
        for(var box:profile.hullBoxes())shape.addChildShape(new BoxCollisionShape(box.extent().addLocal(extra,extra,extra)),box.center());
        return shape;
    }
    @Override public VehicleProfile profile(int id) {
        VehicleProfile profile=profiles.get(id);
        if(profile==null)throw new IllegalArgumentException("Unknown vehicle "+id);
        return profile;
    }
    public PhysicsVehicle vehicle(int id) { return vehicles.get(id); }
    public boolean containsVehicle(int id) { return vehicles.containsKey(id); }
    public long teleportGeneration(int id) {return teleportGenerations.getOrDefault(id,0L);}
    /** Last native step's chassis support; wheel suspension is observed separately. */
    public boolean chassisSupported(int id) {return chassisSupports.containsKey(id);}
    /** Last native step's actual contact with another registered vehicle. */
    public boolean touchingVehicle(int id) {return vehicleContacts.containsKey(id);}
    private void clearBodyContacts(int id) {
        chassisSupports.remove(id);vehicleContacts.remove(id);
        vehicleContacts.values().forEach(contacts->contacts.remove(id));
        vehicleContacts.values().removeIf(Set::isEmpty);
    }
    public void removeVehicle(int id) {
        immobilize(id,false);
        clearBodyContacts(id);
        teleportGenerations.remove(id);
        if(vehicles.containsKey(id))resetInterpolation(id);
        PhysicsVehicle body=vehicles.remove(id);
        if (body!=null) { space.removeCollisionObject(body); identities.remove(body); wheelContactCounts.remove(id);wheelSupports.remove(id); }
        roadContexts.remove(id);
    }
    public void step() {
        rams.clear();
        chassisSupports.clear();vehicleContacts.clear();
        for (var entry:vehicles.entrySet()) {
            int id=entry.getKey();
            previous.put(id,new Pose(position(id),rotation(id)));
            preStepVelocity.put(id,velocity(id));
            previousWheels.put(id,wheelPoses(entry.getValue()));
        }
        space.update(MatchSession.DT,0);
        space.distributeEvents();
        vehicles.values().forEach(this::synchronizeWheels);
        vehicles.forEach(this::refreshWheelContacts);
    }
    private void synchronizeWheels(PhysicsVehicle body) {
        // Libbulletjme 22.0.3 addWheel reads an uninitialized previous location.
        // Its first contact can produce a NaN roll angle, which then corrupts
        // the friction axle on the NEXT native step. Contain that native output
        // before either presentation or physics consumes the wheel transform.
        // An airborne wheel can retain a NaN delta until its next contact, so
        // this must run after every step, not just the first one.
        for (int i=0;i<body.getNumWheels();i++) {
            var wheel=body.getWheel(i);
            if (!Float.isFinite(wheel.getRotationAngle())) wheel.setRotationAngle(0);
        }
        body.updateWheels();
    }
    private Pose[] wheelPoses(PhysicsVehicle body) {
        Pose[] poses=new Pose[4];
        for (int i=0;i<4;i++) poses[i]=new Pose(body.getWheel(i).getWheelWorldLocation(null),body.getWheel(i).getWheelWorldRotation(null));
        return poses;
    }
    public Pose interpolatedPose(int id,float alpha) {
        Pose old=previous.get(id);
        if (!vehicles.containsKey(id)) return old;
        return interpolate(old,new Pose(position(id),rotation(id)),alpha);
    }
    public Pose interpolatedWheel(int id,int wheel,float alpha) {
        return interpolate(previousWheels.get(id)[wheel],wheelPoses(vehicle(id))[wheel],alpha);
    }
    private static Pose interpolate(Pose a,Pose b,float alpha) {
        return new Pose(a.position().clone().interpolateLocal(b.position(),alpha),new Quaternion().slerp(a.rotation(),b.rotation(),alpha));
    }
    public void resetInterpolation(int id) {
        previous.put(id,new Pose(position(id),rotation(id)));
        previousWheels.put(id,wheelPoses(vehicle(id)));
        preStepVelocity.put(id,velocity(id));
    }
    private void contact(PhysicsCollisionEvent event) {
        Integer a=identities.get(event.getObjectA()), b=identities.get(event.getObjectB());
        observeBodyContact(event,a,b);
        if (a==null || b==null || a.equals(b)) return;
        Vector3f relative=preStepVelocity.get(a).subtract(preStepVelocity.get(b));
        // Bullet's normal points from body B to body A.
        float closing=Math.max(0,-relative.dot(event.getNormalWorldOnB()));
        int first=Math.min(a,b), second=Math.max(a,b);
        long key=((long)first<<32)|(second&0xffffffffL);
        Ram old=rams.get(key);
        if (old==null || old.closingSpeed()<closing) {
            Vector3f point=event.getPositionWorldOnA().add(event.getPositionWorldOnB()).multLocal(.5f);
            Vector3f normal=event.getNormalWorldOnB().clone();if(a!=first)normal.negateLocal();
            rams.put(key,new Ram(first,second,closing,point,normal));
        }
    }
    private void observeBodyContact(PhysicsCollisionEvent event,Integer a,Integer b) {
        // Persistent manifolds can outlive actual contact while their points separate.
        // One millimetre covers resting solver slop, not a ray/proximity substitute.
        if(!(event.getDistance1()<=.001f))return;
        if(a!=null&&b!=null&&!a.equals(b)) {
            vehicleContacts.computeIfAbsent(a,key->new HashSet<>()).add(b);
            vehicleContacts.computeIfAbsent(b,key->new HashSet<>()).add(a);
            return;
        }
        // Bullet's normal points from body B to body A: keep only upward support
        // of a chassis by a currently registered static, never a wall or ceiling.
        Integer vehicle=a!=null?a:b;
        if(vehicle==null)return;
        Integer surface=staticIdentities.get(a!=null?event.getObjectB():event.getObjectA());
        float up=event.getNormalWorldOnB().y*(a!=null?1:-1);
        if(surface!=null&&up>=.65f)chassisSupports.computeIfAbsent(vehicle,key->new HashSet<>()).add(surface);
    }
    public List<Ram> rams() { return List.copyOf(rams.values()); }
    @Override public Vector3f position(int id) {
        PhysicsVehicle body=vehicles.get(id);
        return body!=null?body.getPhysicsLocation(null):previous.get(id).position().clone();
    }
    @Override public Quaternion rotation(int id) {
        PhysicsVehicle body=vehicles.get(id);
        return body!=null?body.getPhysicsRotation():previous.get(id).rotation().clone();
    }
    @Override public Vector3f velocity(int id) { return vehicles.containsKey(id)?vehicle(id).getLinearVelocity():new Vector3f(); }
    @Override public float mass(int id) { return vehicles.containsKey(id)?vehicle(id).getMass():profile(id).mass(); }
    /** Keep the same dynamic chassis and momentum, but stop all driver actuators. */
    public void makeWreck(int id) {
        immobilize(id,false);
        stopDriving(id);
    }
    public void stopDriving(int id) {
        PhysicsVehicle body=vehicles.get(id);if(body==null)return;
        body.accelerate(0);body.brake(0);body.steer(0);
    }
    @Override public boolean grounded(int id) { return wheelContacts(id)>0; }
    public int wheelContacts(int id) {
        return wheelContactCounts.getOrDefault(id,0);
    }
    /** Cached suspension contacts on upward static surfaces, excluding walls/cars. */
    public int supportedWheelContacts(int id) {
        Support[] supports=wheelSupports.get(id);if(supports==null)return 0;
        int count=0;for(var support:supports)if(support!=null)count++;
        return count;
    }
    private void refreshWheelContacts(int id,PhysicsVehicle body) {
        // castRay updates Bullet wheel/suspension state. Keep it in the physics
        // owner; HUD, AI, recovery and repeated diagnostics only read this snapshot.
        int count=0,contactMask=0;
        Support[] supports=new Support[body.getNumWheels()];
        for(int i=0;i<body.getNumWheels();i++)if(body.castRay(i)>=0) {
            count++;contactMask|=1<<i;
        }
        // Finish every native suspension cast before making any independent
        // support queries, preserving the original wheel refresh ordering.
        Vector3f position=body.getPhysicsLocation();Quaternion rotation=body.getPhysicsRotation();
        for(int i=0;i<body.getNumWheels();i++)if((contactMask&(1<<i))!=0) {
            var wheel=body.getWheel(i);
            Vector3f point=wheel.getCollisionLocation(),normal=wheel.getCollisionNormal();
            if(point==null||!Float.isFinite(point.x)||!Float.isFinite(point.y)||!Float.isFinite(point.z)
                    ||normal==null||!(normal.y>=.65f))continue;
            // Minie exposes the native point/normal, but not the wheel's ground
            // object. Repeat the full suspension segment only to identify that
            // object. Tiny rays around the point lose precision on large boxes.
            Vector3f from=position.add(rotation.mult(wheel.getLocation()));
            Vector3f to=from.add(rotation.mult(wheel.getDirection(null)).multLocal(wheel.getRestLength()+wheel.getRadius()));
            PhysicsRayTestResult nearest=null;
            for(var hit:space.rayTest(from,to)) {
                if(hit.getCollisionObject()==body)continue;
                if(nearest==null||hit.getHitFraction()<nearest.getHitFraction())nearest=hit;
            }
            if(nearest==null)continue;
            Integer surface=staticIdentities.get(nearest.getCollisionObject());
            if(surface!=null&&nearest.getHitNormalLocal().y>=.65f)supports[i]=new Support(surface,point,normal);
        }
        wheelContactCounts.put(id,count);
        wheelSupports.put(id,supports);
        refreshRoadContext(id);
    }
    private void refreshRoadContext(int id) {
        RoadContext previous=roadContext(id);
        if(previous.motion()==RoadContext.Motion.LAUNCH)return;
        if(arenaDefinition==null||supportedWheelContacts(id)==0) {roadContexts.put(id,previous.airborne());return;}
        Map<String,Integer> votes=new LinkedHashMap<>();
        Map<String,ArenaDefinition.Surface> found=new HashMap<>();
        for(var support:wheelSupports.get(id)) {
            if(support==null)continue;
            var surface=surfacesByGeometry.get(staticObjectId(support.surfaceId()));
            // Older anonymous static registration has no authored ID. Resolve only
            // at the verified native contact, never at a guessed car height.
            if(surface==null)surface=arenaDefinition.surfaceAt(support.point(),0,.03f).orElse(null);
            if(surface==null)continue;
            votes.merge(surface.id(),1,Integer::sum);found.put(surface.id(),surface);
        }
        String selected=votes.keySet().stream().max(Comparator.<String>comparingInt(votes::get).thenComparing(Comparator.reverseOrder())).orElse(null);
        if(selected==null) {roadContexts.put(id,previous.airborne());return;}
        var surface=found.get(selected);
        boolean ramp=arenaDefinition.ramps().stream().anyMatch(r->r.id().equals(surface.geometryId()));
        roadContexts.put(id,new RoadContext(surface.id(),surface.level(),surface.grip(),ramp?RoadContext.Motion.RAMP:RoadContext.Motion.ROAD,
                ramp?surface.geometryId():"","",surface.level()));
    }
    /** Solve the native fixed-step ballistic recurrence with this body's real damping. */
    public Vector3f launchVelocity(int id,Vector3f destination,float seconds) {
        if(!Float.isFinite(seconds)||seconds<=0)throw new IllegalArgumentException("Invalid flight duration");
        PhysicsVehicle body=vehicle(id);int steps=Math.max(1,Math.round(seconds/MatchSession.DT));
        double damping=Math.pow(1-body.getLinearDamping(),MatchSession.DT);
        double velocityCoefficient=1,gravityVelocity=0,positionCoefficient=0,gravityPosition=0;
        for(int tick=0;tick<steps;tick++) {
            velocityCoefficient*=damping;
            gravityVelocity=(gravityVelocity+MatchSession.DT)*damping;
            positionCoefficient+=velocityCoefficient*MatchSession.DT;
            gravityPosition+=gravityVelocity*MatchSession.DT;
        }
        Vector3f displacement=destination.subtract(position(id));
        Vector3f gravity=body.getGravity(null);
        return displacement.subtract(gravity.mult((float)gravityPosition)).divideLocal((float)positionCoefficient);
    }
    @Override public Hit ray(Vector3f from,Vector3f to,int ignoredVehicle) {
        Hit nearest=null;
        for (PhysicsRayTestResult result:space.rayTest(from,to)) {
            int id=identities.getOrDefault(result.getCollisionObject(),-1);
            if (id==ignoredVehicle && id>=0) continue;
            if (nearest==null || result.getHitFraction()<nearest.fraction()) nearest=new Hit(id,
                    from.clone().interpolateLocal(to,result.getHitFraction()),result.getHitNormalLocal().clone(),result.getHitFraction());
        }
        return nearest;
    }
    public Hit staticSweep(Vector3f from,Vector3f to,float radius) { return nativeSweep(from,to,radius,-1,true); }
    @Override public Support support(Vector3f from,float depth) {
        if(!Float.isFinite(depth)||depth<=0)throw new IllegalArgumentException("Invalid support depth");
        PhysicsRayTestResult nearest=null;
        for(var hit:space.rayTest(from,from.add(0,-depth,0))) {
            if(!staticIdentities.containsKey(hit.getCollisionObject()))continue;
            if(nearest==null||hit.getHitFraction()<nearest.getHitFraction())nearest=hit;
        }
        if(nearest==null)return null;
        return new Support(staticIdentities.get(nearest.getCollisionObject()),from.add(0,-depth*nearest.getHitFraction(),0),nearest.getHitNormalLocal());
    }
    @Override public void immobilize(int id,boolean frozen) {
        New6Dof previous=immobilizers.get(id);
        if(!frozen) {
            if(previous!=null) { space.removeJoint(previous); previous.destroy(); immobilizers.remove(id); }
            return;
        }
        PhysicsVehicle body=vehicles.get(id);
        if(previous!=null||body==null)return;
        Vector3f velocity=body.getLinearVelocity(); velocity.x=0;velocity.z=0;
        body.setLinearVelocity(velocity);body.setAngularVelocity(Vector3f.ZERO);
        // World-aligned frame at the current pose: XZ and rotation locked, Y free.
        New6Dof joint=new New6Dof(body,Vector3f.ZERO,body.getPhysicsLocation(),
                body.getPhysicsRotation().inverse().toRotationMatrix(),new Matrix3f(),RotationOrder.XYZ);
        for(int axis=0;axis<6;axis++) {
            joint.set(MotorParam.LowerLimit,axis,axis==1?1:0);
            joint.set(MotorParam.UpperLimit,axis,axis==1?-1:0);
        }
        space.addJoint(joint);immobilizers.put(id,joint);
    }
    public int immobilizerCount() { return immobilizers.size(); }
    private Hit nativeSweep(Vector3f from,Vector3f to,float radius,int onlyVehicle,boolean onlyStatics) {
        SphereCollisionShape shape=sweepShapes.computeIfAbsent(radius,SphereCollisionShape::new);
        Hit nearest=null;
        for (PhysicsSweepTestResult result:space.sweepTest(shape,new Transform(from),new Transform(to))) {
            int id=identities.getOrDefault(result.getCollisionObject(),-1);
            if (onlyStatics ? id>=0 : id!=onlyVehicle) continue;
            if (nearest==null || result.getHitFraction()<nearest.fraction()) {
                Vector3f normal=result.getHitNormalLocal(null).normalizeLocal();
                Vector3f point=from.clone().interpolateLocal(to,result.getHitFraction()).subtractLocal(normal.mult(radius));
                nearest=new Hit(id,point,normal,result.getHitFraction());
            }
        }
        return nearest;
    }
    @Override public Hit sweep(Vector3f from,Vector3f to,float radius,int ignoredVehicle,float stepStart,float stepEnd) {
        if(!Float.isFinite(stepStart)||!Float.isFinite(stepEnd)||stepStart<0||stepEnd>1||stepStart>stepEnd)
            throw new IllegalArgumentException("Invalid sweep step interval");
        Hit nearest=staticSweep(from,to,radius);
        // Sweep in each target's relative frame; this detects a car crossing the projectile
        // path during the same tick, even when neither endpoint overlaps the car.
        for (var entry:vehicles.entrySet()) {
            int id=entry.getKey(); if (id==ignoredVehicle) continue;
            Pose old=previous.get(id); Vector3f now=position(id); Quaternion rot=rotation(id);
            Pose current=new Pose(now,rot),intervalStart=interpolate(old,current,stepStart),intervalEnd=interpolate(old,current,stepEnd);
            if (pointSegmentDistance(intervalEnd.position(),from,to)>profile(id).hullBounds().radius()+radius+intervalEnd.position().distance(intervalStart.position())) continue;
            float angle=2*(float)Math.acos(Math.clamp(Math.abs(intervalStart.rotation().dot(intervalEnd.rotation())),0,1));
            int parts=Math.clamp((int)Math.ceil(angle/0.04f),1,4);
            for (int part=0;part<parts;part++) {
                float t0=part/(float)parts,t1=(part+1f)/parts;
                Pose start=interpolate(old,current,stepStart+(stepEnd-stepStart)*t0),end=interpolate(old,current,stepStart+(stepEnd-stepStart)*t1);
                Vector3f p0=from.clone().interpolateLocal(to,t0),p1=from.clone().interpolateLocal(to,t1);
                Vector3f rel0=now.add(rot.mult(start.rotation().inverse().mult(p0.subtract(start.position()))));
                Vector3f rel1=now.add(rot.mult(end.rotation().inverse().mult(p1.subtract(end.position()))));
                Hit hit=nativeSweep(rel0,rel1,radius,id,false);
                if (hit!=null) {
                    float fraction=t0+hit.fraction()/parts;
                    if (nearest==null || fraction<nearest.fraction()) {
                        Quaternion contactRotation=interpolate(old,current,stepStart+(stepEnd-stepStart)*fraction).rotation();
                        Vector3f normal=contactRotation.mult(rot.inverse().mult(hit.normal())).normalizeLocal();
                        nearest=new Hit(id,from.clone().interpolateLocal(to,fraction).subtractLocal(normal.mult(radius)),normal,fraction);
                    }
                }
            }
        }
        return nearest;
    }
    private static float pointSegmentDistance(Vector3f point,Vector3f from,Vector3f to) {
        Vector3f delta=to.subtract(from);
        float t=delta.lengthSquared()<1e-12?0:Math.clamp(point.subtract(from).dot(delta)/delta.lengthSquared(),0,1);
        return point.distance(from.add(delta.mult(t)));
    }
    @Override public boolean visible(Vector3f from,Vector3f to,int targetVehicle) {
        Hit nearest=ray(from,to,-1);
        return nearest==null || nearest.vehicleId()==targetVehicle || nearest.fraction()>=0.999f;
    }
    @Override public float distanceToHull(int id,Vector3f point) {
        Vector3f local=rotation(id).inverse().mult(point.subtract(position(id)));
        float distance=Float.POSITIVE_INFINITY;
        for(var box:profile(id).hullBoxes())distance=Math.min(distance,boxDistance(local,box.center(),box.extent()));
        return distance;
    }
    private static float boxDistance(Vector3f point,Vector3f center,Vector3f extent) {
        Vector3f delta=point.subtract(center);
        return new Vector3f(Math.max(0,Math.abs(delta.x)-extent.x),Math.max(0,Math.abs(delta.y)-extent.y),Math.max(0,Math.abs(delta.z)-extent.z)).length();
    }
    @Override public void impulse(int id,Vector3f linearImpulse,Vector3f torqueImpulse,float maximumAngularDeltaSpeed) {
        PhysicsVehicle body=vehicles.get(id);if(body==null)return;
        Vector3f torque=torqueImpulse.clone();
        float angularChange=body.getInverseInertiaWorld(null).mult(torque).length();
        if(angularChange>maximumAngularDeltaSpeed)torque.multLocal(maximumAngularDeltaSpeed/angularChange);
        body.applyCentralImpulse(linearImpulse);
        body.applyTorqueImpulse(torque);
    }
    @Override public Vector3f closestHullPoint(int id,Vector3f from) {
        Vector3f local=rotation(id).inverse().mult(from.subtract(position(id)));
        Vector3f closest=null;float distance=Float.POSITIVE_INFINITY;
        for(var box:profile(id).hullBoxes()) {
            Vector3f candidate=boxClosest(local,box.center(),box.extent());float candidateDistance=local.distanceSquared(candidate);
            if(candidateDistance<distance){closest=candidate;distance=candidateDistance;}
        }
        return position(id).add(rotation(id).mult(closest));
    }
    private static Vector3f boxClosest(Vector3f point,Vector3f center,Vector3f extent) {
        return new Vector3f(Math.clamp(point.x,center.x-extent.x,center.x+extent.x),
                Math.clamp(point.y,center.y-extent.y,center.y+extent.y),Math.clamp(point.z,center.z-extent.z,center.z+extent.z));
    }
    public boolean freePose(int id,Vector3f position,Quaternion rotation) {
        return freePose(profile(id),position,rotation,id);
    }
    public boolean freeSpawnPose(VehicleProfile profile,Vector3f position,Quaternion rotation) {
        return freePose(profile,position,rotation,-2);
    }
    private boolean freePose(VehicleProfile profile,Vector3f position,Quaternion rotation,int ignoredVehicle) {
        PhysicsGhostObject probe=recoveryProbes.computeIfAbsent(profile,key->new PhysicsGhostObject(chassisShape(key,.12f)));
        probe.setPhysicsLocation(position); probe.setPhysicsRotation(rotation);
        boolean[] blocked={false};
        space.contactTest(probe,event->{
            PhysicsCollisionObject other=event.getObjectA()==probe?event.getObjectB():event.getObjectA();
            if (identities.getOrDefault(other,-1)!=ignoredVehicle && event.getDistance1() < -0.01f) blocked[0]=true;
        });
        return !blocked[0];
    }
    public void teleport(int id,Vector3f position,Quaternion rotation) {
        immobilize(id,false);
        clearBodyContacts(id);
        roadContexts.put(id,RoadContext.UNKNOWN);
        teleportGenerations.merge(id,1L,Long::sum);
        PhysicsVehicle body=vehicle(id); body.setPhysicsLocation(position); body.setPhysicsRotation(rotation);
        body.setLinearVelocity(Vector3f.ZERO); body.setAngularVelocity(Vector3f.ZERO); body.clearForces();
        body.resetSuspension(); body.updateWheels(); refreshWheelContacts(id,body); resetInterpolation(id);
    }
    public int bodyCount() { return space.countCollisionObjects(); }
    @Override public void close() {
        if (closed) return; closed=true;
        for(int id:List.copyOf(immobilizers.keySet()))immobilize(id,false);
        space.removeCollisionListener(contactListener);
        space.removeOngoingCollisionListener(contactListener);
        for (PhysicsVehicle body:new ArrayList<>(vehicles.values())) space.removeCollisionObject(body);
        for (PhysicsRigidBody body:statics.values()) space.removeCollisionObject(body);
        vehicles.clear(); profiles.clear(); roadContexts.clear();surfacesByGeometry.clear();arenaDefinition=null;recoveryProbes.clear();wheelContactCounts.clear();wheelSupports.clear(); identities.clear(); previous.clear(); teleportGenerations.clear(); previousWheels.clear();
        statics.clear();staticIds.clear();staticNames.clear();staticIdentities.clear();preStepVelocity.clear();rams.clear();sweepShapes.clear();
        chassisSupports.clear();vehicleContacts.clear();
        space.destroy();
    }
}
