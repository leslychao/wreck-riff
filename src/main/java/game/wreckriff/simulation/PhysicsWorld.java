package game.wreckriff.simulation;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.*;
import com.jme3.bullet.collision.shapes.*;
import com.jme3.bullet.objects.*;
import com.jme3.math.*;
import com.jme3.system.NativeLibraryLoader;
import game.wreckriff.config.VehicleRules;
import java.util.*;

/** Single-threaded native physics owner. No scene controls are attached to bodies. */
public final class PhysicsWorld implements WorldQuery, AutoCloseable {
    public record Pose(Vector3f position, Quaternion rotation) {}
    public record Ram(int first,int second,float closingSpeed) {}
    private final PhysicsSpace space;
    private final VehicleRules rules;
    private final Map<Integer,PhysicsVehicle> vehicles=new LinkedHashMap<>();
    private final Map<PhysicsCollisionObject,Integer> identities=new IdentityHashMap<>();
    private final Map<Integer,Pose> previous=new HashMap<>();
    private final Map<Integer,Pose[]> previousWheels=new HashMap<>();
    private final Map<Integer,Vector3f> preStepVelocity=new HashMap<>();
    private final Map<Long,Ram> rams=new LinkedHashMap<>();
    private final Map<Float,SphereCollisionShape> sweepShapes=new HashMap<>();
    private final List<PhysicsRigidBody> statics=new ArrayList<>();
    private final PhysicsGhostObject recoveryProbe;
    private final PhysicsCollisionListener contactListener=this::contact;
    private boolean closed;

