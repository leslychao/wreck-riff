package game.wreckriff.simulation;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

/** Geometry boundary. The live implementation always queries real Bullet colliders. */
public interface WorldQuery {
    record Hit(int vehicleId, Vector3f point, Vector3f normal, float fraction) {}
    record Support(int surfaceId,Vector3f point,Vector3f normal) {
        public Support { point=point.clone(); normal=normal.clone(); }
    }
    Vector3f position(int vehicleId);
    Vector3f velocity(int vehicleId);
    Quaternion rotation(int vehicleId);
    boolean grounded(int vehicleId);
    float mass(int vehicleId);
    Hit ray(Vector3f from, Vector3f to, int ignoredVehicle);
    Hit sweep(Vector3f from, Vector3f to, float radius, int ignoredVehicle);
    boolean visible(Vector3f from, Vector3f to, int targetVehicle);
    float distanceToHull(int vehicleId, Vector3f point);
    /** World-space impulse in kilogram metres per second (N*s). */
    void impulse(int vehicleId, Vector3f impulse);
    /** Static-only downward support query; vehicles cannot become mine or fire support. */
    default Support support(Vector3f from,float depth) {
        Hit hit=ray(from,from.add(0,-depth,0),-1);
        return hit!=null && hit.vehicleId()<0?new Support(0,hit.point(),hit.normal()):null;
    }
    /** Native implementations own the temporary constraint and its entire lifecycle. */
    default void immobilize(int vehicleId,boolean frozen) {}
    default Vector3f forward(int id) { return rotation(id).mult(Vector3f.UNIT_Z); }
    default Vector3f weaponBase(int id) { return position(id).add(rotation(id).mult(new Vector3f(0,0.55f,1.6f))); }
    default Vector3f muzzle(int id) { return position(id).add(rotation(id).mult(new Vector3f(0,0.55f,2.5f))); }
    default java.util.List<Vector3f> hullVisibilityPoints(int id) {
        return java.util.List.of(position(id), position(id).add(rotation(id).mult(new Vector3f(0,0.4f,1.6f))),
                position(id).add(rotation(id).mult(new Vector3f(0,0.4f,-1.6f))));
    }
}
