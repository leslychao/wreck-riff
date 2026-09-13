package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.anim.MorphControl;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.simulation.GameEvent;
import java.util.*;

/** Prepared Blender assets and one presentation owner; simulation retains wheel poses and sockets. */
public final class VehicleVisual {
    private VehicleVisual() {}
    public static Node create(AssetManager assets,VehicleProfile profile,int livery) {
        Objects.requireNonNull(profile);if(livery<0)throw new IllegalArgumentException("Nonnegative livery required");
        Node root=new Node(profile.id()+"-"+livery);root.setUserData("profileId",profile.id());root.setUserData("livery",livery);
        root.setUserData("assetOrigin","ORIGINAL_BLENDER_CONTENT");root.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        VehicleDamageVisual.install(assets,root,profile,livery);anchors(root,profile);return root;
    }
    private static void anchors(Node root,VehicleProfile profile) {
        for(int i=0;i<2;i++)anchor(root,"machine-gun-muzzle-"+i,profile.machineGunMuzzle(i));
        anchor(root,"weapon-muzzle",profile.muzzle());anchor(root,"weapon-base",profile.weaponBase());
        for(int side:new int[]{-1,1})anchor(root,"exhaust-"+(side<0?"left":"right"),new Vector3f(side*profile.width()*.34f,-.11f,-profile.length()*.5225f));
        if(profile.id().equals("grinder"))anchor(root,"grinder-intake",profile.grinderIntake());
        if(profile.id().equals("rivet"))anchor(root,"rivet-impulse-emitter",new Vector3f(0,.065f,2.405f));
        if(profile.id().equals("spark")) {
            anchor(root,"spark-nozzle-left",new Vector3f(-profile.width()/2,.15f,-.3f));
            anchor(root,"spark-nozzle-right",new Vector3f(profile.width()/2,.15f,-.3f));
            anchor(root,"spark-smoke-ejector",new Vector3f(0,.2f,-profile.length()/2));
        }
    }
    private static void anchor(Node root,String name,Vector3f position){Node node=new Node(name);node.setLocalTranslation(position);root.attachChild(node);}
    /** Pure presentation: phase comes from the match, vulnerability only from a real gameplay window. */
    public static void updateBossPhase(Node vehicle,int phase,boolean vulnerable) {
        if(phase<0||phase>2)throw new IllegalArgumentException("Boss phase 0..2 required");
        if(vehicle.getChild("boss-panels")==null)return;
        boolean alive=!Integer.valueOf(4).equals(vehicle.getUserData("damageStage"));
        vulnerable&=alive;
        vehicle.getChild("boss-panels").setCullHint(phase==2?Spatial.CullHint.Always:Spatial.CullHint.Inherit);
        vehicle.getChild("service-cover").setCullHint(vulnerable?Spatial.CullHint.Always:Spatial.CullHint.Inherit);
        vehicle.getChild("service-core").setCullHint(vulnerable?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        vehicle.setUserData("bossVisualPhase",phase);vehicle.setUserData("servicePanelOpen",vulnerable);
        clearBossTelegraph(vehicle,true);
    }

    /** Continuous preparation on existing lamps: no flashes, extra draws, or weak-point changes. */
    public static void updateBossTelegraph(Node vehicle,long tick,long beganTick,long untilTick,boolean glow) {
        if(vehicle.getChild("boss-panels")==null)return;
        boolean alive=!Integer.valueOf(4).equals(vehicle.getUserData("damageStage"));
        if(!alive||beganTick<0||untilTick<=beganTick||tick<beganTick||tick>=untilTick) {
            clearBossTelegraph(vehicle,glow);return;
        }
        float progress=(float)((tick-beganTick)/(double)(untilTick-beganTick));
        // The ordinary visible colour carries the warning when bloom and flash intensity are both off.
        bossLights(vehicle,new ColorRGBA(.80f+.20f*progress,.32f+.55f*progress,.045f+.40f*progress,1),glow,true);
    }

    static void clearBossTelegraph(Node vehicle,boolean glow) {
        if(vehicle.getChild("boss-panels")==null)return;
        boolean alive=!Integer.valueOf(4).equals(vehicle.getUserData("damageStage"));
        Integer phase=vehicle.getUserData("bossVisualPhase");
        ColorRGBA color=!alive?new ColorRGBA(.025f,.025f,.025f,1):phase==null||phase==0
                ?new ColorRGBA(.13f,.64f,.80f,1):phase==1?new ColorRGBA(1,.50f,.10f,1):new ColorRGBA(.9f,.12f,.045f,1);
        bossLights(vehicle,color,glow&&alive,false);
    }
    private static void bossLights(Node vehicle,ColorRGBA color,boolean glow,boolean warning) {
        Geometry lights=(Geometry)vehicle.getChild("headlights");
        if(lights==null)return;
        // Keep the damaged mesh intact, but never dim a gameplay warning to the broken lamps' 1.5%.
        // Ordinary per-lamp blackout returns as soon as the preparation ends.
        lights.getMaterial().setBoolean("VertexColor",!warning);
        lights.getMaterial().setColor("Color",color);
        lights.getMaterial().setColor("GlowColor",glow?color.mult(.35f):ColorRGBA.Black);
    }

    public static void updateDamage(Node vehicle,float hpFraction){vehicle.getControl(VehicleDamageVisual.class).damage(hpFraction);}
    public static void updateEffects(Node vehicle,boolean frozen,boolean shielded){vehicle.getControl(VehicleDamageVisual.class).effects(frozen,shielded);}
    public static void acceptPresented(Node vehicle,GameEvent event){vehicle.getControl(VehicleDamageVisual.class).accept(event);}
    public static void configureMaximumHp(Node vehicle,float maximumHp){vehicle.getControl(VehicleDamageVisual.class).configureMaximumHp(maximumHp);}
    public static GameEvent refineContact(Node vehicle,GameEvent event){return vehicle.getControl(VehicleDamageVisual.class).refineContact(event);}
    public static void updatePresentation(Node vehicle,float dt,Camera camera){vehicle.getControl(VehicleDamageVisual.class).advance(dt,camera);}
    public record DetachedPanel(String panelId,Vector3f localPosition,Quaternion localRotation,Vector3f halfExtents,Vector3f localImpulse) {}
    public static List<DetachedPanel> drainDetached(Node vehicle){return vehicle.getControl(VehicleDamageVisual.class).drainDetached();}
    public static void close(Node vehicle){var control=vehicle.getControl(VehicleDamageVisual.class);if(control!=null){control.close();vehicle.removeControl(control);}vehicle.removeControl(MorphControl.class);}
}
