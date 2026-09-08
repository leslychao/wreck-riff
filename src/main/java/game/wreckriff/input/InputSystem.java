package game.wreckriff.input;

import com.jme3.input.*;
import com.jme3.input.event.*;
import game.wreckriff.config.SettingsStore;
import org.lwjgl.glfw.*;
import java.util.*;
import java.util.function.*;
import static org.lwjgl.glfw.GLFW.*;

/** Raw input edges are retained until a physics tick consumes them, including at >120 FPS. */
public final class InputSystem implements RawInputListener,AutoCloseable {
    private final InputManager manager;
    private final Supplier<SettingsStore.Settings> settings;
    private final Set<Integer> keys=new HashSet<>();
    private final Set<Integer> mouse=new HashSet<>();
    private final Set<Integer> suppressedKeys=new HashSet<>(),suppressedMouse=new HashSet<>();
    private final GLFWGamepadState pad=GLFWGamepadState.create();
    private final boolean[] priorPad=new boolean[GLFW_GAMEPAD_BUTTON_LAST+1];
    private final boolean[] suppressedPad=new boolean[GLFW_GAMEPAD_BUTTON_LAST+1];
    private int activePad=-1;
    private int weaponDelta;
    private boolean special;
    private float axisSteer,axisThrottle,axisBrake;
    private boolean padHandbrake,padTurbo,padMg,padRocket,padRear,padRecover;
    private IntConsumer keyPressed=k->{};
    private Consumer<String> uiAction=a->{};
    private Runnable disconnect=()->{};
    private boolean gameplay;
    public InputSystem(InputManager manager,Supplier<SettingsStore.Settings> settings) {
        this.manager=manager; this.settings=settings; manager.addRawInputListener(this);
    }
    public void onKey(IntConsumer listener) { keyPressed=listener; }
    public void onUi(Consumer<String> listener) { uiAction=listener; }
    public void onDisconnect(Runnable listener) { disconnect=listener; }
    public void setGameplay(boolean value) { gameplay=value; manager.setCursorVisible(!value); }
    public int activePad() { return activePad; }
    public String padName() { return activePad<0?"No mapped gamepad":glfwGetGamepadName(activePad); }
    public String diagnostics() { return String.format(Locale.ROOT,"%s | steer %.2f RT %.2f LT %.2f",padName(),axisSteer,axisThrottle,axisBrake); }
    public void pollGamepad() {
        if(activePad>=0 && (!glfwJoystickPresent(activePad) || !glfwJoystickIsGamepad(activePad))) {
            activePad=-1; clear(); disconnect.run();
        }
        if(activePad<0) for(int id=GLFW_JOYSTICK_1;id<=GLFW_JOYSTICK_LAST;id++) if(glfwJoystickIsGamepad(id)) { activePad=id; break; }
        if(activePad<0 || !glfwGetGamepadState(activePad,pad)) return;
        for(int i=0;i<suppressedPad.length;i++) if(!button(i)) suppressedPad[i]=false;
        axisSteer=deadZone(pad.axes(GLFW_GAMEPAD_AXIS_LEFT_X),settings.get().deadZone);
        axisThrottle=Math.clamp((pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER)+1)/2,0,1);
        axisBrake=Math.clamp((pad.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER)+1)/2,0,1);
        padHandbrake=availableButton(GLFW_GAMEPAD_BUTTON_X); padTurbo=availableButton(GLFW_GAMEPAD_BUTTON_B);
        padMg=availableButton(GLFW_GAMEPAD_BUTTON_LEFT_BUMPER); padRocket=availableButton(GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER);
        padRear=availableButton(GLFW_GAMEPAD_BUTTON_Y); padRecover=availableButton(GLFW_GAMEPAD_BUTTON_BACK);
        if(edge(GLFW_GAMEPAD_BUTTON_A)) { if(gameplay) special=true; else uiAction.accept("activate"); }
        if(edge(GLFW_GAMEPAD_BUTTON_DPAD_LEFT)) { if(gameplay) weaponDelta--; else uiAction.accept("left"); }
        if(edge(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT)) { if(gameplay) weaponDelta++; else uiAction.accept("right"); }
        if(edge(GLFW_GAMEPAD_BUTTON_DPAD_UP)) uiAction.accept("up");
        if(edge(GLFW_GAMEPAD_BUTTON_DPAD_DOWN)) uiAction.accept("down");
        if(edge(GLFW_GAMEPAD_BUTTON_START)) uiAction.accept("pause");
        if(edge(GLFW_GAMEPAD_BUTTON_B) && !gameplay) uiAction.accept("back");
        for(int i=0;i<priorPad.length;i++) priorPad[i]=button(i);
    }
    private boolean button(int id) { return pad.buttons(id)==GLFW_PRESS; }
    private boolean availableButton(int id) { return button(id)&&!suppressedPad[id]; }
    private boolean edge(int id) { return availableButton(id)&&!priorPad[id]; }
    private boolean held(String action) { Integer key=settings.get().keys.get(action); return keys.contains(key)&&!suppressedKeys.contains(key); }
    public VehicleCommand consume() {
        float steer=held("Steer right")?1:held("Steer left")?-1:axisSteer;
        VehicleCommand result=new VehicleCommand(Math.max(held("Throttle")?1:0,axisThrottle),Math.max(held("Brake / reverse")?1:0,axisBrake),
                steer*settings.get().sensitivity,held("Handbrake")||padHandbrake,held("Turbo")||padTurbo,
                (mouse.contains(0)&&!suppressedMouse.contains(0))||padMg,(mouse.contains(1)&&!suppressedMouse.contains(1))||padRocket,
                special,weaponDelta,held("Rear view")||padRear,held("Recover")||padRecover);
        special=false; weaponDelta=0; return result;
    }
    public void clear() {
        suppressedKeys.addAll(keys); suppressedMouse.addAll(mouse);
        for(int i=0;i<suppressedPad.length;i++) suppressedPad[i]|=button(i);
        keys.clear(); mouse.clear(); special=false; weaponDelta=0;
        axisSteer=axisThrottle=axisBrake=0; padMg=padRocket=padHandbrake=padTurbo=padRear=padRecover=false;
    }
    public static float deadZone(float value,float deadZone) {
        if(!Float.isFinite(value)) return 0;
        return Math.abs(value)<=deadZone?0:Math.copySign((Math.abs(value)-deadZone)/(1-deadZone),value);
    }
    @Override public void onKeyEvent(KeyInputEvent event) {
        if(event.isRepeating()) return;
        if(event.isPressed()) {
            keys.add(event.getKeyCode());
            if(!suppressedKeys.contains(event.getKeyCode())) keyPressed.accept(event.getKeyCode());
            if(gameplay&&!suppressedKeys.contains(event.getKeyCode())) {
                if(event.getKeyCode()==settings.get().keys.get("Feedback Pulse")) special=true;
                if(event.getKeyCode()==settings.get().keys.get("Previous weapon")) weaponDelta--;
                if(event.getKeyCode()==settings.get().keys.get("Next weapon")) weaponDelta++;
            }
        } else { keys.remove(event.getKeyCode()); suppressedKeys.remove(event.getKeyCode()); }
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
