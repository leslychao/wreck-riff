package game.wreckriff.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleRulesTest {
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
