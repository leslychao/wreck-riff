package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import game.wreckriff.simulation.GameEvent;
import java.util.*;

/** Recoil of the authored assemblies never moves authoritative muzzle/socket nodes. */
final class WeaponMountVisual {
    private final Node vehicle;
    private final Map<String,Float> recoil=new HashMap<>(),heat=new HashMap<>();
    WeaponMountVisual(Node vehicle){this.vehicle=vehicle;}
    void accept(GameEvent event) {
        if(event.type()!=GameEvent.Type.SHOT)return;
        String socket=event.emission()==null?"weapon-muzzle":event.emission().socketId();
        String part=socket.startsWith("machine-gun-muzzle-")?"mount-machine-gun-"+socket.substring(socket.length()-1):"mount-weapon";
        recoil.put(part,1f);heat.merge(part,.16f,(a,b)->Math.min(1,a+b));
    }
    void update(float dt) {
        for(String name:List.of("mount-machine-gun-0","mount-machine-gun-1","mount-weapon")) {
            float amount=Math.max(0,recoil.getOrDefault(name,0f)-dt/(name.equals("mount-weapon")?.22f:.095f));recoil.put(name,amount);
            heat.computeIfPresent(name,(key,value)->Math.max(0,value-dt*.45f));
            for(Spatial child:vehicle.getChildren())if(child instanceof Node lod&&child.getName().startsWith("lod")) {
                Spatial mount=lod.getChild(name);if(mount!=null)mount.setLocalTranslation(0,0,-amount*(name.equals("mount-weapon")?.11f:.045f));
            }
        }
    }
    float recoil(String mount){return recoil.getOrDefault(mount,0f);}
    void reset(){recoil.clear();heat.clear();update(0);}
}
