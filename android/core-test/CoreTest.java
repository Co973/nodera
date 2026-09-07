import chat.mesh.core.Packet;
import chat.mesh.core.Transport;
import java.util.Arrays;
import java.util.HexFormat;

public final class CoreTest {
    public static void main(String[] args) {
        byte[] sender=new byte[8],id=new byte[16];Arrays.fill(sender,(byte)0xaa);Arrays.fill(id,(byte)0xbb);
        Packet p=new Packet(2,7,sender,id,123,"payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String expected="010207"+"aa".repeat(8)+"bb".repeat(16)+"000000000000007b00000007"+"7061796c6f6164";
        if(!HexFormat.of().formatHex(p.encode()).equals(expected))throw new AssertionError("Wire format mismatch");
        if(Packet.decode(p.encode()).relay().ttl!=6)throw new AssertionError("Relay TTL mismatch");
        for(int i=0;i<39;i++){try{Packet.decode(Arrays.copyOf(p.encode(),i));throw new AssertionError("Accepted truncated packet");}catch(IllegalArgumentException expectedError){}}
        if(Transport.permits(Transport.Lane.BLE_CONTROL,4)||Transport.permits(Transport.Lane.MESHTASTIC_TEXT,3)||!Transport.permits(Transport.Lane.WIFI_DIRECT,4))throw new AssertionError("Lane policy failed");
        System.out.println("Android core: wire compatibility, truncated input, relay TTL, lane constraints passed.");
    }
}
