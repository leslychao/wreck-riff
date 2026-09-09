package game.wreckriff.vehicle;

import com.jme3.bullet.objects.PhysicsVehicle;
import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.arena.ArenaDefinition.Bounds;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;

/** Arcade assistance acts through native forces; it never replaces ordinary movement. */
public final class VehicleController {
    public record Recovery(boolean recovered, boolean fatal, float cost, String cause) {
        public static final Recovery NONE=new Recovery(false,false,0,"");
    }
    private record SafePose(long tick,Vector3f position,Quaternion rotation) {}
    private record RetreatPoint(long tick,Vector3f point) {}
    private static final int RETREAT_TICKS=10*MatchSession.TICKS_PER_SECOND;
    private final PhysicsWorld world;
    private final VehicleState state;
    private final VehicleRules rules;
    private final VehicleProfile profile;
    private final Bounds bounds;
    private final float recoveryCost;
    private final Deque<SafePose> safe=new ArrayDeque<>();
    private final Deque<RetreatPoint> retreat=new ArrayDeque<>();
    private long recordedTeleportGeneration;
    private float steering, rearGrip;
    private int reverseWait, forwardWait, recoveryHold;
    private boolean reversing, turboActive;
    private Vector3f launchDirection;
    private Vector3f rightingAxis;
    private int rightingHold, rightingStable, rightingUnsupported;
    private long rightingGeneration;
    public VehicleController(PhysicsWorld world,VehicleState state,VehicleRules rules) {
        this(world,state,rules,new Bounds(-80,80,-70,70,-8),rules.recoveryCost());
    }
    public VehicleController(PhysicsWorld world,VehicleState state,VehicleRules rules,Bounds bounds) {
        this(world,state,rules,bounds,rules.recoveryCost());
    }
    public VehicleController(PhysicsWorld world,VehicleState state,VehicleRules rules,Bounds bounds,float recoveryCost) {
        if(!Float.isFinite(recoveryCost)||recoveryCost<0)throw new IllegalArgumentException("Invalid recovery cost");
        this.world=world; this.state=state; this.rules=rules; rearGrip=rules.frictionSlip();
        this.profile=world.profile(state.id);this.bounds=Objects.requireNonNull(bounds);this.recoveryCost=recoveryCost;
        recordedTeleportGeneration=world.teleportGeneration(state.id);
        rightingGeneration=recordedTeleportGeneration;
        safe.add(new SafePose(-120,world.position(state.id),world.rotation(state.id)));
    }
    public boolean reversing() { return reversing; }
    public boolean turboActive() { return turboActive; }
    public boolean launchActive() { return launchDirection!=null; }
    public boolean rightingActive() { return rightingAxis!=null; }
    public void beginLaunch(Vector3f direction) {
        if(direction==null||direction.lengthSquared()<.1f)throw new IllegalArgumentException("Invalid launch direction");
        launchDirection=direction.clone().setY(0).normalizeLocal();
    }
    public void endLaunch() { launchDirection=null; }
    /** Latest still-supported, unoccupied road pose; reading it never moves the live car. */
    public Optional<PhysicsWorld.Pose> checkpointPose() {
        for(var iterator=safe.descendingIterator();iterator.hasNext();) {
            var pose=iterator.next();
            if(!world.freePose(state.id,pose.position(),pose.rotation()))continue;
            boolean supported=true;
            for(int wheel=0;wheel<4;wheel++) {
                Vector3f point=pose.position().add(pose.rotation().mult(profile.wheelConnection(wheel)));
                var support=world.support(point,profile.suspensionRestLength()+profile.wheelRadius()+.4f);
                if(support==null||support.normal().y<.75f||!bounds.contains(support.point())) {supported=false;break;}
            }
            if(supported)return Optional.of(new PhysicsWorld.Pose(pose.position().clone(),pose.rotation().clone()));
        }
        return Optional.empty();
    }
    public Recovery prepare(VehicleCommand command,long tick) {
        if (!state.alive()) return Recovery.NONE;
        if (state.recoveryCooldown>0) state.recoveryCooldown--;
        if (state.protectionTicks>0) state.protectionTicks--;
        Vector3f p=world.position(state.id);
        boolean emergency=p.y<bounds.recoveryY()||p.x<bounds.minX()-2||p.x>bounds.maxX()+2||p.z<bounds.minZ()-2||p.z>bounds.maxZ()+2;
        boolean requested=command.recover() && !launchActive() && !state.controlled() && world.velocity(state.id).length()<2;
        recoveryHold=requested?recoveryHold+1:0;
        if (!emergency && (recoveryHold<seconds(rules.recoveryHold()) || state.recoveryCooldown>0)) return Recovery.NONE;
        if (emergency || requested) {
            recoveryHold=0;
            Map<Long,Vector3f> clearRetreat=null;
            Iterator<SafePose> candidates=safe.descendingIterator();
            while (candidates.hasNext()) {
                SafePose pose=candidates.next();
                if (tick-pose.tick()<120 || !world.freePose(state.id,pose.position(),pose.rotation())) continue;
                WorldQuery.Hit ground=world.ray(pose.position().add(0,0.4f,0),pose.position().add(0,-Math.max(2,profile.roadOffset()+profile.suspensionRestLength()+.5f),0),state.id);
                if (ground==null || ground.vehicleId()>=0 || ground.normal().y<0.75f) continue;
                // Historical recovery must not shortcut through a closed wall.
                WorldQuery.Hit barrier=world.staticSweep(p.add(0,0.5f,0),pose.position().add(0,0.5f,0),0.2f);
                if (barrier!=null && barrier.fraction()<0.98f) {
                    if(!emergency)continue;
                    if(clearRetreat==null)clearRetreat=clearRecordedRetreat(p,tick);
                    Vector3f visited=clearRetreat.get(pose.tick());
                    if(visited==null||visited.distanceSquared(pose.position().add(0,.5f,0))>1e-8f)continue;
                }
                world.teleport(state.id,pose.position(),pose.rotation());
                endLaunch();
                retreat.clear();recordedTeleportGeneration=world.teleportGeneration(state.id);
                state.recoveryCooldown=seconds(rules.recoveryCooldown());
                state.protectionTicks=seconds(rules.recoveryProtection());
                state.recoveries++;
                steering=0; reverseWait=0; forwardWait=0; reversing=false;
                return new Recovery(true,false,recoveryCost,"recovery");
            }
            if (emergency) return new Recovery(false,true,state.hp,"out-of-bounds");
        }
        return Recovery.NONE;
    }
    public void drive(VehicleCommand command) {
        PhysicsVehicle body=world.vehicle(state.id);
        if (body==null || !state.alive()) { resetRighting(); return; }
        if(state.heavyImpactPending) {
            state.impactStabilizerTicks=seconds(rules.impactStabilizerOffSeconds()+rules.impactStabilizerReturnSeconds());
            state.heavyImpactPending=false;
        }
        float dt=MatchSession.DT;
        Vector3f forward=world.forward(state.id), velocity=world.velocity(state.id);
        float longitudinal=velocity.dot(forward);
        float speed=Math.abs(longitudinal);
        int grounded=world.wheelContacts(state.id);
        int supportedWheels=world.supportedWheelContacts(state.id);
        updateRighting(command,supportedWheels);
        boolean righting=rightingActive();
        float throttle=righting?0:command.throttle(), reverse=righting?0:command.brakeReverse();
        float brake=0, power=0;
        int directionDelay=seconds(rules.directionChangeDelaySeconds());
        if (reverse>0) {
            forwardWait=0;
            if (longitudinal>0.5f) { brake=reverse; reverseWait=0; reversing=false; }
            else if (longitudinal< -0.5f || reversing || reverseWait++>=directionDelay) {
                power=-reverse; reversing=true; reverseWait=0;
            }
            else brake=reverse;
        } else {
            reverseWait=0;
            if (throttle>0) {
                if (longitudinal < -0.5f) { brake=throttle; forwardWait=0; reversing=true; }
                else if (longitudinal>0.5f || !reversing || forwardWait++>=directionDelay) {
                    power=throttle; reversing=false; forwardWait=0;
                }
                else brake=throttle;
            } else forwardWait=0;
        }
        turboActive=!righting && command.turbo() && power>0 && grounded>0 && state.turbo>=rules.turboDrain()*dt && state.protectionTicks==0;
        if (turboActive) {
            state.turbo=Math.max(0,state.turbo-rules.turboDrain()*dt); state.turboQuietTicks=0;
        } else {
            state.turboQuietTicks++;
            if (state.turboQuietTicks>=seconds(rules.turboRegenDelay())) state.turbo=Math.min(100,state.turbo+rules.turboRegen()*dt);
        }
        float maxSpeed=rules.maxSpeed()*profile.speedMultiplier();
        float limit=(power<0?rules.reverseSpeed():(turboActive?rules.turboSpeed():rules.maxSpeed()))*profile.speedMultiplier();
        float ratio=Math.clamp(speed/limit,0,1);
        float taper=Math.max(0,1-ratio*ratio*ratio*ratio);
        float massRatio=world.mass(state.id)/rules.mass();
        float force=power*rules.engineForce()*massRatio*profile.accelerationMultiplier()*taper
                *(power<0?rules.reverseForceMultiplier():turboActive?1.55f:1);
        body.accelerate(force/4);
        body.brake(brake*rules.brakeForce()*massRatio);
        float angle=FastMath.interpolateLinear(Math.clamp(speed/maxSpeed,0,1),rules.lowSpeedSteering(),rules.highSpeedSteering())*FastMath.DEG_TO_RAD*profile.turnMultiplier();
        float nativeSteer=righting?0:-command.steer();
        steering += (nativeSteer*angle-steering)*(1-(float)Math.exp(-rules.steeringResponse()*dt));
        body.steer(steering);
        boolean handbrake=!righting && command.handbrake();
        float gripTarget=handbrake?rules.handbrakeFriction():rules.frictionSlip();
        float gripBlend=handbrake?0.3f:Math.min(1,dt/rules.gripReturnSeconds()*3);
        rearGrip += (gripTarget-rearGrip)*gripBlend;
        body.setFrictionSlip(2,rearGrip); body.setFrictionSlip(3,rearGrip);
        if (handbrake) {
            body.brake(2,rules.brakeForce()*0.12f*massRatio); body.brake(3,rules.brakeForce()*0.12f*massRatio);
            if (speed>2 && grounded>=2) {
                float upright=Math.max(0,world.rotation(state.id).mult(Vector3f.UNIT_Y).y);
                float assist=nativeSteer*Math.signum(longitudinal)*rules.handbrakeTorque()*upright*Math.min(1,speed/12)*profile.turnMultiplier();
                body.applyTorque(new Vector3f(0,(assist-body.getAngularVelocity().y*rules.handbrakeYawDamping())*massRatio,0));
            }
        }
        if(launchActive())stabilizeLaunch(body,command.steer(),massRatio);
        else if(righting)rightInPlace(body,supportedWheels);
        else if (!state.controlled() && supportedWheels>=1) {
            Vector3f up=world.rotation(state.id).mult(Vector3f.UNIT_Y);
            // A wheel ray may touch a wall while overturned. Ordinary assistance
            // only catches a recoverable lean; righting a fallen hull needs input.
            if(up.y>FastMath.cos(rules.selfRighting().tiltDegrees()*FastMath.DEG_TO_RAD)) {
                Vector3f correction=up.cross(Vector3f.UNIT_Y).mult(rules.groundStability().torque());
                Vector3f angular=body.getAngularVelocity();
                correction.addLocal(-angular.x*rules.groundStability().damping(),0,-angular.z*rules.groundStability().damping());
                float maximum=rules.groundStability().torque();
                if (correction.length()>maximum) correction.normalizeLocal().multLocal(maximum);
                body.applyTorque(correction.multLocal(stabilizerScale()*massRatio));
            }
        }
        if(state.impactStabilizerTicks>0)state.impactStabilizerTicks--;
    }
    private void resetRighting() {
        rightingAxis=null; rightingHold=rightingStable=rightingUnsupported=0;
    }
    private void updateRighting(VehicleCommand command,int grounded) {
        long generation=world.teleportGeneration(state.id);
        if(generation!=rightingGeneration) { resetRighting(); rightingGeneration=generation; }
        float input=Math.max(Math.abs(command.steer()),Math.max(command.throttle(),command.brakeReverse()));
        if(input<=.2f || launchActive() || state.controlled() || world.touchingVehicle(state.id)
                || state.impactStabilizerTicks>0) { resetRighting(); return; }
        Vector3f up=world.rotation(state.id).mult(Vector3f.UNIT_Y);
        boolean supported=world.chassisSupported(state.id)||(grounded>0&&up.y>.35f);
        if(rightingActive()) {
            // No torque in flight, including a small hop while rolling over a hull
            // edge. A brief interruption preserves the chosen side without flying.
            rightingUnsupported=supported?0:rightingUnsupported+1;
            rightingStable=up.y>.9f&&grounded>=2?rightingStable+1:0;
            if(rightingUnsupported>seconds(.5f)||rightingStable>=seconds(.15f))resetRighting();
            return;
        }
        var tuning=rules.selfRighting();
        if(up.y>=FastMath.cos(tuning.tiltDegrees()*FastMath.DEG_TO_RAD)
                || world.velocity(state.id).length()>=tuning.maxSpeed()) { rightingHold=0; return; }
        rightingHold=Math.min(rightingHold+1,seconds(tuning.holdSeconds()));
        if(rightingHold<seconds(tuning.holdSeconds())||!world.chassisSupported(state.id))return;
        Vector3f axis=up.cross(Vector3f.UNIT_Y);
        if(axis.lengthSquared()<1e-6f) {
            // up x worldUp is zero on an exact roof. Use heading to choose a
            // reproducible roll axis, with steering breaking the left/right tie.
            axis=world.forward(state.id).setY(0).normalizeLocal();
            axis.multLocal(command.steer()<-.2f?1:-1);
        } else axis.normalizeLocal();
        rightingAxis=axis;
        steering=0; reverseWait=forwardWait=0;
    }
    private void rightInPlace(PhysicsVehicle body,int grounded) {
        Vector3f up=world.rotation(state.id).mult(Vector3f.UNIT_Y);
        if(!world.chassisSupported(state.id)&&(grounded==0||up.y<=.35f))return;
        float angle=(float)Math.atan2(up.cross(Vector3f.UNIT_Y).dot(rightingAxis),up.y);
        // Numerical noise around pi must not reverse the selected roof-roll side.
        if(angle< -FastMath.HALF_PI)angle+=FastMath.TWO_PI;
        var tuning=rules.selfRighting();
        float targetSpeed=Math.clamp(angle*4,-tuning.angularSpeed(),tuning.angularSpeed());
        Vector3f angular=body.getAngularVelocity();
        Vector3f acceleration=rightingAxis.mult(targetSpeed).subtractLocal(angular.x,0,angular.z).multLocal(tuning.response());
        if(acceleration.length()>tuning.angularAcceleration())acceleration.normalizeLocal().multLocal(tuning.angularAcceleration());
        // Transform through the native diagonal local inertia. Inverting the world
        // inverse-inertia matrix directly trips Matrix3f's small-determinant cutoff.
        Quaternion rotation=world.rotation(state.id);
        Vector3f local=rotation.inverse().mult(acceleration),inverse=body.getInverseInertiaLocal(null);
        Vector3f torque=new Vector3f(local.x/inverse.x,local.y/inverse.y,local.z/inverse.z);
        body.applyTorque(rotation.mult(torque));
    }
    public float stabilizerScale() {
        int returning=seconds(rules.impactStabilizerReturnSeconds());
        return state.impactStabilizerTicks>returning?0:1-Math.max(0,state.impactStabilizerTicks-1)/(float)returning;
    }
    private void stabilizeLaunch(PhysicsVehicle body,float steer,float massRatio) {
        if(state.controlled()||stabilizerScale()==0)return;
        Quaternion rotation=world.rotation(state.id);
        Vector3f up=rotation.mult(Vector3f.UNIT_Y),forward=rotation.mult(Vector3f.UNIT_Z);
        Vector3f correction=up.cross(Vector3f.UNIT_Y).mult(rules.stabilizingTorque());
        Vector3f flat=forward.clone().setY(0);
        if(flat.lengthSquared()>.01f) {
            flat.normalizeLocal();
            float yawError=(float)Math.atan2(flat.cross(launchDirection).y,Math.clamp(flat.dot(launchDirection),-1,1));
            // Steering adjusts heading only, by at most 8 degrees. It adds no
            // horizontal force capable of leaving the authored landing corridor.
            float desired=yawError+steer*8*FastMath.DEG_TO_RAD;
            correction.y=Math.clamp(desired*rules.stabilizingTorque(),-rules.stabilizingTorque()*.7f,rules.stabilizingTorque()*.7f);
        }
        Vector3f angular=body.getAngularVelocity();
        correction.addLocal(-angular.x*rules.stabilizingTorque()*.22f,-angular.y*rules.stabilizingTorque()*.32f,-angular.z*rules.stabilizingTorque()*.22f);
        float maximum=rules.stabilizingTorque();
        if(correction.length()>maximum)correction.normalizeLocal().multLocal(maximum);
        body.applyTorque(correction.multLocal(massRatio*stabilizerScale()));
    }
    public void recordSafePose(long tick) {
        if(!state.alive()||launchActive())return;
        long generation=world.teleportGeneration(state.id);
        if(generation!=recordedTeleportGeneration||(!retreat.isEmpty()&&retreat.getLast().tick()!=tick-1))retreat.clear();
        recordedTeleportGeneration=generation;
        retreat.addLast(new RetreatPoint(tick,world.position(state.id).add(0,.5f,0)));
        while(retreat.size()>RETREAT_TICKS+1||retreat.getFirst().tick()<tick-RETREAT_TICKS)retreat.removeFirst();
        // A chassis hanging over an edge is not a safe recovery destination.
        if (tick%12!=0 || world.wheelContacts(state.id)<4) return;
        Vector3f position=world.position(state.id);
        float safeMargin=Math.max(4,profile.fullBounds().extent().length());
        if (position.x<bounds.minX()+safeMargin||position.x>bounds.maxX()-safeMargin
                ||position.z<bounds.minZ()+safeMargin||position.z>bounds.maxZ()-safeMargin
                ||world.rotation(state.id).mult(Vector3f.UNIT_Y).y<0.9f) return;
        if (!world.freePose(state.id,position,world.rotation(state.id))) return;
        safe.addLast(new SafePose(tick,position,world.rotation(state.id)));
        while (safe.size()>100) safe.removeFirst();
    }
    private Map<Long,Vector3f> clearRecordedRetreat(Vector3f from,long tick) {
        if(world.teleportGeneration(state.id)!=recordedTeleportGeneration||retreat.isEmpty()
                ||retreat.getLast().tick()!=tick-1)return Map.of();
        Map<Long,Vector3f> reachable=new HashMap<>();Vector3f cursor=from.add(0,.5f,0);
        for(var iterator=retreat.descendingIterator();iterator.hasNext();) {
            RetreatPoint recorded=iterator.next();if(recorded.tick()<tick-RETREAT_TICKS)break;
            // Every waypoint was actually occupied. Recheck the complete reverse path
            // against today's static geometry; a newly closed passage breaks it.
            if(cursor.distanceSquared(recorded.point())>1e-10f&&world.staticSweep(cursor,recorded.point(),.2f)!=null)break;
            reachable.put(recorded.tick(),recorded.point());cursor=recorded.point();
        }
        return reachable;
    }
    private static int seconds(float duration) { return Math.round(duration*120); }
}
