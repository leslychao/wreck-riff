package game.wreckriff.input;

import com.jme3.input.*;
import com.jme3.input.event.*;
import game.wreckriff.config.SettingsStore;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.WeaponType;
import org.lwjgl.glfw.*;
import java.util.*;
import java.util.function.*;
import static org.lwjgl.glfw.GLFW.*;

/** Raw input edges are retained until a physics tick consumes them, including at >120 FPS. */
public final class InputSystem implements RawInputListener,AutoCloseable {
    private final InputManager manager;
    private final Supplier<SettingsStore.Settings> settings;
    private final GamepadProfile profile;
    private final Set<Integer> keys=new HashSet<>();
    private final Set<Integer> mouse=new HashSet<>();
    private final Set<Integer> suppressedKeys=new HashSet<>(),suppressedMouse=new HashSet<>();
    private final GLFWGamepadState pad=GLFWGamepadState.create();
    private final boolean[] priorPad=new boolean[GLFW_GAMEPAD_BUTTON_LAST+1];
    private final boolean[] currentPad=new boolean[GLFW_GAMEPAD_BUTTON_LAST+1];
    private final boolean[] suppressedPad=new boolean[GLFW_GAMEPAD_BUTTON_LAST+1];
    private int activePad=-1;
    private int weaponDelta;
    private WeaponType directWeapon;
    private AbilityId ability=AbilityId.NONE;
    private float axisSteer,axisThrottle,axisBrake;
    private boolean padHandbrake,padTurbo,padMg,padRocket,padRear,padRecover;
    private IntConsumer keyPressed=k->{};
    private Consumer<String> uiAction=a->{};
    private Runnable disconnect=()->{};
    private boolean gameplay;
    private boolean usingGamepad;
    private float observedSteer,observedThrottle,observedBrake;
    public InputSystem(InputManager manager,Supplier<SettingsStore.Settings> settings) {
        this(manager,settings,GamepadProfile.bundled());
    }
    public InputSystem(InputManager manager,Supplier<SettingsStore.Settings> settings,GamepadProfile profile) {
        this.manager=manager; this.settings=settings; this.profile=profile; manager.addRawInputListener(this);
    }
    public void onKey(IntConsumer listener) { keyPressed=listener; }
    public void onUi(Consumer<String> listener) { uiAction=listener; }
    public void onDisconnect(Runnable listener) { disconnect=listener; }
    public void setGameplay(boolean value) { gameplay=value; manager.setCursorVisible(!value); }
    public int activePad() { return activePad; }
    public boolean usingGamepad() {return usingGamepad;}
    /** Prompts use the active physical device and its actual loaded bindings. */
    public String displayBinding(String action) {
        return displayBinding(action,usingGamepad);
    }
    public String displayBinding(String action,boolean gamepad) {
        if(!gamepad)return switch(action) {
            case "Machine gun"->"LMB";case "Selected weapon"->"RMB";case "Pause"->"ESC";
            default->KeyLabels.name(settings.get().keys.get(action));
        };
        return switch(action) {
            case "Throttle"->axisLabel(profile.throttleAxis());case "Brake / reverse"->axisLabel(profile.brakeAxis());
            case "Steer","Steer left","Steer right"->axisLabel(profile.steerAxis());
            case "Handbrake"->buttonLabel(profile.handbrake());case "Turbo"->buttonLabel(profile.turbo());
            case "Machine gun"->buttonLabel(profile.machineGun());case "Selected weapon"->buttonLabel(profile.rocket());
            case "Freeze"->buttonLabel(profile.freeze());case "Shield"->buttonLabel(profile.shield());
            case "Previous weapon"->buttonLabel(profile.previousWeapon());case "Next weapon"->buttonLabel(profile.nextWeapon());
            case "Rear view"->buttonLabel(profile.rearView());case "Recover"->buttonLabel(profile.recover());
            case "Pause"->buttonLabel(profile.pause());default->"UNBOUND";
        };
    }
    private static String axisLabel(int axis) {
        return switch(axis){case 0->"LS X";case 1->"LS Y";case 2->"RS X";case 3->"RS Y";case 4->"LT";case 5->"RT";default->"UNBOUND";};
    }
    private static String buttonLabel(int button) {
        return switch(button){case 0->"A";case 1->"B";case 2->"X";case 3->"Y";case 4->"LB";case 5->"RB";
            case 6->"BACK";case 7->"START";case 8->"GUIDE";case 9->"LS CLICK";case 10->"RS CLICK";
            case 11->"D-PAD UP";case 12->"D-PAD RIGHT";case 13->"D-PAD DOWN";case 14->"D-PAD LEFT";default->"UNBOUND";};
    }
    public String padName() { return activePad<0?"No mapped gamepad":glfwGetGamepadName(activePad); }
    public String diagnostics() { return String.format(Locale.ROOT,"%s | steer %.2f RT %.2f LT %.2f\n%s",padName(),axisSteer,axisThrottle,axisBrake,profile.warning()); }
    public void pollGamepad() {
        if(activePad>=0 && (!glfwJoystickPresent(activePad) || !glfwJoystickIsGamepad(activePad))) {
            activePad=-1;usingGamepad=false;clear();disconnect.run();
        }
        if(activePad<0) for(int id=GLFW_JOYSTICK_1;id<=GLFW_JOYSTICK_LAST;id++) if(glfwJoystickIsGamepad(id)) { activePad=id; break; }
        if(activePad<0 || !glfwGetGamepadState(activePad,pad)) return;
        acceptGamepadState(pad);
    }
    /** Same logical GLFW snapshot path is exercised by mapped-controller regression tests. */
    void acceptGamepadState(GLFWGamepadState snapshot) {
        for(int i=0;i<currentPad.length;i++) currentPad[i]=snapshot.buttons(i)==GLFW_PRESS;
        for(int i=0;i<suppressedPad.length;i++) if(!button(i)) suppressedPad[i]=false;
        axisSteer=deadZone(snapshot.axes(profile.steerAxis()),settings.get().deadZone);
        axisThrottle=profile.trigger(snapshot.axes(profile.throttleAxis()));
        axisBrake=profile.trigger(snapshot.axes(profile.brakeAxis()));
        boolean freshButton=false;
        for(int i=0;i<currentPad.length;i++)freshButton|=currentPad[i]&&!priorPad[i];
        if(freshButton||Math.abs(axisSteer-observedSteer)>.05f||Math.abs(axisThrottle-observedThrottle)>.05f||Math.abs(axisBrake-observedBrake)>.05f) {
            usingGamepad=true;observedSteer=axisSteer;observedThrottle=axisThrottle;observedBrake=axisBrake;
        }
        padHandbrake=availableButton(profile.handbrake()); padTurbo=availableButton(profile.turbo());
        padMg=availableButton(profile.machineGun()); padRocket=availableButton(profile.rocket());
        padRear=availableButton(profile.rearView()); padRecover=availableButton(profile.recover());
        if(gameplay) {
            if(edge(profile.shield())) requestAbility(AbilityId.SHIELD);
            if(edge(profile.freeze())) requestAbility(AbilityId.FREEZE);
        } else if(edge(profile.activate())) uiAction.accept("activate");
        if(edge(profile.previousWeapon())) { if(gameplay) weaponDelta--; else uiAction.accept("left"); }
        if(edge(profile.nextWeapon())) { if(gameplay) weaponDelta++; else uiAction.accept("right"); }
        if(!gameplay&&edge(profile.up())) uiAction.accept("up");
        if(!gameplay&&edge(profile.down())) uiAction.accept("down");
        if(edge(profile.pause())) uiAction.accept("pause");
        if(edge(profile.back()) && !gameplay) uiAction.accept("back");
        for(int i=0;i<priorPad.length;i++) priorPad[i]=button(i);
    }
    private boolean button(int id) { return id>=0&&currentPad[id]; }
    private boolean availableButton(int id) { return id>=0&&button(id)&&!suppressedPad[id]; }
    private boolean edge(int id) { return availableButton(id)&&!priorPad[id]; }
    private boolean held(String action) { Integer key=settings.get().keys.get(action); return key!=null&&key>0&&keys.contains(key)&&!suppressedKeys.contains(key); }
    private boolean matches(String action,int code) {
        Integer binding=settings.get().keys.get(action);
        return binding!=null&&binding>0&&binding==code;
    }
    private void requestAbility(AbilityId next) {
        if(priority(next)>priority(ability)) ability=next;
    }
    private static int priority(AbilityId id) { return switch(id) {case NONE->0;case FREEZE->1;case SHIELD->2;}; }
    public VehicleCommand consume() {
        float steer=held("Steer right")?1:held("Steer left")?-1:axisSteer;
        VehicleCommand result=new VehicleCommand(Math.max(held("Throttle")?1:0,axisThrottle),Math.max(held("Brake / reverse")?1:0,axisBrake),
                steer*settings.get().sensitivity,held("Handbrake")||padHandbrake,held("Turbo")||padTurbo,
                (mouse.contains(0)&&!suppressedMouse.contains(0))||padMg,(mouse.contains(1)&&!suppressedMouse.contains(1))||padRocket,
                directWeapon,weaponDelta,held("Rear view")||padRear,held("Recover")||padRecover,ability);
        directWeapon=null; weaponDelta=0; ability=AbilityId.NONE; return result;
    }
    public void clear() {
        suppressedKeys.addAll(keys); suppressedMouse.addAll(mouse);
        for(int i=0;i<suppressedPad.length;i++) suppressedPad[i]|=button(i);
        keys.clear(); mouse.clear(); directWeapon=null; weaponDelta=0; ability=AbilityId.NONE;
        axisSteer=axisThrottle=axisBrake=0; padMg=padRocket=padHandbrake=padTurbo=padRear=padRecover=false;
    }
    public static float deadZone(float value,float deadZone) {
        if(!Float.isFinite(value)) return 0;
        return Math.abs(value)<=deadZone?0:Math.copySign((Math.abs(value)-deadZone)/(1-deadZone),value);
    }
    @Override public void onKeyEvent(KeyInputEvent event) {
        if(event.isRepeating()) return;
        if(event.isPressed()) {
            usingGamepad=false;
            boolean newPress=!keys.contains(event.getKeyCode());
            keys.add(event.getKeyCode());
            if(!suppressedKeys.contains(event.getKeyCode())) keyPressed.accept(event.getKeyCode());
            if(gameplay&&newPress&&!suppressedKeys.contains(event.getKeyCode())) {
                if(matches("Freeze",event.getKeyCode())) requestAbility(AbilityId.FREEZE);
                if(matches("Shield",event.getKeyCode())) requestAbility(AbilityId.SHIELD);
                for(WeaponType weapon:WeaponType.values()) if(matches(weaponBinding(weapon),event.getKeyCode())) directWeapon=weapon;
                if(matches("Previous weapon",event.getKeyCode())) weaponDelta--;
                if(matches("Next weapon",event.getKeyCode())) weaponDelta++;
            }
        } else { keys.remove(event.getKeyCode()); suppressedKeys.remove(event.getKeyCode()); }
    }
    public static String weaponBinding(WeaponType weapon) {
        return switch(weapon) {
            case HOMING->"Select Homing";case POWER->"Select Power";case MINE->"Select Mine";case NAPALM->"Select Napalm";
            case BALLISTIC->"Select Ballistic";case CANNON->"Select Cannon";
        };
    }
    @Override public void onMouseButtonEvent(MouseButtonEvent event) {
        if(event.isPressed()) {usingGamepad=false;mouse.add(event.getButtonIndex()); if(!gameplay&&event.getButtonIndex()==0&&!suppressedMouse.contains(0)) uiAction.accept("click"); }
        else {mouse.remove(event.getButtonIndex());suppressedMouse.remove(event.getButtonIndex());if(!gameplay&&event.getButtonIndex()==0)uiAction.accept("release");}
    }
    @Override public void beginInput() {}
    @Override public void endInput() {}
    @Override public void onJoyAxisEvent(JoyAxisEvent event) {}
    @Override public void onJoyButtonEvent(JoyButtonEvent event) {}
    @Override public void onMouseMotionEvent(MouseMotionEvent event) {
        if(event.getDX()!=0||event.getDY()!=0||event.getDeltaWheel()!=0) {
            usingGamepad=false;
            if(!gameplay)uiAction.accept(event.getDeltaWheel()>0?"scroll-up":event.getDeltaWheel()<0?"scroll-down":"pointer-move");
        }
    }
    @Override public void onTouchEvent(TouchEvent event) {}
    @Override public void close() { manager.removeRawInputListener(this); clear(); }
}
