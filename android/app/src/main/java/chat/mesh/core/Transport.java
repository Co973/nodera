package chat.mesh.core;

/** Adapters must report unavailable lanes instead of silently falling back to a media radio. */
public interface Transport extends AutoCloseable {
    enum Lane { BLE_CONTROL, WIFI_DIRECT, LAN, MESHTASTIC_TEXT }
    Lane lane();
    boolean available();
    void send(Packet packet) throws Exception;
    static boolean permits(Lane lane,int packetType) {
        if(packetType<1||packetType>6)return false;
        if(lane==Lane.MESHTASTIC_TEXT)return packetType==2;
        if(lane==Lane.BLE_CONTROL)return packetType!=4;
        return true;
    }
}
