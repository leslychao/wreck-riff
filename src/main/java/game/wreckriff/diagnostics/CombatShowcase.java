package game.wreckriff.diagnostics;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.combat.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;

/** Bounded dev-only evidence script. Staged HP gallery is labelled; combat uses the normal tick pipeline. */
public final class CombatShowcase {
    public static final int SECONDS=38;
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
    public Map<Integer,VehicleCommand> commands() {
        long tick=session.tick;
        if(tick<1440&&tick%240==0) {
            // The zero stage is a visual preview; retain a live native target for the following repair/combat.
            float fraction=displayHpFraction(session.vehicle(1));
            if(fraction>0)session.vehicle(1).hp=session.vehicle(1).maximumHp*fraction;
        }
        if(tick==1440||tick==1920||tick==2400)pair();
        Map<Integer,VehicleCommand> result=new HashMap<>();
        if(tick>=1440&&tick<1680)result.put(0,command(true,null,AbilityId.NONE));
        if(tick==1920)result.put(0,command(false,WeaponType.POWER,AbilityId.NONE));
        if(tick==2400)result.put(0,command(false,null,AbilityId.FREEZE));
        if(tick==2550)result.put(1,command(false,null,AbilityId.SHIELD));
        if(tick==2640)result.put(2,command(false,null,AbilityId.FREEZE));
        if(tick>=2640&&tick<2760)result.put(0,command(true,null,AbilityId.NONE));
        if(tick==3000) {pair();result.put(0,command(false,WeaponType.HOMING,AbilityId.NONE));}
        if(tick==3360) {pair();result.put(0,command(false,WeaponType.MINE,AbilityId.NONE));}
        if(tick==3480&&!combat.mines().isEmpty()) {
            Vector3f mine=combat.mines().getFirst().position();
            world.teleport(0,new Vector3f(-52,.85f,-48),new Quaternion());
            world.teleport(1,mine.add(0,.85f,0),new Quaternion());
        }
        if(tick==3840) {
            pair();world.teleport(1,new Vector3f(-42,.85f,-16),new Quaternion().fromAngleAxis(FastMath.PI,Vector3f.UNIT_Y));
            result.put(0,command(false,WeaponType.NAPALM,AbilityId.NONE));
        }
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
        if(seconds<16)return "MACHINE GUN / SHORT TRACERS AND METAL IMPACTS";
        if(seconds<20)return "POWER ROCKET / REAL BODY IMPULSE";
        if(seconds<21.25)return "FREEZE / ICE ON DAMAGED BODY";
        if(seconds<25)return "SHIELD / CLEANSE, ABSORPTION AND EXPIRY";
        if(seconds<28)return "HOMING / BODY RESPONSE";
        if(seconds<32)return "MINE / VERTICAL LIFT";
        return "NAPALM / IMPACT AND BURNING";
    }
    public String frame(Camera camera) {
        Vector3f target=world.position(1);
        boolean close=session.seconds()<12 || (session.seconds()>=20&&session.seconds()<25);
        // Keep the ground reference stable during recoil instead of cancelling movement with the camera.
        if(session.seconds()>=12&&session.seconds()<20||session.seconds()>=25&&session.seconds()<28)
            target=new Vector3f(-42,.55f,-35);
        if(session.seconds()>=29&&session.seconds()<32)target=new Vector3f(-42,.55f,-51);
        Vector3f offset=close?new Vector3f(5.8f,3.0f,-7.2f):new Vector3f(14,8,-10);
        camera.setFrustumPerspective(close?43:55,camera.getWidth()/(float)camera.getHeight(),.1f,500);
        camera.setLocation(target.add(offset));camera.lookAt(target.add(0,.3f,close?0:-4),Vector3f.UNIT_Y);
        String[] names={"hp-100","hp-75","hp-50","hp-25","hp-0","hp-repaired","machine-gun","power-hit","freeze","shield","mine","napalm"};
        double[] times={1,3,5,7,9,11,13,16.25,20.5,22.5,29.15,33.3};
        for(int i=0;i<times.length;i++)if(session.seconds()>=times[i]&&captures.add(names[i]))return names[i];
        return null;
    }
    public Map<String,Object> evidence() { return Map.of("seconds",session.seconds(),"events",events,"observations",observations,
            "gallery","HP is staged for reversible appearance inspection; subsequent attacks use real commands and normal physics."); }
    public boolean complete() { return session.seconds()>=SECONDS; }
    public boolean demonstrated() {
        return events.getOrDefault("FREEZE:freeze",0)>0&&events.getOrDefault("SHIELD:shield",0)>0
                &&events.keySet().stream().anyMatch(key->key.startsWith("SHIELD_HIT:"))&&events.getOrDefault("EXPLOSION:power",0)>0;
    }
}
