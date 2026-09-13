package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.config.*;
import java.util.*;

/** Initial roster placement checks already installed vehicles, including a relocated checkpoint player. */
public final class MatchSpawns {
    public record Pose(Vector3f position,Quaternion rotation) {}
    private MatchSpawns() {}
    public static List<ArenaDefinition.Spawn> ordered(ArenaDefinition arena,long seed) {
        var spawns=new ArrayList<>(arena.spawns());
        // Campaign starts are authored for first contact and checkpoint migration; opponents still vary by seed.
        Collections.shuffle(arena.districts().isEmpty()?spawns:spawns.subList(1,spawns.size()),new Random(seed));
        return List.copyOf(spawns);
    }
    public static Pose select(PhysicsWorld world,VehicleProfile profile,List<ArenaDefinition.Spawn> ordered,
                              int participant,ProgressStore.Checkpoint checkpoint) {
        if(participant==0&&checkpoint!=null) {
            var stored=checkpoint.safePose();var position=new Vector3f((float)stored.x(),(float)stored.y(),(float)stored.z());
            var rotation=new Quaternion().fromAngleAxis((float)stored.yaw(),Vector3f.UNIT_Y);
            if(!world.freeSpawnPose(profile,position,rotation))throw new IllegalArgumentException("Контрольная точка перекрыта");
            return new Pose(position,rotation);
        }
        for(int i=0;i<ordered.size();i++) {
            var spawn=ordered.get((participant+i)%ordered.size());
            var position=spawn.position().vector().addLocal(0,profile.roadOffset(),0);
            var rotation=new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y);
            if(world.freeSpawnPose(profile,position,rotation))return new Pose(position,rotation);
        }
        throw new IllegalArgumentException("Нет свободного места для машины "+participant);
    }
}