    public PhysicsWorld(VehicleRules rules) {
        this.rules=rules;
        NativeLibraryLoader.loadNativeLibrary("bulletjme",true);
        space=new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        space.setGravity(new Vector3f(0,-rules.gravity(),0));
        space.useDeterministicDispatch(true);
        space.addCollisionListener(contactListener);
        space.addOngoingCollisionListener(contactListener);
        recoveryProbe=new PhysicsGhostObject(chassisShape(0.12f));
    }
    public PhysicsSpace space() { return space; }
    public PhysicsRigidBody addStatic(CollisionShape shape,Vector3f position,Quaternion rotation) {
        PhysicsRigidBody body=new PhysicsRigidBody(shape,0);
        body.setPhysicsLocation(position); body.setPhysicsRotation(rotation);
        body.setFriction(0.8f); space.addCollisionObject(body); statics.add(body);
        return body;
    }
    public PhysicsVehicle addVehicle(int id,Vector3f position,Quaternion orientation) {
        if (vehicles.containsKey(id)) throw new IllegalArgumentException("Duplicate vehicle "+id);
        CompoundCollisionShape shape=chassisShape(0);
        PhysicsVehicle body=new PhysicsVehicle(shape,rules.mass());
        body.setPhysicsLocation(position); body.setPhysicsRotation(orientation);
        body.setDamping(0.02f,0.18f); body.setFriction(0.6f); body.setRestitution(0.08f);
        body.setEnableSleep(false);
        body.setCcdMotionThreshold(0.3f); body.setCcdSweptSphereRadius(0.25f);
        body.setSuspensionStiffness(rules.suspensionStiffness());
        body.setSuspensionCompression(rules.suspensionCompression());
        body.setSuspensionDamping(rules.suspensionDamping());
        body.setMaxSuspensionForce(rules.maxSuspensionForce());
        body.setMaxSuspensionTravelCm(rules.suspensionRestLength()*100);
        body.setFrictionSlip(rules.frictionSlip());
        for (int wheel=0;wheel<4;wheel++) {
            float x=(wheel%2==0?-1:1)*(rules.width()/2-0.05f);
            float z=(wheel<2?1:-1)*rules.wheelBase()/2;
            body.addWheel(new Vector3f(x,0.15f,z),new Vector3f(0,-1,0),new Vector3f(-1,0,0),
                    rules.suspensionRestLength(),rules.wheelRadius(),wheel<2);
            body.setRollInfluence(wheel,rules.rollInfluence());
        }
        space.addCollisionObject(body);
        body.getController().setCoordinateSystem(0,1,2);
        vehicles.put(id,body); identities.put(body,id);
        body.updateWheels(); resetInterpolation(id);
        return body;
    }
    private CompoundCollisionShape chassisShape(float extra) {
        CompoundCollisionShape shape=new CompoundCollisionShape(2);
        shape.addChildShape(new BoxCollisionShape(new Vector3f(rules.width()/2+extra,0.3f+extra,rules.length()/2+extra)),new Vector3f(0,0.2f,0));
        shape.addChildShape(new BoxCollisionShape(new Vector3f(0.83f+extra,0.32f+extra,0.85f+extra)),new Vector3f(0,0.65f,-0.25f));
        return shape;
    }
    public PhysicsVehicle vehicle(int id) { return vehicles.get(id); }
    public boolean containsVehicle(int id) { return vehicles.containsKey(id); }
    public void removeVehicle(int id) {
        PhysicsVehicle body=vehicles.remove(id);
        if (body!=null) { space.removeCollisionObject(body); identities.remove(body); }
    }
    public void step() {
        rams.clear();
        for (var entry:vehicles.entrySet()) {
            int id=entry.getKey();
            previous.put(id,new Pose(position(id),rotation(id)));
            preStepVelocity.put(id,velocity(id));
            previousWheels.put(id,wheelPoses(entry.getValue()));
        }
        space.update(MatchSession.DT,0);
        space.distributeEvents();
        vehicles.values().forEach(PhysicsVehicle::updateWheels);
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
        if (a==null || b==null || a.equals(b)) return;
        Vector3f relative=preStepVelocity.get(a).subtract(preStepVelocity.get(b));
        // Bullet's normal points from body B to body A.
        float closing=Math.max(0,-relative.dot(event.getNormalWorldOnB()));
        int first=Math.min(a,b), second=Math.max(a,b);
        long key=((long)first<<32)|(second&0xffffffffL);
        Ram old=rams.get(key);
        if (old==null || old.closingSpeed()<closing) rams.put(key,new Ram(first,second,closing));
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
    @Override public float mass(int id) { return rules.mass(); }
    @Override public boolean grounded(int id) { return wheelContacts(id)>0; }
    public int wheelContacts(int id) {
        PhysicsVehicle body=vehicle(id);
        if (body==null) return 0;
        int count=0;
        for (int i=0;i<4;i++) if (body.castRay(i)>=0) count++;
        return count;
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
    private Hit nativeSweep(Vector3f from,Vector3f to,float radius,int onlyVehicle,boolean onlyStatics) {
        SphereCollisionShape shape=sweepShapes.computeIfAbsent(radius,SphereCollisionShape::new);
        Hit nearest=null;
        for (PhysicsSweepTestResult result:space.sweepTest(shape,new Transform(from),new Transform(to))) {
            int id=identities.getOrDefault(result.getCollisionObject(),-1);
            if (onlyStatics ? id>=0 : id!=onlyVehicle) continue;
            if (nearest==null || result.getHitFraction()<nearest.fraction()) nearest=new Hit(id,
                    from.clone().interpolateLocal(to,result.getHitFraction()),result.getHitNormalLocal(null),result.getHitFraction());
        }
        return nearest;
    }
    @Override public Hit sweep(Vector3f from,Vector3f to,float radius,int ignoredVehicle) {
        Hit nearest=staticSweep(from,to,radius);
        // Sweep in each target's relative frame; this detects a car crossing the projectile
        // path during the same tick, even when neither endpoint overlaps the car.
        for (var entry:vehicles.entrySet()) {
            int id=entry.getKey(); if (id==ignoredVehicle) continue;
            Pose old=previous.get(id); Vector3f now=position(id); Quaternion rot=rotation(id);
            if (pointSegmentDistance(now,from,to)>rules.length()/2+radius+now.distance(old.position())) continue;
            float angle=2*(float)Math.acos(Math.clamp(Math.abs(old.rotation().dot(rot)),0,1));
            int parts=Math.clamp((int)Math.ceil(angle/0.04f),1,4);
            for (int part=0;part<parts;part++) {
                float t0=part/(float)parts,t1=(part+1f)/parts;
                Pose start=interpolate(old,new Pose(now,rot),t0),end=interpolate(old,new Pose(now,rot),t1);
                Vector3f p0=from.clone().interpolateLocal(to,t0),p1=from.clone().interpolateLocal(to,t1);
                Vector3f rel0=now.add(rot.mult(start.rotation().inverse().mult(p0.subtract(start.position()))));
                Vector3f rel1=now.add(rot.mult(end.rotation().inverse().mult(p1.subtract(end.position()))));
                Hit hit=nativeSweep(rel0,rel1,radius,id,false);
                if (hit!=null) {
                    float fraction=t0+hit.fraction()/parts;
                    if (nearest==null || fraction<nearest.fraction()) nearest=new Hit(id,from.clone().interpolateLocal(to,fraction),hit.normal(),fraction);
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
        return Math.min(boxDistance(local,new Vector3f(0,0.2f,0),new Vector3f(rules.width()/2,0.3f,rules.length()/2)),
                boxDistance(local,new Vector3f(0,0.65f,-0.25f),new Vector3f(0.83f,0.32f,0.85f)));
    }
    private static float boxDistance(Vector3f point,Vector3f center,Vector3f extent) {
        Vector3f delta=point.subtract(center);
        return new Vector3f(Math.max(0,Math.abs(delta.x)-extent.x),Math.max(0,Math.abs(delta.y)-extent.y),Math.max(0,Math.abs(delta.z)-extent.z)).length();
    }
    @Override public void impulse(int id,Vector3f impulse) { if (vehicle(id)!=null) vehicle(id).applyCentralImpulse(impulse); }
    public boolean freePose(int id,Vector3f position,Quaternion rotation) {
        PhysicsGhostObject probe=recoveryProbe;
        probe.setPhysicsLocation(position); probe.setPhysicsRotation(rotation);
        boolean[] blocked={false};
        space.contactTest(probe,event->{
            PhysicsCollisionObject other=event.getObjectA()==probe?event.getObjectB():event.getObjectA();
            if (identities.getOrDefault(other,-1)!=id && event.getDistance1() < -0.01f) blocked[0]=true;
        });
        return !blocked[0];
    }
    public void teleport(int id,Vector3f position,Quaternion rotation) {
        PhysicsVehicle body=vehicle(id); body.setPhysicsLocation(position); body.setPhysicsRotation(rotation);
        body.setLinearVelocity(Vector3f.ZERO); body.setAngularVelocity(Vector3f.ZERO); body.clearForces();
        body.resetSuspension(); body.updateWheels(); resetInterpolation(id);
    }
    public int bodyCount() { return space.countCollisionObjects(); }
    @Override public void close() {
        if (closed) return; closed=true;
        space.removeCollisionListener(contactListener);
        space.removeOngoingCollisionListener(contactListener);
        for (PhysicsVehicle body:new ArrayList<>(vehicles.values())) space.removeCollisionObject(body);
        for (PhysicsRigidBody body:statics) space.removeCollisionObject(body);
        vehicles.clear(); identities.clear(); previous.clear(); previousWheels.clear(); statics.clear(); rams.clear(); sweepShapes.clear();
        space.destroy();
    }
}
