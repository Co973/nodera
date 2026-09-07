package chat.mesh.core;

import com.google.gson.*;
import java.util.Map;

/** Matches JSON.stringify's string encoding and preserves member insertion order. */
public final class Json {
    private Json() {}
    public static JsonObject object(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    public static JsonObject obj(Object... fields) {
        JsonObject result=new JsonObject();
        for(int i=0;i<fields.length;i+=2) {
            Object value=fields[i+1];String key=(String)fields[i];
            if(value instanceof JsonElement)result.add(key,(JsonElement)value);
            else if(value instanceof Number)result.addProperty(key,(Number)value);
            else if(value instanceof Boolean)result.addProperty(key,(Boolean)value);
            else if(value==null)result.add(key,JsonNull.INSTANCE);
            else result.addProperty(key,value.toString());
        }
        return result;
    }
    public static String str(JsonObject o,String key) { return o.get(key).getAsString(); }
    public static String stringify(JsonElement value) {
        if(value==null||value.isJsonNull())return "null";
        if(value.isJsonPrimitive())return value.getAsJsonPrimitive().isString()?quote(value.getAsString()):value.toString();
        StringBuilder out=new StringBuilder();
        if(value.isJsonArray()) {
            out.append('[');for(JsonElement item:value.getAsJsonArray()){if(out.length()>1)out.append(',');out.append(stringify(item));}return out.append(']').toString();
        }
        out.append('{');for(Map.Entry<String,JsonElement> entry:value.getAsJsonObject().entrySet()){if(out.length()>1)out.append(',');out.append(quote(entry.getKey())).append(':').append(stringify(entry.getValue()));}return out.append('}').toString();
    }
    private static String quote(String s) {
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;
                case '\b':out.append("\\b");break;case '\f':out.append("\\f");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;
                default:
                    boolean lone=Character.isHighSurrogate(c)?i+1>=s.length()||!Character.isLowSurrogate(s.charAt(i+1)):Character.isLowSurrogate(c)&&(i==0||!Character.isHighSurrogate(s.charAt(i-1)));
                    if(c<32||lone)out.append(String.format("\\u%04x",(int)c));else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
