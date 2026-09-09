package game.wreckriff.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigsTest {
    @Test void finiteJsonCannotOverflowTheTargetJavaType() {
        for(String value:new String[]{"1e39","-1e39"})
            assertThrows(IllegalArgumentException.class,()->Configs.validate(JsonParser.parseString(value),float.class,"field"));
        for(String value:new String[]{"2147483648","-2147483649","1.5"})
            assertThrows(IllegalArgumentException.class,()->Configs.validate(JsonParser.parseString(value),int.class,"field"));
        assertThrows(IllegalArgumentException.class,()->Configs.validate(JsonParser.parseString("9223372036854775808"),long.class,"field"));
        assertDoesNotThrow(()->Configs.validate(JsonParser.parseString("9223372036854775807"),long.class,"field"));
    }
}
