package game.wreckriff.vehicle;

import com.jme3.bullet.objects.PhysicsVehicle;
import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;

/** Arcade assistance acts through native forces; it never replaces ordinary movement. */
public final class VehicleController {
    public record Recovery(boolean recovered, boolean fatal, float cost, String cause) {
        public static final Recovery NONE=new Recovery(false,false,0,"");
    }
    private record SafePose(long tick,Vector3f position,Quaternion rotation) {}
    private final PhysicsWorld world;
    private final VehicleState state;
    private final VehicleRules rules;
    private final Deque<SafePose> safe=new ArrayDeque<>();
    private float steering, rearGrip;
    private int reverseWait, forwardWait, recoveryHold;
    private boolean reversing, turboActive;
    public VehicleController(PhysicsWorld world,VehicleState state,VehicleRules rules) {
        this.world=world; this.state=state; this.rules=rules; rearGrip=rules.frictionSlip();
        safe.add(new SafePose(-120,world.position(state.id),world.rotation(state.id)));
    }
    public boolean reversing() { return reversing; }
    public boolean turboActive() { return turboActive; }
    public Recovery prepare(VehicleCommand command,long tick) {
        if (!state.alive()) return Recovery.NONE;
        if (state.recoveryCooldown>0) state.recoveryCooldown--;
        if (state.protectionTicks>0) state.protectionTicks--;
        Vector3f p=world.position(state.id);
        boolean emergency=p.y < -8 || Math.abs(p.x)>82 || Math.abs(p.z)>72;
        boolean requested=command.recover() && !state.controlled() && world.velocity(state.id).length()<2;
        recoveryHold=requested?recoveryHold+1:0;
        if (!emergency && (recoveryHold<seconds(rules.recoveryHold()) || state.recoveryCooldown>0)) return Recovery.NONE;
        if (emergency || requested) {
            recoveryHold=0;
            Iterator<SafePose> candidates=safe.descendingIterator();
            while (candidates.hasNext()) {
                SafePose pose=candidates.next();
                if (tick-pose.tick()<120 || !world.freePose(state.id,pose.position(),pose.rotation())) continue;
                WorldQuery.Hit ground=world.ray(pose.position().add(0,0.4f,0),pose.position().add(0,-2,0),state.id);
                if (ground==null || ground.vehicleId()>=0 || ground.normal().y<0.75f) continue;
                // Historical recovery must not shortcut through a closed wall.
                WorldQuery.Hit barrier=world.staticSweep(p.add(0,0.5f,0),pose.position().add(0,0.5f,0),0.2f);
                if (barrier!=null && barrier.fraction()<0.98f) continue;
                world.teleport(state.id,pose.position(),pose.rotation());
                state.recoveryCooldown=seconds(rules.recoveryCooldown());
                state.protectionTicks=seconds(rules.recoveryProtection());
                state.recoveries++;
                steering=0; reverseWait=0; forwardWait=0; reversing=false;
                return new Recovery(true,false,rules.recoveryCost(),"recovery");
            }
            if (emergency) return new Recovery(false,true,state.hp,"out-of-bounds");
        }
        return Recovery.NONE;
    }
    public void drive(VehicleCommand command) {
        PhysicsVehicle body=world.vehicle(state.id);
        if (body==null || !state.alive()) return;
        float dt=MatchSession.DT;
        Vector3f forward=world.forward(state.id), velocity=world.velocity(state.id);
        float longitudinal=velocity.dot(forward);
        float speed=Math.abs(longitudinal);
        int grounded=world.wheelContacts(state.id);
        float throttle=command.throttle(), reverse=command.brakeReverse();
        float brake=0, power=0;
        if (reverse>0) {
            forwardWait=0;
            if (longitudinal>0.5f) { brake=reverse; reverseWait=0; }
            else if (++reverseWait>=18) { power=-reverse; reversing=true; }
            else brake=reverse;
        } else {
            reverseWait=0;
            if (throttle>0) {
                if (longitudinal < -0.5f) { brake=throttle; forwardWait=0; }
                else if (!reversing || ++forwardWait>=18) { power=throttle; reversing=false; }
                else brake=throttle;
            } else forwardWait=0;
        }
        turboActive=command.turbo() && power>0 && grounded>0 && state.turbo>=rules.turboDrain()*dt && state.protectionTicks==0;
        if (turboActive) {
            state.turbo=Math.max(0,state.turbo-rules.turboDrain()*dt); state.turboQuietTicks=0;
        } else {
            state.turboQuietTicks++;
            if (state.turboQuietTicks>=seconds(rules.turboRegenDelay())) state.turbo=Math.min(100,state.turbo+rules.turboRegen()*dt);
        }
        float limit=power<0?rules.reverseSpeed():(turboActive?rules.turboSpeed():rules.maxSpeed());
        float ratio=Math.clamp(speed/limit,0,1);
        float taper=Math.max(0,1-ratio*ratio*ratio*ratio);
        float force=power*rules.engineForce()*taper*(turboActive?1.55f:1);
        body.accelerate(force/4);
        body.brake(brake*rules.brakeForce());
        float angle=FastMath.interpolateLinear(Math.clamp(speed/rules.maxSpeed(),0,1),rules.lowSpeedSteering(),rules.highSpeedSteering())*FastMath.DEG_TO_RAD;
        float nativeSteer=-command.steer();
        steering += (nativeSteer*angle-steering)*(1-(float)Math.exp(-rules.steeringResponse()*dt));
        body.steer(steering);
        float gripTarget=command.handbrake()?rules.handbrakeFriction():rules.frictionSlip();
        float gripBlend=command.handbrake()?0.3f:Math.min(1,dt/rules.gripReturnSeconds()*3);
        rearGrip += (gripTarget-rearGrip)*gripBlend;
        body.setFrictionSlip(2,rearGrip); body.setFrictionSlip(3,rearGrip);
        if (command.handbrake()) {
            body.brake(2,rules.brakeForce()*0.12f); body.brake(3,rules.brakeForce()*0.12f);
            if (speed>2 && grounded>=2) {
                float upright=Math.max(0,world.rotation(state.id).mult(Vector3f.UNIT_Y).y);
                float assist=nativeSteer*rules.handbrakeTorque()*upright*Math.min(1,speed/12);
                body.applyTorque(new Vector3f(0,assist-body.getAngularVelocity().y*rules.handbrakeYawDamping(),0));
            }
        }
        if (grounded>=2) {
            Vector3f up=world.rotation(state.id).mult(Vector3f.UNIT_Y);
            Vector3f correction=up.cross(Vector3f.UNIT_Y).mult(rules.stabilizingTorque());
            Vector3f angular=body.getAngularVelocity();
            correction.addLocal(-angular.x*rules.stabilizingTorque()*0.12f,0,-angular.z*rules.stabilizingTorque()*0.12f);
            float maximum=rules.stabilizingTorque();
            if (correction.length()>maximum) correction.normalizeLocal().multLocal(maximum);
            body.applyTorque(correction);
        }
    }
    public void recordSafePose(long tick) {
        // A chassis hanging over an edge is not a safe recovery destination.
        if (!state.alive() || tick%12!=0 || world.wheelContacts(state.id)<4) return;
        Vector3f position=world.position(state.id);
        if (Math.abs(position.x)>76 || Math.abs(position.z)>66 || world.rotation(state.id).mult(Vector3f.UNIT_Y).y<0.9f) return;
        if (!world.freePose(state.id,position,world.rotation(state.id))) return;
        safe.addLast(new SafePose(tick,position,world.rotation(state.id)));
        while (safe.size()>100) safe.removeFirst();
    }
    private static int seconds(float duration) { return Math.round(duration*120); }
}
