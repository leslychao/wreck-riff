package game.wreckriff.simulation;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

/** Immutable target-local contact, captured using the target pose at collision time. */
public record VehicleContact(Vector3f localPoint,Vector3f localNormal) {
    public VehicleContact {localPoint=copy(localPoint);localNormal=copy(localNormal);}
    @Override public Vector3f localPoint(){return localPoint.clone();}
    @Override public Vector3f localNormal(){return localNormal.clone();}
    public static VehicleContact atPose(Vector3f point,Vector3f normal,Vector3f position,Quaternion rotation) {
        Quaternion inverse=rotation.inverse();
        return new VehicleContact(inverse.mult(point.subtract(position)),inverse.mult(normal));
    }
    private static Vector3f copy(Vector3f value) {
        if(!Vector3f.isValidVector(value))throw new IllegalArgumentException("Finite contact vector required");
        return value.clone();
    }
}
