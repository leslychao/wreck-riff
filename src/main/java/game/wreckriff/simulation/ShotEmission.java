package game.wreckriff.simulation;

import com.jme3.math.Vector3f;

/** Actual launch socket and world-space direction/velocity, never reconstructed from a later vehicle pose. */
public record ShotEmission(String socketId,Vector3f direction,Vector3f sourceVelocity) {
    public ShotEmission {
        if(socketId==null||socketId.isBlank()||!Vector3f.isValidVector(direction)||direction.lengthSquared()<1e-12f
                ||!Vector3f.isValidVector(sourceVelocity))throw new IllegalArgumentException("Valid launch emission required");
        direction=direction.normalize();sourceVelocity=sourceVelocity.clone();
    }
    @Override public Vector3f direction(){return direction.clone();}
    @Override public Vector3f sourceVelocity(){return sourceVelocity.clone();}
}
