package game.wreckriff.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleRulesTest {
    @Test void assistanceTuningIsRequiredFiniteAndBounded() {
        for(String group:new String[]{"groundStability","selfRighting"}) {
            JsonObject original=Configs.gson().toJsonTree(VehicleRules.load()).getAsJsonObject();
            for(String field:original.getAsJsonObject(group).keySet()) {
                for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
                    JsonObject json=original.deepCopy();json.getAsJsonObject(group).addProperty(field,value);
                    assertThrows(RuntimeException.class,()->Configs.gson().fromJson(json,VehicleRules.class),group+"."+field+"="+value);
                }
                JsonObject missing=original.deepCopy();missing.getAsJsonObject(group).remove(field);
                assertThrows(IllegalArgumentException.class,()->Configs.validate(missing,VehicleRules.class,"vehicle"));
            }
            JsonObject missing=original.deepCopy();missing.remove(group);
            assertThrows(IllegalArgumentException.class,()->Configs.validate(missing,VehicleRules.class,"vehicle"));
        }
        for(String field:new String[]{"tiltDegrees","maxSpeed","holdSeconds","angularAcceleration","angularSpeed","response"}) {
            JsonObject json=Configs.gson().toJsonTree(VehicleRules.load()).getAsJsonObject();
            json.getAsJsonObject("selfRighting").addProperty(field,1000);
            assertThrows(RuntimeException.class,()->Configs.gson().fromJson(json,VehicleRules.class),field);
        }
    }
    @Test void reverseTuningRejectsInvalidValuesAndMissingFields() {
        for(String field:new String[]{"reverseForceMultiplier","directionChangeDelaySeconds"}) {
            for(double invalid:new double[]{-.1,Double.NaN,Double.POSITIVE_INFINITY}) {
                JsonObject json=Configs.gson().toJsonTree(VehicleRules.load()).getAsJsonObject();
                json.addProperty(field,invalid);
                assertThrows(RuntimeException.class,()->Configs.gson().fromJson(json,VehicleRules.class),field+" = "+invalid);
            }
            JsonObject missing=Configs.gson().toJsonTree(VehicleRules.load()).getAsJsonObject();missing.remove(field);
            assertThrows(IllegalArgumentException.class,()->Configs.validate(missing,VehicleRules.class,"vehicle"));
        }
        JsonObject json=Configs.gson().toJsonTree(VehicleRules.load()).getAsJsonObject();
        json.addProperty("reverseForceMultiplier",0);
        assertThrows(RuntimeException.class,()->Configs.gson().fromJson(json,VehicleRules.class));
        json.addProperty("reverseForceMultiplier",1.2);
        json.addProperty("directionChangeDelaySeconds",1.1);
        assertThrows(RuntimeException.class,()->Configs.gson().fromJson(json,VehicleRules.class));
        json.addProperty("directionChangeDelaySeconds",0);
        assertDoesNotThrow(()->Configs.gson().fromJson(json,VehicleRules.class));
    }
}
