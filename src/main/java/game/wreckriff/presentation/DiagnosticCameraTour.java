package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.simulation.WorldQuery;
import java.util.EnumSet;

/** Automated visual evidence only. Reads scene positions and changes the camera, never simulation/input. */
public final class DiagnosticCameraTour {
    private enum Shot { CHASE, GARAGE, RAMP, CAR }
    private final EnumSet<Shot> captured=EnumSet.noneOf(Shot.class);
    private final Vector3f garageEye,garageAim,rampEye,rampAim;
    public DiagnosticCameraTour(ArenaDefinition arena) {
        var garage=arena.boxes().stream().filter(p->p.id().equals("garage-roof")).findFirst().orElseThrow();
        Vector3f centre=garage.center().vector();
        garageEye=centre.add(-garage.size().x()*.5f-12,0,-garage.size().z()*.5f);
        garageAim=centre.add(0,-garage.center().y()*.6f,0);
        var ramp=arena.ramps().stream().filter(r->r.endY()>r.startY()).findFirst().orElseThrow();
        rampEye=new Vector3f(ramp.minX()-12,ramp.startY()+8,ramp.minZ()-15);
        rampAim=new Vector3f((ramp.minX()+ramp.maxX())*.5f,ramp.startY()+(ramp.endY()-ramp.startY())*.6f,ramp.maxZ()-4);
    }
    /** Call after chase updates during automation warmup; each due shot is captured once, including after a frame gap. */
    public String apply(Camera camera,WorldQuery world,double seconds) {
        if(camera.getWidth()<=0||camera.getHeight()<=0)return null;
        Shot shot=null;
        for(Shot candidate:Shot.values())if(!captured.contains(candidate)&&seconds>=scheduledTime(candidate)) {shot=candidate;break;}
        if(shot==null)return null;
        switch(shot) {
            case CHASE -> { }
            case GARAGE -> frame(camera,garageEye,garageAim,64);
            case RAMP -> frame(camera,rampEye,rampAim,58);
            case CAR -> {
                Vector3f centre=world.position(0),aim=centre.add(0,.35f,0),eye=null;
                Quaternion rotation=world.rotation(0);
                for(Vector3f offset:new Vector3f[]{new Vector3f(-6.5f,3.3f,7.4f),new Vector3f(6.5f,3.3f,7.4f),
                        new Vector3f(-6.5f,3.3f,-7.4f),new Vector3f(6.5f,3.3f,-7.4f)}) {
                    Vector3f candidate=centre.add(rotation.mult(offset));
                    WorldQuery.Hit hit=world.ray(aim,candidate,0);
                    if(hit==null){eye=candidate;break;}
                    if(eye==null)eye=aim.clone().interpolateLocal(candidate,Math.max(.1f,hit.fraction()-.08f));
                }
                frame(camera,eye,aim,48);
            }
        }
        captured.add(shot);return shot.name().toLowerCase(java.util.Locale.ROOT);
    }
    private static double scheduledTime(Shot shot) {return switch(shot){case CHASE->8;case GARAGE->12;case RAMP->16;case CAR->20;};}
    private static void frame(Camera camera,Vector3f eye,Vector3f aim,float fov) {
        camera.setFrustumPerspective(fov,camera.getWidth()/(float)camera.getHeight(),.1f,500);
        camera.setLocation(eye);camera.lookAt(aim,Vector3f.UNIT_Y);
    }
}
