package chat.mesh.core;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** One bounded RPC per connection; ordered ATT writes/reads carry a 7-byte frame header. */
public final class BleFrames {
    public static final int MAX=32768,HEADER=7,MAX_CHARACTERISTIC=512;
    public static byte[] frame(byte[] value,int offset,int sequence,int mtu){
        if(value.length<1||value.length>MAX||offset<0||offset>=value.length||sequence<0||sequence>65535||mtu<23)throw new IllegalArgumentException("Invalid BLE frame");
        int budget=Math.min(mtu-3,MAX_CHARACTERISTIC);
        if(budget<=HEADER)throw new IllegalArgumentException("BLE MTU is too small");
        int count=Math.min(budget-HEADER,value.length-offset);
        return ByteBuffer.allocate(HEADER+count).put((byte)1).putInt(value.length).putShort((short)sequence).put(value,offset,count).array();
    }
    public static final class Receiver {
        private final ByteArrayOutputStream data=new ByteArrayOutputStream();private int total=-1,next=0;
        public synchronized boolean accept(byte[] frame){
            if(frame.length<=HEADER||frame[0]!=1)throw new IllegalArgumentException("Invalid BLE frame");
            ByteBuffer in=ByteBuffer.wrap(frame);in.get();int size=in.getInt(),sequence=Short.toUnsignedInt(in.getShort());
            if(size<1||size>MAX||sequence!=next||total!=-1&&size!=total||data.size()+in.remaining()>size)throw new IllegalArgumentException("Out-of-order or oversized BLE frame");
            if(total==-1)total=size;byte[] bytes=new byte[in.remaining()];in.get(bytes);data.write(bytes,0,bytes.length);next++;return data.size()==total;
        }
        public synchronized byte[] result(){if(total<0||data.size()!=total)throw new IllegalStateException("Incomplete BLE request");return data.toByteArray();}
    }
}
