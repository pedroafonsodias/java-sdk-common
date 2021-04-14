package com.launchdarkly.sdk.json;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * A helper class for interoperability with application code that uses Gson.
 * <p>
 * While the LaunchDarkly Java-based SDKs have used <a href="https://github.com/google/gson">Gson</a>
 * internally in the past, they may not always do so-- and even if they do, some SDK distributions may
 * embed their own copy of Gson with modified (shaded) class names so that it does not conflict with
 * any Gson instance elsewhere in the classpath. For both of those reasons, applications should not
 * assume that {@code Gson.toGson()} and {@code Gson.fromGson()}-- or any other JSON framework that is
 * based on reflection-- will work correctly for SDK classes, whose correct JSON representations do
 * not necessarily correspond to their internal field layout. This class addresses that issue
 * for applications that prefer to use Gson for everything rather than calling
 * {@link JsonSerialization} for individual objects.
 * <p>
 * An application that wishes to use Gson to serialize or deserialize classes from the SDK should
 * configure its {@code Gson} instance as follows:
 * <pre><code>
 *     import com.launchdarkly.sdk.json.LDGson;
 *     
 *     Gson gson = new GsonBuilder()
 *       .registerTypeAdapterFactory(LDGson.typeAdapters())
 *       // any other GsonBuilder options go here
 *       .create();
 * </code></pre>
 * <p>
 * This causes Gson to use the correct JSON representation logic (the same that would be used by
 * {@link JsonSerialization}) for any types that have the SDK's {@link JsonSerializable} marker
 * interface, such as {@link LDUser} and {@link LDValue}, regardless of whether they are the
 * top-level object being serialized or are contained in something else such as a collection. It
 * does not affect Gson's behavior for any other classes.
 * <p>
 * Note that some of the LaunchDarkly SDK distributions deliberately do not expose Gson as a
 * dependency, so if you are using Gson in your application you will need to make sure you have
 * defined your own dependency on it. Referencing {@link LDGson} will cause a runtime
 * exception if Gson is not in the caller's classpath.
 */
public abstract class LDGson {
  private static final JsonElement JSONELEMENT_TRUE = new JsonPrimitive(true);
  private static final JsonElement JSONELEMENT_FALSE = new JsonPrimitive(false);
  
  private LDGson() {}
  
  // Implementation note:
  // The reason this class exists is the Java server-side SDK's issue with Gson interoperability due
  // to the use of shading in the default jar artifact. If the Gson type references in this class
  // were also shaded in the SDK jar, then this class would not work with an unshaded Gson instance,
  // which would defeat the whole purpose. Therefore, the Java SDK build will need to have special-
  // case handling for this class (and its inner classes) when it builds the jar, and embed the
  // original class files instead of the ones that have had shading applied. By design, none of the
  // other Gson-related classes in this project would need such special handling; in the Java
  // server-side SDK jar, they would be meant to use the shaded copy of Gson.
  
  /**
   * Returns a Gson {@code TypeAdapterFactory} that defines the correct serialization and
   * deserialization behavior for all LaunchDarkly SDK objects that implement {@link JsonSerializable}.
   * <pre><code>
   *     import com.launchdarkly.sdk.json.LDGson;
   *     
   *     Gson gson = new GsonBuilder()
   *       .registerTypeAdapterFactory(LDGson.typeAdapters())
   *       // any other GsonBuilder options go here
   *       .create();
   * </code></pre>
   * @return a {@code TypeAdapterFactory}
   */
  public static TypeAdapterFactory typeAdapters() {
    return LDTypeAdapterFactory.INSTANCE;
  }
  
  /**
   * Returns a Gson {@code JsonElement} that is equivalent to the specified {@link LDValue}.
   * <p>
   * This is slightly more efficient than using {@code Gson.toJsonTree()}.
   * 
   * @param value an {@link LDValue} ({@code null} is treated as equivalent to {@link LDValue#ofNull()})
   * @return a Gson {@code JsonElement} (may be a {@code JsonNull} but will never be {@code null})
   */
  public static JsonElement valueToJsonElement(LDValue value) {
    if (value == null) {
      return JsonNull.INSTANCE;
    }
    switch (value.getType()) {
    case BOOLEAN:
      return value.booleanValue() ? JSONELEMENT_TRUE : JSONELEMENT_FALSE;
    case NUMBER:
      return new JsonPrimitive(value.doubleValue());
    case STRING:
      return value.stringValue() == null ? JsonNull.INSTANCE : new JsonPrimitive(value.stringValue());
    case ARRAY:
      JsonArray a = new JsonArray();
      for (LDValue e: value.values()) {
        a.add(valueToJsonElement(e));
      }
      return a;
    case OBJECT:
      JsonObject o = new JsonObject();
      for (String k: value.keys()) {
        o.add(k, valueToJsonElement(value.get(k)));
      }
      return o;
    default:
      return JsonNull.INSTANCE;
    }
  }
  
  /**
   * Convenience method for converting a map of {@link LDValue} values to a map of Gson {@code JsonElement}s.
   * 
   * @param <T> type of the map's keys
   * @param valueMap a map containing {@link LDValue} values
   * @return an equivalent map containing Gson {@code JsonElement} values
   */
  public static <T> Map<T, JsonElement> valueMapToJsonElementMap(Map<T, LDValue> valueMap) {
    Map<T, JsonElement> ret = new HashMap<>(valueMap.size());
    for (Map.Entry<T, LDValue> e: valueMap.entrySet()) {
      ret.put(e.getKey(), valueToJsonElement(e.getValue()));
    }
    return ret;
  }
  
  private static class LDTypeAdapterFactory implements TypeAdapterFactory {
    // Note that this static initializer will only run if application code actually references LDGson.
    private static LDTypeAdapterFactory INSTANCE = new LDTypeAdapterFactory();
    
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
      if (JsonSerializable.class.isAssignableFrom(type.getRawType())) {
        return new LDTypeAdapter<T>(gson, type.getType());
      }
      return null;
    }
  }

  private static class LDTypeAdapter<T> extends TypeAdapter<T> {
    private final Gson gson;
    private final Type objectType;
    
    LDTypeAdapter(Gson gson, Type objectType) {
      this.gson = gson;
      this.objectType = objectType;
    }
    
    @Override
    public void write(JsonWriter out, T value) throws IOException {
      String json = JsonSerialization.serializeInternal(value);
      out.jsonValue(json);
    }
  
    @Override
    public T read(JsonReader in) throws IOException {
      // This implementation is inefficient because we can't assume our internal Gson instance can
      // use this JsonReader directly; instead we have to read the next JSON value, convert it to a
      // string, and then ask our JsonSerialization to parse it back from a string.
      JsonElement jsonTree = gson.fromJson(in, JsonElement.class);
      String jsonString = gson.toJson(jsonTree);
      try {
        // Calling the Gson overload that takes a Type rather than a Class (even though a Class *is* a
        // Type) allows it to take generic type parameters into account for EvaluationDetail.
        return JsonSerialization.deserializeInternalGson(jsonString, objectType);
      } catch (SerializationException e) {
        throw new JsonParseException(e.getCause());
      }
    }
  }
}
