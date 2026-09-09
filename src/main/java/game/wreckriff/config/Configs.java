package game.wreckriff.config;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Strict record-shaped bundled config; user settings have a separate tolerant store. */
public final class Configs {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path override;
    private Configs() {}
    public static void setDevOverride(Path path) { override=path; }
    public static <T> T load(String name, Class<T> type) {
        try (Reader reader = open(name)) {
            JsonElement tree=JsonParser.parseReader(reader);
            validate(tree,type,name);
            return GSON.fromJson(tree,type);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid config " + name + ": " + e.getMessage(), e);
        }
    }
    public static Reader open(String name) throws IOException {
        if (!name.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("Invalid config name");
        if (override != null) return Files.newBufferedReader(override.resolve(name+".json"), StandardCharsets.UTF_8);
        InputStream stream=Configs.class.getResourceAsStream("/config/"+name+".json");
        if (stream==null) throw new FileNotFoundException(name+".json");
        return new InputStreamReader(stream,StandardCharsets.UTF_8);
    }
    public static void validate(JsonElement value, Type type, String path) {
        if (value==null || value.isJsonNull()) throw new IllegalArgumentException(path+" is required");
        if (type instanceof ParameterizedType pt && pt.getRawType()==List.class) {
            if (!value.isJsonArray()) throw new IllegalArgumentException(path+" must be an array");
            for (JsonElement child:value.getAsJsonArray()) validate(child,pt.getActualTypeArguments()[0],path+"[]");
            return;
        }
        if (!(type instanceof Class<?> cls)) return;
        if (cls.isRecord()) {
            if (!value.isJsonObject()) throw new IllegalArgumentException(path+" must be an object");
            Set<String> expected=new HashSet<>();
            for (RecordComponent field:cls.getRecordComponents()) {
                expected.add(field.getName());
                validate(value.getAsJsonObject().get(field.getName()),field.getGenericType(),path+"."+field.getName());
            }
            for (String key:value.getAsJsonObject().keySet()) if (!expected.contains(key)) throw new IllegalArgumentException(path+" unknown field "+key);
        } else if (cls==String.class || cls.isEnum()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(path+" must be text");
        } else if (cls==boolean.class || cls==Boolean.class) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(path+" must be boolean");
        } else if (cls.isPrimitive() || Number.class.isAssignableFrom(cls)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw new IllegalArgumentException(path+" must be finite numeric");
            if ((cls==int.class || cls==long.class) && value.getAsDouble()!=Math.rint(value.getAsDouble())) throw new IllegalArgumentException(path+" must be integer");
            if ((cls==float.class || cls==Float.class) && !Float.isFinite(value.getAsFloat())) throw new IllegalArgumentException(path+" exceeds float range");
            if (cls==int.class || cls==Integer.class || cls==long.class || cls==Long.class) {
                try {
                    if(cls==int.class||cls==Integer.class)value.getAsBigDecimal().intValueExact();
                    else value.getAsBigDecimal().longValueExact();
                } catch(ArithmeticException e) {throw new IllegalArgumentException(path+" exceeds integer range",e);}
            }
        }
    }
    public static Gson gson() { return GSON; }
}
