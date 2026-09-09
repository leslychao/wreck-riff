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
    public String padName() { return activePad<0?"No mapped gamepad":glfwGetGamepadName(activePad); }
    public String diagnostics() { return String.format(Locale.ROOT,"%s | steer %.2f RT %.2f LT %.2f\n%s",padName(),axisSteer,axisThrottle,axisBrake,profile.warning()); }
    public void pollGamepad() {
        if(activePad>=0 && (!glfwJoystickPresent(activePad) || !glfwJoystickIsGamepad(activePad))) {
            activePad=-1; clear(); disconnect.run();
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
        };
    }
    @Override public void onMouseButtonEvent(MouseButtonEvent event) {
        if(event.isPressed()) { mouse.add(event.getButtonIndex()); if(!gameplay&&event.getButtonIndex()==0&&!suppressedMouse.contains(0)) uiAction.accept("click"); }
        else { mouse.remove(event.getButtonIndex()); suppressedMouse.remove(event.getButtonIndex()); }
    }
    @Override public void beginInput() {}
    @Override public void endInput() {}
    @Override public void onJoyAxisEvent(JoyAxisEvent event) {}
    @Override public void onJoyButtonEvent(JoyButtonEvent event) {}
    @Override public void onMouseMotionEvent(MouseMotionEvent event) {}
    @Override public void onTouchEvent(TouchEvent event) {}
    @Override public void close() { manager.removeRawInputListener(this); clear(); }
}
