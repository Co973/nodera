import chat.mesh.core.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.Arrays;
import static chat.mesh.core.Json.*;
import static chat.mesh.core.MeshCrypto.*;

public final class InteropTest {
    public static void main(String[] args)throws Exception{
        JsonObject fixture=object(text(Files.readAllBytes(Paths.get(args[0]))));
        JsonObject identity=fixture.getAsJsonObject("receiver"),envelope=fixture.getAsJsonObject("envelope");
        JsonObject decoded=open(identity,envelope);
        if(!str(decoded,"text").equals(str(fixture,"text")))throw new AssertionError("Node to Java decryption mismatch");
        JsonObject tampered=envelope.deepCopy();tampered.getAsJsonObject("box").addProperty("tag",b64(new byte[16]));
        try{open(identity,tampered);throw new AssertionError("Accepted invalid authentication");}catch(SecurityException expected){}
        JsonObject from=identity("Android test");JsonObject reply=seal(from,card(identity),obj("kind","text","text",str(fixture,"text")));
        Files.write(Paths.get(args[1]),bytes(stringify(obj("identity",card(from),"envelope",reply))));
        byte[] payload=new byte[30000];Arrays.fill(payload,(byte)42);
        for(int mtu:new int[]{23,247,517}){
            BleFrames.Receiver receiver=new BleFrames.Receiver();int offset=0,sequence=0;while(offset<payload.length){byte[] frame=BleFrames.frame(payload,offset,sequence++,mtu);receiver.accept(frame);offset+=frame.length-BleFrames.HEADER;}
            if(!Arrays.equals(payload,receiver.result()))throw new AssertionError("BLE fragmentation mismatch");
        }
        byte[] unicode="Hello 👋 世界".getBytes(java.nio.charset.StandardCharsets.UTF_8);BleFrames.Receiver unicodeReceiver=new BleFrames.Receiver();
        int unicodeOffset=0,unicodeSequence=0;while(unicodeOffset<unicode.length){byte[] frame=BleFrames.frame(unicode,unicodeOffset,unicodeSequence++,517);if(frame.length>BleFrames.MAX_CHARACTERISTIC)throw new AssertionError("Characteristic value exceeded cap");unicodeReceiver.accept(frame);unicodeOffset+=frame.length-BleFrames.HEADER;}
        if(!Arrays.equals(unicode,unicodeReceiver.result()))throw new AssertionError("Unicode BLE fragmentation mismatch");
        BleFrames.Receiver receiver=new BleFrames.Receiver();byte[] first=BleFrames.frame(payload,0,0,23);receiver.accept(first);
        try{receiver.accept(first);throw new AssertionError("Accepted duplicate sequence");}catch(IllegalArgumentException expected){}
        try{new BleFrames.Receiver().accept(new byte[]{1,0,0,0,1,0,0});throw new AssertionError("Accepted malformed frame");}catch(IllegalArgumentException expected){}
        System.out.println("Cross-language envelope authentication and BLE fragmentation checks passed.");
    }
}
