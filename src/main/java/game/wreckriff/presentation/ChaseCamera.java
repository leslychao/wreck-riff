package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.config.CameraRules;
import game.wreckriff.simulation.PhysicsWorld;

public final class ChaseCamera {
    private final Camera camera;
    private final CameraRules rules;
    private final Vector3f position=new Vector3f(),target=new Vector3f();
    private boolean initialized;
    private float fov,shake;
    private double phase;
    public ChaseCamera(Camera camera,CameraRules rules) { this.camera=camera; this.rules=rules; fov=rules.fov(); }
    public void reset() { initialized=false; }
    public void impact(float magnitude) { shake=Math.min(1,shake+magnitude); }
    public void update(PhysicsWorld world,int id,float alpha,float dt,boolean rear,boolean turbo,float intensity) {
        if(camera.getWidth()<=0 || camera.getHeight()<=0) return;
        var pose=world.interpolatedPose(id,alpha);
        Vector3f heading=pose.rotation().mult(Vector3f.UNIT_Z); heading.y=0;
        if(heading.lengthSquared()<0.01f) heading.set(Vector3f.UNIT_Z); else heading.normalizeLocal();
        if(rear) heading.negateLocal();
        Vector3f aim=pose.position().add(heading.mult(rules.lookAhead())).addLocal(0,rules.lookHeight(),0);
        Vector3f pivot=pose.position().add(0,rules.lookHeight(),0);
        Vector3f desired=pose.position().subtract(heading.mult(rules.distance())).addLocal(0,rules.height(),0);
        var collision=world.staticSweep(pivot,desired,rules.sweepRadius());
        if(collision!=null) desired=pivot.clone().interpolateLocal(desired,Math.max(0,collision.fraction()-rules.wallMargin()/Math.max(0.1f,pivot.distance(desired))));
        if(!initialized) { position.set(desired); target.set(aim); initialized=true; }
        else {
            float positionRate=collision!=null?Math.max(40,rules.positionResponse()):rules.positionResponse();
            Vector3f candidate=position.clone().interpolateLocal(desired,1-(float)Math.exp(-positionRate*dt));
            var finalCollision=world.staticSweep(pivot,candidate,rules.sweepRadius());
            if(finalCollision!=null) candidate=pivot.clone().interpolateLocal(candidate,Math.max(0,finalCollision.fraction()-0.03f));
            position.set(candidate); target.interpolateLocal(aim,1-(float)Math.exp(-rules.rotationResponse()*dt));
        }
        fov+=( (turbo?rules.turboFov():rules.fov())-fov)*(1-(float)Math.exp(-7*dt));
        camera.setFrustumPerspective(fov,camera.getWidth()/(float)camera.getHeight(),0.1f,500);
        phase+=dt; shake=Math.max(0,shake-dt*2.5f);
        float amount=shake*intensity;
        Vector3f offset=new Vector3f((float)Math.sin(phase*73),(float)Math.sin(phase*97),0).mult(rules.shakeDistance()*amount);
        camera.setLocation(position.add(offset)); camera.lookAt(target,Vector3f.UNIT_Y);
        if(amount>0) camera.setRotation(camera.getRotation().mult(new Quaternion().fromAngleAxis((float)Math.sin(phase*89)*rules.shakeAngle()*FastMath.DEG_TO_RAD*amount,Vector3f.UNIT_Z)));
    }
}
