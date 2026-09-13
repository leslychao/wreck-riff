package game.wreckriff.vehicle;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

/** Shared horizontal facing for presentation of a tumbling chassis. Does not modify its physical pose. */
public final class VehicleOrientation {
    private VehicleOrientation() {}

    /**
     * Near vertical/inverted poses, the previous heading selects a continuous projected branch.
     * Once the chassis up-axis is within 60 degrees of world up, the physical nose takes priority:
     * a real airborne yaw turn must not stay locked to a pre-apex reference. Grounded/fresh poses
     * pass null and always use the nose whenever its horizontal projection is defined.
     */
    public static Vector3f horizontalForward(Quaternion rotation,Vector3f previous) {
        Vector3f forward=rotation.mult(Vector3f.UNIT_Z);forward.y=0;
        if(!Vector3f.isValidVector(forward)||forward.lengthSquared()<.0001f)
            return previous==null?Vector3f.UNIT_Z.clone():previous.clone();
        forward.normalizeLocal();
        boolean upright=rotation.mult(Vector3f.UNIT_Y).y>=.5f;
        if(previous!=null&&!upright&&forward.dot(previous)<0)forward.negateLocal();
        return forward;
    }
}
