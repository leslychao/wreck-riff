package game.wreckriff.diagnostics;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.combat.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;

/** Bounded dev-only evidence script. Staged HP gallery is labelled; combat uses the normal tick pipeline. */
public final class CombatShowcase {
    public static final int SECONDS=40;
    private final MatchSession session;
    private final PhysicsWorld world;
    private final CombatSystem combat;
    private final Set<String> captures=new HashSet<>();
    private final Map<String,Integer> events=new TreeMap<>();
    private final List<Map<String,Object>> observations=new ArrayList<>();
    public CombatShowcase(MatchSession session,PhysicsWorld world,CombatSystem combat) {
        this.session=session;this.world=world;this.combat=combat;
        pair();
        world.teleport(2,new Vector3f(-55,.85f,-35),new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y));
    }
    private void pair() {
        world.teleport(0,new Vector3f(-42,.85f,-48),new Quaternion());
        world.teleport(1,new Vector3f(-42,.85f,-35),new Quaternion().fromAngleAxis(FastMath.PI,Vector3f.UNIT_Y));
    }
    private void sidePair() {
        world.teleport(0,new Vector3f(-53,.85f,-35),new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y));
        world.teleport(1,new Vector3f(-42,.85f,-35),new Quaternion());
    }
    public Map<Integer,VehicleCommand> commands() {
        long tick=session.tick;
        if(tick<1440&&tick%240==0) {
            // The zero stage is a visual preview; retain a live native target for the following repair/combat.
            float fraction=displayHpFraction(session.vehicle(1));
            if(fraction>0)session.vehicle(1).hp=session.vehicle(1).maximumHp*fraction;
        }
        if(tick==1440||tick==1680||tick==1920)pair();
        Map<Integer,VehicleCommand> result=new HashMap<>();
        if(tick>=1440&&tick<1620)result.put(0,command(true,null,AbilityId.NONE));
        if(tick==1680)result.put(0,command(false,WeaponType.POWER,AbilityId.NONE));
        if(tick==1920)result.put(0,command(false,null,AbilityId.FREEZE));
        if(tick==2070)result.put(1,command(false,null,AbilityId.SHIELD));
        if(tick==2160)result.put(2,command(false,null,AbilityId.FREEZE));
        if(tick>=2160&&tick<2280)result.put(0,command(true,null,AbilityId.NONE));
        if(tick==2400) {
            pair();world.teleport(1,new Vector3f(-42,.85f,-16),new Quaternion().fromAngleAxis(FastMath.PI,Vector3f.UNIT_Y));
            session.vehicle(1).hp=session.vehicle(1).maximumHp;
            result.put(0,command(false,WeaponType.NAPALM,AbilityId.NONE));
        }
        if(tick==2700) {
            pair();world.teleport(1,new Vector3f(-42,.85f,-12),new Quaternion().fromAngleAxis(FastMath.PI,Vector3f.UNIT_Y));
            session.vehicle(1).hp=session.vehicle(1).maximumHp;
        }
        if(tick==2760)result.put(0,command(false,WeaponType.BALLISTIC,AbilityId.NONE));
        if(tick==3480) {sidePair();session.vehicle(1).hp=session.vehicle(1).maximumHp;result.put(0,command(false,WeaponType.CANNON,AbilityId.NONE));}
        if(tick==3960) {
            world.teleport(0,new Vector3f(-65,.85f,-48),new Quaternion().fromAngleAxis(-FastMath.HALF_PI,Vector3f.UNIT_Y));
            result.put(0,command(false,WeaponType.CANNON,AbilityId.NONE));
        }
        if(tick==4320) {sidePair();session.vehicle(1).hp=40;result.put(0,command(false,WeaponType.CANNON,AbilityId.NONE));}
        return result;
    }
    private static VehicleCommand command(boolean mg,WeaponType weapon,AbilityId ability) {
        return new VehicleCommand(0,0,0,false,false,mg,weapon!=null,weapon,0,false,false,ability);
    }
    public float displayHpFraction(VehicleState state) {
        if(state.id==1&&session.tick<1440)return new float[]{1,.75f,.5f,.25f,0,1}[(int)(session.tick/240)];
        return state.hp/state.maximumHp;
    }
    public void accept(List<GameEvent> batch) {
        for(GameEvent event:batch)events.merge(event.type()+":"+event.kind(),1,Integer::sum);
        if(session.tick%12==0) {
            var target=session.vehicle(1);Vector3f velocity=world.velocity(1);
            observations.add(Map.of("seconds",session.seconds(),"hp",target.hp,"frozenTicks",target.frozenTicks,
                    "shieldTicks",target.shieldTicks,"position",world.position(1).toString(),"velocity",velocity.toString()));
        }
    }
    public String label() {
        double seconds=session.seconds();
        if(seconds<12) return new String[]{"HP 100% / INTACT","HP 75% / LIGHT DAMAGE","HP 50% / DAMAGED PANELS",
                "HP 25% / CRITICAL","HP 0% / CHARRED BODY","REPAIRED / HP 100%"}[(int)(seconds/2)]+"  [STAGED HP GALLERY]";
        if(seconds<14)return "MACHINE GUN / SHORT TRACERS AND METAL IMPACTS";
        if(seconds<16)return "POWER ROCKET / REAL BODY IMPULSE";
        if(seconds<17.25)return "FREEZE / ICE ON DAMAGED BODY";
        if(seconds<20)return "SHIELD / CLEANSE, ABSORPTION AND EXPIRY";
        if(seconds<23)return "NAPALM / ASSISTED ARC AT 32 METRES";
        if(seconds<29)return "BALLISTIC / FOUR WARNED STRIKES FROM ABOVE";
        if(seconds<33)return "CANNON / SIDE HIT, NATIVE BODY ROTATION";
        if(seconds<36)return "CANNON / ENVIRONMENT RICOCHETS";
        return "CANNON / LETHAL HIT, THREE-SECOND PHYSICAL WRECK";
    }
    public String frame(Camera camera) {
        Vector3f target=world.position(1);
        boolean close=session.seconds()<12 || (session.seconds()>=16&&session.seconds()<20);
        // Keep the ground reference stable during recoil instead of cancelling movement with the camera.
        if(session.seconds()>=12&&session.seconds()<16||session.seconds()>=29)
            target=new Vector3f(-42,.55f,-35);
        if(session.seconds()>=23&&session.seconds()<29)target=new Vector3f(-42,7,-12);
        if(session.seconds()>=33&&session.seconds()<36)target=new Vector3f(-72,.55f,-48);
        Vector3f offset=close?new Vector3f(5.8f,3.0f,-7.2f):session.seconds()>=23&&session.seconds()<29?new Vector3f(21,10,-23):new Vector3f(14,8,-10);
        camera.setFrustumPerspective(close?43:55,camera.getWidth()/(float)camera.getHeight(),.1f,500);
        camera.setLocation(target.add(offset));camera.lookAt(target.add(0,.3f,close?0:-4),Vector3f.UNIT_Y);
        String[] names={"hp-100","hp-75","hp-50","hp-25","hp-0","hp-repaired","machine-gun","power-hit","freeze","shield","napalm","ballistic-warning","ballistic-hit","cannon-hit","cannon-ricochet","cannon-lethal","wreck-removed"};
        double[] times={1,3,5,7,9,11,13,14.25,16.5,18.5,21.3,24,25,29.3,33.25,36.4,39.5};
        for(int i=0;i<times.length;i++)if(session.seconds()>=times[i]&&captures.add(names[i]))return names[i];
        return null;
    }
    public Map<String,Object> evidence() { return Map.of("seconds",session.seconds(),"events",events,"observations",observations,
            "gallery","HP gallery and positions/health between scenarios are staged; all attacks and the final lethal hit use normal commands and physics."); }
    public boolean complete() { return session.seconds()>=SECONDS; }
    public boolean demonstrated() {
        return events.getOrDefault("FREEZE:freeze",0)>0&&events.getOrDefault("SHIELD:shield",0)>0
                &&events.keySet().stream().anyMatch(key->key.startsWith("SHIELD_HIT:"))&&events.getOrDefault("EXPLOSION:power",0)>0
                &&events.getOrDefault("EXPLOSION:ballistic",0)==4&&events.getOrDefault("EXPLOSION:cannon-ricochet",0)>=2
                &&events.getOrDefault("DESTROYED:cannon",0)>0&&!world.containsVehicle(1);
    }
}
