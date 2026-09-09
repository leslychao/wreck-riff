package game.wreckriff.input;

import com.jme3.input.*;
import com.jme3.input.event.*;
import game.wreckriff.config.SettingsStore;
import game.wreckriff.combat.AbilityId;
import org.lwjgl.glfw.GLFWGamepadState;
import static org.lwjgl.glfw.GLFW.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class InputSystemTest {
    @Test void modifierNeedsFreshDirectionAndDoesNotStealDriving() {
        try(InputSystem input=input()) {
            input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_W,true));
            input.onKeyEvent(key(KeyInput.KEY_LCONTROL,true));
            assertEquals(AbilityId.NONE,input.consume().ability(),"Pressing modifier after throttle is not a combo");
            input.onKeyEvent(key(KeyInput.KEY_W,false));input.onKeyEvent(key(KeyInput.KEY_W,true));
            VehicleCommand first=input.consume();
            assertEquals(AbilityId.FREEZE,first.ability());assertEquals(1,first.throttle());
            assertEquals(AbilityId.NONE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_A,true));input.onKeyEvent(key(KeyInput.KEY_D,true));
            assertEquals(AbilityId.SHIELD,input.consume().ability(),"Same-tick defensive action has priority");
        }
    }
    @Test void combosFollowReboundMovementKeysAndCannotSurvivePause() {
        var settings=new SettingsStore.Settings();settings.keys.put("Throttle",KeyInput.KEY_UP);
        try(InputSystem input=input(settings)) {
            input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_LCONTROL,true));
            input.onKeyEvent(key(KeyInput.KEY_UP,true));assertEquals(AbilityId.FREEZE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_UP,false));input.onKeyEvent(key(KeyInput.KEY_UP,true));
            input.clear();input.setGameplay(false);input.setGameplay(true);
            assertEquals(AbilityId.NONE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_D,true));assertEquals(AbilityId.NONE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_LCONTROL,false));input.onKeyEvent(key(KeyInput.KEY_LCONTROL,true));
            input.onKeyEvent(key(KeyInput.KEY_D,false));input.onKeyEvent(key(KeyInput.KEY_D,true));
            assertEquals(AbilityId.SHIELD,input.consume().ability());
        }
    }
    @Test void gamepadModifierConsumesDpadForAbilitiesAndPreservesStick() {
        try(InputSystem input=input()) {
            input.setGameplay(true);
            GLFWGamepadState snapshot=GLFWGamepadState.create();
            snapshot.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER,-1);snapshot.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER,-1);
            snapshot.axes(GLFW_GAMEPAD_AXIS_LEFT_X,.575f);
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_RIGHT_THUMB,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            VehicleCommand command=input.consume();assertEquals(AbilityId.STUN,command.ability());
            assertEquals(0,command.weaponDelta());assertEquals(.5f,command.steer(),.00001f);
            input.acceptGamepadState(snapshot);assertEquals(AbilityId.NONE,input.consume().ability());
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            assertEquals(AbilityId.SHIELD,input.consume().ability());
            input.clear();input.acceptGamepadState(snapshot);assertEquals(AbilityId.NONE,input.consume().ability());
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_RIGHT_THUMB,(byte)GLFW_RELEASE);
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT,(byte)GLFW_RELEASE);input.acceptGamepadState(snapshot);
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            assertEquals(-1,input.consume().weaponDelta());
        }
    }
    @Test void ordinaryDirectionsNeverActivateAbilities() {
        try(InputSystem input=input()) {
            input.setGameplay(true);
            for(int code:new int[]{KeyInput.KEY_W,KeyInput.KEY_A,KeyInput.KEY_D})input.onKeyEvent(key(code,true));
            assertEquals(AbilityId.NONE,input.consume().ability());
        }
    }
    @Test void firstFreshAttacksAfterStartOrResumeNeedNoUnrelatedRelease() {
        try(InputSystem input=input()) {
            input.clear(); input.setGameplay(true);
            input.onMouseButtonEvent(mouse(0,true)); input.onMouseButtonEvent(mouse(1,true));
            input.onKeyEvent(key(KeyInput.KEY_F,true));
            VehicleCommand command=input.consume();
            assertTrue(command.machineGun()); assertTrue(command.selectedWeapon()); assertTrue(command.special());
            assertFalse(input.consume().special(),"A pulse edge is consumed exactly once");
        }
    }
    @Test void attacksHeldAcrossResumeStaySuppressedIndividuallyUntilReleased() {
        try(InputSystem input=input()) {
            input.setGameplay(true); input.onMouseButtonEvent(mouse(0,true));input.onKeyEvent(key(KeyInput.KEY_F,true));
            input.clear(); input.setGameplay(false); input.clear(); input.setGameplay(true);
            // Repeated screen transitions preserve the latch even though clear already emptied held state.
            input.onMouseButtonEvent(mouse(0,true)); input.onKeyEvent(key(KeyInput.KEY_F,true));
            input.onMouseButtonEvent(mouse(1,true));
            VehicleCommand command=input.consume();
            assertFalse(command.machineGun());assertFalse(command.special());assertTrue(command.selectedWeapon());
            input.onKeyEvent(key(KeyInput.KEY_W,false));
            assertFalse(input.consume().machineGun(),"Unrelated key release must not re-arm old mouse hold");
            input.onMouseButtonEvent(mouse(0,false)); input.onKeyEvent(key(KeyInput.KEY_F,false));
            input.onMouseButtonEvent(mouse(0,true)); input.onKeyEvent(key(KeyInput.KEY_F,true));
            VehicleCommand fresh=input.consume();assertTrue(fresh.machineGun());assertTrue(fresh.special());
            assertFalse(input.consume().special());
        }
    }
    @Test void mouseClickThatActivatesResumeCannotBecomeMachineGunFire() {
        try(InputSystem input=input()) {
            input.setGameplay(false);
            input.onUi(action->{if(action.equals("click")){input.clear();input.setGameplay(true);}});
            input.onMouseButtonEvent(mouse(0,true));assertFalse(input.consume().machineGun());
            input.onMouseButtonEvent(mouse(0,false));input.onMouseButtonEvent(mouse(0,true));
            assertTrue(input.consume().machineGun());
        }
    }
    @Test void edgeSurvivesRenderFramesWithoutTicksButDoesNotRepeat() {
        try(InputSystem input=input()) {
            input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_E,true));input.onKeyEvent(key(KeyInput.KEY_E,false));
            for(int frame=0;frame<8;frame++){input.beginInput();input.endInput();}
            assertEquals(1,input.consume().weaponDelta()); assertEquals(0,input.consume().weaponDelta());
        }
    }
    @Test void deadZoneRenormalizesRemainingTravelAndRejectsNan() {
        assertEquals(0,InputSystem.deadZone(.149f,.15f));assertEquals(0,InputSystem.deadZone(-.15f,.15f));
        assertEquals(1,InputSystem.deadZone(1,.15f),.00001f);assertEquals(-1,InputSystem.deadZone(-1,.15f),.00001f);
        assertEquals(.5f,InputSystem.deadZone(.575f,.15f),.00001f);assertEquals(0,InputSystem.deadZone(Float.NaN,.15f));
    }
    private static InputSystem input() {
        return input(new SettingsStore.Settings());
    }
    private static InputSystem input(SettingsStore.Settings settings) {
        return new InputSystem(new InputManager(device(MouseInput.class),device(KeyInput.class),null,null),()->settings);
    }
    private static KeyInputEvent key(int code,boolean down){return new KeyInputEvent(code,'\0',down,false);}
    private static MouseButtonEvent mouse(int button,boolean down){return new MouseButtonEvent(button,down,0,0);}
    private static <T> T device(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)-> {
            if(method.getReturnType()==boolean.class)return true;
            if(method.getReturnType()==int.class)return 0;
            if(method.getReturnType()==long.class)return 0L;
            return null;
        }));
    }
}
