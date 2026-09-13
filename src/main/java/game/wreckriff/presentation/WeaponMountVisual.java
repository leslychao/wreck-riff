package game.wreckriff.presentation;

import com.jme3.scene.*;
import game.wreckriff.simulation.GameEvent;
import java.util.*;

/** Authored moving bolt carriers and barrels; fixed receivers and physics sockets remain fixed. */
final class WeaponMountVisual {
    private static final String[] NAMES={"mount-machine-gun-0","mount-machine-gun-1","mount-weapon"};
    private final Node vehicle;
    private final float[] recoil=new float[3],heat=new float[3];
    private final List<List<Geometry>> bolts=new ArrayList<>(),barrels=new ArrayList<>();
    private boolean bound,rocket;
    WeaponMountVisual(Node vehicle){this.vehicle=vehicle;for(int i=0;i<3;i++){bolts.add(new ArrayList<>());barrels.add(new ArrayList<>());}}
    private void bind(){if(bound)return;for(Spatial child:vehicle.getChildren())if(child instanceof Node lod&&child.getName().startsWith("lod"))for(int i=0;i<3;i++) {
        Spatial bolt=lod.getChild(NAMES[i]+"-bolt"),barrel=lod.getChild(NAMES[i]+"-barrel");if(bolt instanceof Geometry g)bolts.get(i).add(g);if(barrel instanceof Geometry g)barrels.get(i).add(g);
    }bound=true;}
    void accept(GameEvent event) {
        if(event.type()!=GameEvent.Type.SHOT)return;
        String socket=event.emission()==null?"weapon-muzzle":event.emission().socketId();int index=socket.equals("machine-gun-muzzle-0")?0:socket.equals("machine-gun-muzzle-1")?1:2;
        recoil[index]=1;heat[index]=Math.min(1,heat[index]+(index<2?.14f:.28f));if(index==2)rocket=event.kind().contains("rocket")||event.kind().contains("homing")||event.kind().contains("swarm")||event.kind().contains("napalm")||event.kind().contains("freeze");
    }
    void update(float dt) {
        bind();for(int i=0;i<3;i++) {
            if(recoil[i]==0&&heat[i]==0)continue;
            recoil[i]=Math.max(0,recoil[i]-dt/(i<2?.095f:rocket?.10f:.22f));heat[i]=Math.max(0,heat[i]-dt*.22f);
            float travel=recoil[i]*(i<2?.055f:rocket?.012f:.15f);
            for(Geometry bolt:bolts.get(i))bolt.setLocalTranslation(0,0,-travel);
            for(Geometry barrel:barrels.get(i)){barrel.setLocalTranslation(0,0,-travel*(i<2?.12f:rocket?.12f:1));barrel.getMaterial().setFloat("Heat",heat[i]);}
        }
    }
    float recoil(String mount){for(int i=0;i<3;i++)if(NAMES[i].equals(mount))return recoil[i];return 0;}
    void reset(){Arrays.fill(recoil,0);Arrays.fill(heat,0);for(var list:bolts)for(var g:list)g.setLocalTranslation(0,0,0);for(var list:barrels)for(var g:list){g.setLocalTranslation(0,0,0);g.getMaterial().setFloat("Heat",0);}bolts.clear();barrels.clear();}
}
