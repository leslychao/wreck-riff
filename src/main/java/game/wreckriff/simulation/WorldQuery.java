package game.wreckriff.simulation;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.VehicleProfile;

/** Geometry boundary. The live implementation always queries real Bullet colliders. */
public interface WorldQuery {
    record Hit(int vehicleId, Vector3f point, Vector3f normal, float fraction,String objectId) {
        public Hit(int vehicleId,Vector3f point,Vector3f normal,float fraction) {this(vehicleId,point,normal,fraction,null);}
    }
    record Support(int surfaceId,Vector3f point,Vector3f normal) {
        public Support { point=point.clone(); normal=normal.clone(); }
    }
    Vector3f position(int vehicleId);
    Vector3f velocity(int vehicleId);
    Quaternion rotation(int vehicleId);
    boolean grounded(int vehicleId);
    float mass(int vehicleId);
    default VehicleProfile profile(int vehicleId) { return VehicleProfile.rivet(); }
    default RoadContext roadContext(int vehicleId) { return RoadContext.UNKNOWN; }
    Hit ray(Vector3f from, Vector3f to, int ignoredVehicle);
    default Hit sweep(Vector3f from,Vector3f to,float radius,int ignoredVehicle) {
        return sweep(from,to,radius,ignoredVehicle,0,1);
    }
    /** Fractions of the native step covered by this projectile segment. */
    Hit sweep(Vector3f from,Vector3f to,float radius,int ignoredVehicle,float stepStart,float stepEnd);
    Hit staticSweep(Vector3f from,Vector3f to,float radius);
    boolean visible(Vector3f from, Vector3f to, int targetVehicle);
    float distanceToHull(int vehicleId, Vector3f point);
    /** Aggregated world-space linear and torque impulse; only the native owner applies angular limits. */
    void impulse(int vehicleId,Vector3f linearImpulse,Vector3f torqueImpulse,float maximumAngularDeltaSpeed);
    Vector3f closestHullPoint(int vehicleId,Vector3f from);
    /** Static-only downward support query; vehicles cannot become mine or fire support. */
    default Support support(Vector3f from,float depth) {
        Hit hit=ray(from,from.add(0,-depth,0),-1);
        return hit!=null && hit.vehicleId()<0?new Support(0,hit.point(),hit.normal()):null;
    }
    /** Native implementations own the temporary constraint and its entire lifecycle. */
    default void immobilize(int vehicleId,boolean frozen) {}
    /** Actual native chassis contact; proximity alone cannot start a capture. */
    default boolean touchingVehicles(int first,int second) { return false; }
    default boolean beginGrab(int owner,int target) { return false; }
    default void endGrab(int owner) {}
    default boolean grabIntact(int owner,int target) { return false; }
    /** Native movement only; direction is captured once and never follows later steering. */
    default boolean beginDash(int id,Vector3f direction) { return false; }
    default void endDash(int id) {}
    default boolean dashActive(int id) { return false; }
    default Vector3f forward(int id) { return rotation(id).mult(Vector3f.UNIT_Z); }
    default Vector3f weaponBase(int id) { return position(id).add(rotation(id).mult(profile(id).weaponBase())); }
    default Vector3f muzzle(int id) { return position(id).add(rotation(id).mult(profile(id).muzzle())); }
    default Vector3f machineGunMuzzle(int id,int barrel) {
        return position(id).add(rotation(id).mult(profile(id).machineGunMuzzle(barrel)));
    }
    default java.util.List<Vector3f> hullVisibilityPoints(int id) {
        Vector3f position=position(id);Quaternion rotation=rotation(id);
        return profile(id).hullVisibilityPoints().stream().map(point->position.add(rotation.mult(point))).toList();
    }
}
