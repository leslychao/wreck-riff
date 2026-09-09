package game.wreckriff.ui;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Pure driver-oriented radar. Road level is supplied by the simulation, never inferred from world Y. */
public final class RadarProjection {
    public static final float RANGE_METERS=65;
    public static final int UNKNOWN_LEVEL=Integer.MIN_VALUE;
    private static final int EDGE_SECTORS=48;
    private RadarProjection() { }
    public enum Floor {UNKNOWN,BELOW,SAME,ABOVE}
    public record Heading(float x,float z) {
        public Heading {
            float length=(float)Math.hypot(x,z);
            if(!Float.isFinite(length)||length<.0001f)throw new IllegalArgumentException("Invalid radar heading");
            x/=length;z/=length;
        }
        public static Heading fromRotation(Quaternion rotation,Heading previous) {
            Vector3f forward=rotation.mult(Vector3f.UNIT_Z);
            if(!Float.isFinite(forward.x)||!Float.isFinite(forward.z)||forward.x*forward.x+forward.z*forward.z<.0001f)
                return previous==null?new Heading(0,1):previous;
            return new Heading(forward.x,forward.z);
        }
    }
    public record Observer(float x,float z,Heading heading,int roadLevel) {
        public Observer {if(!Float.isFinite(x)||!Float.isFinite(z)||heading==null)throw new IllegalArgumentException("Invalid radar observer");}
    }
    public record Target(int participantId,float x,float z,int roadLevel,boolean alive,boolean boss,boolean locked) {
        public Target {if(!Float.isFinite(x)||!Float.isFinite(z))throw new IllegalArgumentException("Invalid radar target");}
    }
    /** Normalized circle coordinates: x right, y forward, length at most one. */
    public record Marker(String key,float x,float y,boolean far,Floor floor,boolean boss,boolean locked,int count) { }

    public static Marker project(Observer observer,Target target) {
        float dx=target.x-observer.x,dz=target.z-observer.z;
        float distance=(float)Math.hypot(dx,dz),divisor=Math.max(RANGE_METERS,distance);
        // Rivet points along local +Z; driver's right is local -X, matching ChaseCamera.
        float right=-dx*observer.heading.z+dz*observer.heading.x;
        float forward=dx*observer.heading.x+dz*observer.heading.z;
        Floor floor=target.roadLevel==UNKNOWN_LEVEL||observer.roadLevel==UNKNOWN_LEVEL?Floor.UNKNOWN:
                target.roadLevel>observer.roadLevel?Floor.ABOVE:target.roadLevel<observer.roadLevel?Floor.BELOW:Floor.SAME;
        return new Marker("participant:"+target.participantId,right/divisor,forward/divisor,distance>RANGE_METERS,floor,target.boss,target.locked,1);
    }

    public static List<Marker> project(Observer observer,List<Target> targets) {
        var result=new LinkedHashMap<String,Marker>();
        for(Target target:targets) {
            if(!target.alive)continue;
            Marker marker=project(observer,target);
            if(marker.far && !marker.boss && !marker.locked) {
                double angle=Math.atan2(marker.x,marker.y);
                int sector=Math.floorMod((int)Math.round(angle*EDGE_SECTORS/(2*Math.PI)),EDGE_SECTORS);
                String key="edge:"+marker.floor+":"+sector;
                Marker previous=result.get(key);
                double centered=sector*(2*Math.PI/EDGE_SECTORS);
                result.put(key,new Marker(key,(float)Math.sin(centered),(float)Math.cos(centered),true,marker.floor,false,false,previous==null?1:previous.count+1));
            } else result.put(marker.key,marker);
        }
        return new ArrayList<>(result.values());
    }
}
