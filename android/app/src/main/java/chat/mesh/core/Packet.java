package chat.mesh.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** The architecture's 39-byte header. Transport fragmentation sits below this codec. */
public final class Packet {
    public static final int HEADER_SIZE=39, MAX_PAYLOAD=1_048_576, MAX_TTL=7;
    public final int type, ttl;
    public final byte[] senderId, packetId, payload;
    public final long timestamp;
    public Packet(int type,int ttl,byte[] senderId,byte[] packetId,long timestamp,byte[] payload) {
        if(type<1||type>6||ttl<0||ttl>MAX_TTL||senderId.length!=8||packetId.length!=16||timestamp<0||payload.length>MAX_PAYLOAD)throw new IllegalArgumentException("Invalid packet");
        this.type=type;this.ttl=ttl;this.senderId=senderId.clone();this.packetId=packetId.clone();this.timestamp=timestamp;this.payload=payload.clone();
    }
    public byte[] encode() {
        return ByteBuffer.allocate(HEADER_SIZE+payload.length).order(ByteOrder.BIG_ENDIAN)
            .put((byte)1).put((byte)type).put((byte)ttl).put(senderId).put(packetId)
            .putLong(timestamp).putInt(payload.length).put(payload).array();
    }
    public static Packet decode(byte[] bytes) {
        if(bytes.length<HEADER_SIZE)throw new IllegalArgumentException("Truncated header");
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if(b.get()!=1)throw new IllegalArgumentException("Unsupported version");
        int type=Byte.toUnsignedInt(b.get()),ttl=Byte.toUnsignedInt(b.get());byte[] sender=new byte[8],id=new byte[16];b.get(sender);b.get(id);
        long time=b.getLong();int length=b.getInt();
        if(length<0||length>MAX_PAYLOAD||length!=b.remaining())throw new IllegalArgumentException("Invalid payload length");
        return new Packet(type,ttl,sender,id,time,Arrays.copyOfRange(bytes,HEADER_SIZE,bytes.length));
    }
    public Packet relay() { if(ttl==0)throw new IllegalStateException("TTL exhausted");return new Packet(type,ttl-1,senderId,packetId,timestamp,payload); }
}
