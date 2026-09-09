package game.wreckriff.input;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import game.wreckriff.config.Configs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class GamepadProfileTest {
    @TempDir Path directory;

    @Test void firstLoadCreatesAnEditableUtf8ProfileAndLaterLoadsPreserveIt() throws Exception {
        GamepadProfile expected=GamepadProfile.bundled();
        assertEquals(expected,GamepadProfile.load(directory));
        Path file=directory.resolve("gamepad.json");byte[] original=Files.readAllBytes(file);
        assertEquals('{',original[0],"Generated JSON uses UTF-8 without a byte-order mark");
        assertEquals(expected,Configs.gson().fromJson(Files.readString(file),GamepadProfile.class));
        assertEquals(expected,GamepadProfile.load(directory));
        assertArrayEquals(original,Files.readAllBytes(file));
    }

    @Test void editedAxesButtonsAndTriggerCalibrationSurviveReloadWithoutRewriting() throws Exception {
        JsonObject json=defaults();
        json.addProperty("steerAxis",2);json.addProperty("throttleAxis",3);json.addProperty("brakeAxis",1);
        json.addProperty("machineGun",0);json.addProperty("rocket",3);json.addProperty("pulse",5);
        json.addProperty("triggerMinimum",0);json.addProperty("triggerMaximum",1);
        Path file=directory.resolve("gamepad.json");
        String original="\r\n  "+Configs.gson().toJson(json)+"\r\n";
        Files.writeString(file,original,StandardCharsets.UTF_8);
        GamepadProfile profile=GamepadProfile.load(directory);
        assertEquals(2,profile.steerAxis());assertEquals(3,profile.throttleAxis());assertEquals(1,profile.brakeAxis());
        assertEquals(0,profile.machineGun());assertEquals(3,profile.rocket());assertEquals(5,profile.pulse());
        assertEquals(0,profile.trigger(-1));assertEquals(0,profile.trigger(0));
        assertEquals(.5f,profile.trigger(.5f));assertEquals(1,profile.trigger(1));assertEquals(1,profile.trigger(2));
        assertEquals(profile,GamepadProfile.load(directory));assertEquals(original,Files.readString(file));
    }

    @Test void defaultSignedTriggerRangeMapsReleasedHalfAndFullPressure() {
        GamepadProfile profile=GamepadProfile.bundled();
        assertEquals(0,profile.trigger(-1));assertEquals(.5f,profile.trigger(0));assertEquals(1,profile.trigger(1));
        assertEquals(0,profile.trigger(-2));assertEquals(1,profile.trigger(2));
    }

    @Test void invalidSchemaAxisAndButtonValuesUseDefaultsAndPreserveOriginal() throws Exception {
        for(String field:new String[]{"schemaVersion","steerAxis","throttleAxis","brakeAxis","machineGun","pause","back"}) {
            JsonObject json=defaults();
            json.addProperty(field,field.equals("schemaVersion")?2:field.endsWith("Axis")?6:15);
            assertRejectedWithoutMutation(Configs.gson().toJson(json));
            json.addProperty(field,-1);assertRejectedWithoutMutation(Configs.gson().toJson(json));
        }
        JsonObject fractional=defaults();fractional.addProperty("steerAxis",.5);
        assertRejectedWithoutMutation(Configs.gson().toJson(fractional));
    }

    @Test void invalidTriggerCalibrationAndNonFiniteValuesPreserveOriginal() throws Exception {
        JsonObject json=defaults();json.addProperty("triggerMinimum",1);
        assertRejectedWithoutMutation(Configs.gson().toJson(json));
        json.addProperty("triggerMinimum",2);assertRejectedWithoutMutation(Configs.gson().toJson(json));
        json=defaults();json.addProperty("triggerMaximum","NaN");
        assertRejectedWithoutMutation(Configs.gson().toJson(json));
        json=defaults();json.add("triggerMaximum",JsonParser.parseString("1e999"));
        assertRejectedWithoutMutation(Configs.gson().toJson(json));
        json=defaults();json.add("triggerMaximum",JsonParser.parseString("1e39"));
        assertRejectedWithoutMutation(Configs.gson().toJson(json));
        json=defaults();json.add("triggerMinimum",JsonParser.parseString("-1e39"));
        assertRejectedWithoutMutation(Configs.gson().toJson(json));
    }

    @Test void nonFiniteCalibrationCannotEnterThroughDirectConstruction() {
        for(float value:new float[]{Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->withTriggerRange(value,1));
            assertThrows(IllegalArgumentException.class,()->withTriggerRange(-1,value));
        }
        GamepadProfile extreme=withTriggerRange(-Float.MAX_VALUE,Float.MAX_VALUE);
        assertEquals(0,extreme.trigger(-Float.MAX_VALUE));assertEquals(.5f,extreme.trigger(0));
        assertEquals(1,extreme.trigger(Float.MAX_VALUE),"Finite endpoints must not overflow when normalized");
    }

    @Test void missingUnknownAndMalformedFieldsDoNotDestroyUserEdits() throws Exception {
        JsonObject missing=defaults();missing.remove("rocket");assertRejectedWithoutMutation(Configs.gson().toJson(missing));
        JsonObject unknown=defaults();unknown.addProperty("userNote","Сохранить исходный профиль");
        assertRejectedWithoutMutation(Configs.gson().toJson(unknown));
        JsonObject nullValue=defaults();nullValue.add("rocket",null);assertRejectedWithoutMutation(Configs.gson().toJson(nullValue));
        for(String invalid:new String[]{"{bad-json","[]","null","{\"schemaVersion\":2}"})assertRejectedWithoutMutation(invalid);
    }

    @Test void unavailableDirectoryFallsBackWithoutChangingAnExistingFile() throws Exception {
        Path occupied=directory.resolve("occupied");Files.writeString(occupied,"preserve");
        assertEquals(GamepadProfile.bundled(),assertDoesNotThrow(()->GamepadProfile.load(occupied)));
        assertEquals("preserve",Files.readString(occupied));
        assertEquals(GamepadProfile.bundled(),assertDoesNotThrow(()->GamepadProfile.load(directory.resolve("missing"))));
    }

    private JsonObject defaults() { return Configs.gson().toJsonTree(GamepadProfile.bundled()).getAsJsonObject(); }

    private GamepadProfile withTriggerRange(float minimum,float maximum) {
        return new GamepadProfile(1,0,5,4,minimum,maximum,2,1,4,5,0,14,12,3,6,7,11,13,1);
    }

    private void assertRejectedWithoutMutation(String original) throws Exception {
        Path file=directory.resolve("gamepad.json");byte[] bytes=original.getBytes(StandardCharsets.UTF_8);
        Files.write(file,bytes);assertEquals(GamepadProfile.bundled(),GamepadProfile.load(directory),original);
        assertArrayEquals(bytes,Files.readAllBytes(file),"A rejected profile remains available for manual correction");
        try(var entries=Files.list(directory)) { assertEquals(1,entries.count(),"Loading must not rename or replace the rejected original"); }
    }
}
