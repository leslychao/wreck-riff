package game.wreckriff.input;

import com.jme3.input.*;
import com.jme3.input.event.*;
import game.wreckriff.config.SettingsStore;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.WeaponType;
import org.lwjgl.glfw.GLFWGamepadState;
import static org.lwjgl.glfw.GLFW.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class InputSystemTest {
    @Test void playerSpecialNeedsAFreshOwnKeyOrPadButtonAndClearsAcrossPause() {
        try(InputSystem input=input()) {
            input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_C,true));
            assertEquals(AbilityId.SPECIAL,input.consume().ability());assertEquals(AbilityId.NONE,input.consume().ability());
            input.clear();input.setGameplay(false);input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_C,true));
            assertEquals(AbilityId.NONE,input.consume().ability());input.onKeyEvent(key(KeyInput.KEY_C,false));
            var snapshot=GLFWGamepadState.create();snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN,(byte)GLFW_PRESS);
            input.acceptGamepadState(snapshot);assertEquals(AbilityId.SPECIAL,input.consume().ability());
            assertEquals("D-PAD DOWN",input.displayBinding("Special"));input.acceptGamepadState(snapshot);
            assertEquals(AbilityId.NONE,input.consume().ability());
        }
    }
    @Test void hudPromptsFollowActualInputAndIdleConnectedPadCannotStealThemBack() {
        try(InputSystem input=input()) {
            var snapshot=GLFWGamepadState.create();
            snapshot.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER,-1);snapshot.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER,-1);
            input.acceptGamepadState(snapshot);assertFalse(input.usingGamepad());
            snapshot.axes(GLFW_GAMEPAD_AXIS_LEFT_X,.6f);input.acceptGamepadState(snapshot);
            assertTrue(input.usingGamepad());assertEquals("RB",input.displayBinding("Selected weapon"));
            input.onKeyEvent(key(KeyInput.KEY_W,true));assertFalse(input.usingGamepad());
            input.acceptGamepadState(snapshot);assertFalse(input.usingGamepad(),"An unchanged held stick is not new user activity");
            snapshot.axes(GLFW_GAMEPAD_AXIS_LEFT_X,.61f);input.acceptGamepadState(snapshot);
            assertFalse(input.usingGamepad(),"Small axis noise cannot replace keyboard prompts");
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_A,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            assertTrue(input.usingGamepad());assertEquals("A",input.displayBinding("Shield"));
            input.onMouseMotionEvent(new MouseMotionEvent(100,100,0,0,0,0));assertTrue(input.usingGamepad());
            input.onMouseMotionEvent(new MouseMotionEvent(101,100,1,0,0,0));assertFalse(input.usingGamepad());
            assertEquals("RMB",input.displayBinding("Selected weapon"));
        }
    }
    @Test void promptLabelsRespectKeyboardRemappingAndTheLoadedGamepadProfile() {
        var settings=new SettingsStore.Settings();settings.keys.put("Freeze",KeyInput.KEY_C);
        var defaults=GamepadProfile.bundled();
        var custom=new GamepadProfile(4,2,4,5,-1,1,defaults.handbrake(),defaults.turbo(),defaults.rocket(),defaults.machineGun(),
                defaults.shield(),defaults.previousWeapon(),defaults.nextWeapon(),defaults.rearView(),defaults.recover(),defaults.pause(),
                defaults.up(),defaults.down(),defaults.back(),defaults.freeze(),defaults.activate(),defaults.special());
        try(InputSystem input=new InputSystem(new InputManager(device(MouseInput.class),device(KeyInput.class),null,null),()->settings,custom)) {
            assertEquals("C",input.displayBinding("Freeze"));assertEquals("LMB",input.displayBinding("Machine gun"));
            var snapshot=GLFWGamepadState.create();snapshot.buttons(GLFW_GAMEPAD_BUTTON_A,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            assertEquals("LB",input.displayBinding("Selected weapon"));assertEquals("RB",input.displayBinding("Machine gun"));
            assertEquals("LT",input.displayBinding("Throttle"));assertEquals("RS X",input.displayBinding("Steer"));
        }
    }
    @Test void unknownKeyCannotActivateDisabledAbilitiesWeaponSelectionOrCycle() {
        var settings=new SettingsStore.Settings();
        for(String action:java.util.List.of("Freeze","Shield","Select Homing","Select Power","Select Mine","Select Napalm","Select Ballistic","Select Cannon","Next weapon"))settings.keys.put(action,0);
        settings.keys.remove("Previous weapon");
        try(InputSystem input=input(settings)) {
            input.setGameplay(true);input.onKeyEvent(key(0,true));
            VehicleCommand command=input.consume();
            assertEquals(AbilityId.NONE,command.ability());assertNull(command.directWeapon());assertEquals(0,command.weaponDelta());
            input.onKeyEvent(key(KeyInput.KEY_W,true));assertEquals(1,input.consume().throttle());
        }
    }
    @Test void abilityKeysNeedFreshPressAndDoNotStealDriving() {
        try(InputSystem input=input()) {
            input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_W,true));
            input.onKeyEvent(key(KeyInput.KEY_Z,true));
            VehicleCommand first=input.consume();
            assertEquals(AbilityId.FREEZE,first.ability());assertEquals(1,first.throttle());
            assertEquals(AbilityId.NONE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_Z,true));assertEquals(AbilityId.NONE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_Z,false));input.onKeyEvent(key(KeyInput.KEY_Z,true));
            input.onKeyEvent(key(KeyInput.KEY_F,true));
            assertEquals(AbilityId.SHIELD,input.consume().ability(),"Same-tick defensive action has priority");
        }
    }
    @Test void abilitiesFollowTheirOwnBindingsAndCannotSurvivePause() {
        var settings=new SettingsStore.Settings();settings.keys.put("Freeze",KeyInput.KEY_T);
        try(InputSystem input=input(settings)) {
            input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_Z,true));
            assertEquals(AbilityId.NONE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_T,true));assertEquals(AbilityId.FREEZE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_T,false));input.onKeyEvent(key(KeyInput.KEY_T,true));
            input.clear();input.setGameplay(false);input.setGameplay(true);
            assertEquals(AbilityId.NONE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_T,true));assertEquals(AbilityId.NONE,input.consume().ability());
            input.onKeyEvent(key(KeyInput.KEY_T,false));input.onKeyEvent(key(KeyInput.KEY_T,true));
            assertEquals(AbilityId.FREEZE,input.consume().ability());
        }
    }
    @Test void gamepadDirectAbilitiesPreserveStickAndWeaponCycle() {
        try(InputSystem input=input()) {
            input.setGameplay(true);
            GLFWGamepadState snapshot=GLFWGamepadState.create();
            snapshot.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER,-1);snapshot.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER,-1);
            snapshot.axes(GLFW_GAMEPAD_AXIS_LEFT_X,.575f);
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_UP,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            VehicleCommand command=input.consume();assertEquals(AbilityId.FREEZE,command.ability());
            assertEquals(-1,command.weaponDelta());assertEquals(.5f,command.steer(),.00001f);
            input.acceptGamepadState(snapshot);assertEquals(AbilityId.NONE,input.consume().ability());
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_A,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            assertEquals(AbilityId.SHIELD,input.consume().ability());
            input.clear();input.acceptGamepadState(snapshot);assertEquals(AbilityId.NONE,input.consume().ability());
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT,(byte)GLFW_RELEASE);input.acceptGamepadState(snapshot);
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            assertEquals(-1,input.consume().weaponDelta());
        }
    }
    @Test void ordinaryDirectionsNeverActivateAbilities() {
        try(InputSystem input=input()) {
            input.setGameplay(true);
            for(int code:new int[]{KeyInput.KEY_LCONTROL,KeyInput.KEY_W,KeyInput.KEY_A,KeyInput.KEY_D,KeyInput.KEY_X})input.onKeyEvent(key(code,true));
            assertEquals(AbilityId.NONE,input.consume().ability());
        }
    }
    @Test void firstFreshAttacksAfterStartOrResumeNeedNoUnrelatedRelease() {
        try(InputSystem input=input()) {
            input.clear(); input.setGameplay(true);
            input.onMouseButtonEvent(mouse(0,true)); input.onMouseButtonEvent(mouse(1,true));
            input.onKeyEvent(key(KeyInput.KEY_F,true));
            VehicleCommand command=input.consume();
            assertTrue(command.machineGun()); assertTrue(command.selectedWeapon()); assertEquals(AbilityId.SHIELD,command.ability());
            assertEquals(AbilityId.NONE,input.consume().ability(),"A defensive edge is consumed exactly once");
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
            assertFalse(command.machineGun());assertEquals(AbilityId.NONE,command.ability());assertTrue(command.selectedWeapon());
            input.onKeyEvent(key(KeyInput.KEY_W,false));
            assertFalse(input.consume().machineGun(),"Unrelated key release must not re-arm old mouse hold");
            input.onMouseButtonEvent(mouse(0,false)); input.onKeyEvent(key(KeyInput.KEY_F,false));
            input.onMouseButtonEvent(mouse(0,true)); input.onKeyEvent(key(KeyInput.KEY_F,true));
            VehicleCommand fresh=input.consume();assertTrue(fresh.machineGun());assertEquals(AbilityId.SHIELD,fresh.ability());
            assertEquals(AbilityId.NONE,input.consume().ability());
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
    @Test void directSelectionSurvivesFramesAndIsClearedTogetherWithOtherEdges() {
        try(InputSystem input=input()) {
            input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_4,true));input.onKeyEvent(key(KeyInput.KEY_E,true));
            input.onMouseButtonEvent(mouse(1,true));
            input.beginInput();input.endInput();
            var command=input.consume();assertEquals(WeaponType.NAPALM,command.directWeapon());
            assertEquals(1,command.weaponDelta());assertTrue(command.selectedWeapon());
            assertNull(command.withoutEdges().directWeapon());assertNull(command.withoutAttacks().directWeapon());
            assertNull(input.consume().directWeapon());
            input.onKeyEvent(key(KeyInput.KEY_1,true));input.clear();assertNull(input.consume().directWeapon());
        }
    }
    @Test void menuGamepadActivationDoesNotLeakIntoShieldOrFreeze() {
        try(InputSystem input=input()) {
            var actions=new java.util.ArrayList<String>();input.onUi(actions::add);
            GLFWGamepadState snapshot=GLFWGamepadState.create();
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_A,(byte)GLFW_PRESS);
            snapshot.buttons(GLFW_GAMEPAD_BUTTON_DPAD_UP,(byte)GLFW_PRESS);input.acceptGamepadState(snapshot);
            assertEquals(java.util.List.of("activate","up"),actions);assertEquals(AbilityId.NONE,input.consume().ability());
            input.clear();input.setGameplay(true);input.acceptGamepadState(snapshot);
            assertEquals(AbilityId.NONE,input.consume().ability());
        }
    }
    @Test void newWeaponEdgesShareTheShotTickAndCannotLeakAcrossRetry() {
        try(InputSystem input=input()) {
            input.setGameplay(true);
            for(var entry:java.util.Map.of(KeyInput.KEY_5,WeaponType.BALLISTIC,KeyInput.KEY_6,WeaponType.CANNON).entrySet()) {
                input.onKeyEvent(key(entry.getKey(),true));input.onMouseButtonEvent(mouse(1,true));
                var command=input.consume();assertEquals(entry.getValue(),command.directWeapon());assertTrue(command.selectedWeapon());
                assertNull(input.consume().directWeapon(),"Holding a number does not select again");
                input.clear();input.setGameplay(false);input.setGameplay(true);
                input.onKeyEvent(key(entry.getKey(),true));assertNull(input.consume().directWeapon());
                input.onKeyEvent(key(entry.getKey(),false));input.onMouseButtonEvent(mouse(1,false));
                input.onKeyEvent(key(entry.getKey(),true));assertEquals(entry.getValue(),input.consume().directWeapon());
                input.onKeyEvent(key(entry.getKey(),false));
            }
        }
    }
    @Test void cannonRemappingUsesOnlyTheAssignedKey() {
        var settings=new SettingsStore.Settings();settings.keys.put("Select Cannon",KeyInput.KEY_T);
        try(InputSystem input=input(settings)) {
            input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_6,true));assertNull(input.consume().directWeapon());
            input.onKeyEvent(key(KeyInput.KEY_T,true));assertEquals(WeaponType.CANNON,input.consume().directWeapon());
        }
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
