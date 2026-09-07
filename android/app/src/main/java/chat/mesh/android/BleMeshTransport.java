package chat.mesh.android;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import android.util.Log;
import com.google.gson.*;
import chat.mesh.core.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static chat.mesh.core.Json.*;
import static chat.mesh.core.MeshCrypto.*;

/** Experimental Android-to-Android encrypted text transport. Requires hardware validation. */
public final class BleMeshTransport implements MeshNode.Radio,AutoCloseable {
    public interface Listener {void peer(String address);void status(String value);}
    public static final UUID SERVICE=UUID.fromString("6d657368-6368-4174-8000-000000000001"),RX=UUID.fromString("6d657368-6368-4174-8000-000000000002"),TX=UUID.fromString("6d657368-6368-4174-8000-000000000003");
    private final Context context;private final MeshNode node;private final BluetoothAdapter adapter;private final BluetoothManager manager;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService rpc=new ThreadPoolExecutor(1,3,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private final Map<String,Session> sessions=new HashMap<>();
    private final ConcurrentMap<String,ReentrantLock> clientLocks=new ConcurrentHashMap<>();
    private final Set<BluetoothGatt> activeClients=ConcurrentHashMap.newKeySet();
    private BluetoothGattServer server;private BluetoothLeScanner scanner;private BluetoothLeAdvertiser advertiser;
    private Listener listener;private boolean advertising=false,scanning=false;
    private static final class Session {BleFrames.Receiver input=new BleFrames.Receiver();byte[] reply;int offset,sequence,mtu=23;boolean processing;long touched=SystemClock.elapsedRealtime();}
    public BleMeshTransport(Context context,MeshNode node){this.context=context;this.node=node;manager=context.getSystemService(BluetoothManager.class);adapter=manager==null?null:manager.getAdapter();}
    private void status(String value){Listener l=listener;if(l!=null)main.post(()->l.status(value));}
    private final Runnable scanFinished=()->{stopScan();status("Bluetooth ready · scan finished");listener=null;};
    private final ScanCallback scan=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){Listener l=listener;if(l!=null&&scanning)try{String address=result.getDevice().getAddress();main.post(()->l.peer(address));}catch(SecurityException ignored){}}
        @Override public void onScanFailed(int error){status("Scan failed ("+error+"). Try again shortly.");stopScan();}
    };
    private final AdvertiseCallback advertise=new AdvertiseCallback(){
        @Override public void onStartSuccess(AdvertiseSettings settings){advertising=true;status("Bluetooth listening · scanning nearby");Log.i("NoderaBle","GATT server advertising");}
        @Override public void onStartFailure(int error){advertising=false;status("Scanning only: advertising failed ("+error+").");}
    };
    public void startDiscovery(Listener listener){
        this.listener=listener;
        try{
            if(adapter==null||!adapter.isEnabled()){status("Enable Bluetooth in system settings, then try Nearby again.");return;}
            if(server==null)openServer();
            else startAdvertising();
            stopScan();scanner=adapter.getBluetoothLeScanner();if(scanner==null)throw new IOException("BLE scan unavailable");scanning=true;
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),scan);
            main.postDelayed(scanFinished,30000);status("Scanning for nearby Nodera nodes…");
        }catch(Exception e){status(e.getMessage()==null?"Bluetooth unavailable":e.getMessage());}
    }
    /** Starts the GATT server and advertisement without a scan so a foreground node remains reachable. */
    public void startListening(Listener listener){this.listener=listener;startListening();}
    public void startListening(){
        try{
            if(adapter==null||!adapter.isEnabled())throw new IOException("Bluetooth is disabled");
            if(server==null)openServer();
            else startAdvertising();
        }catch(Exception e){status(e.getMessage()==null?"Bluetooth unavailable":e.getMessage());}
    }
    private void openServer()throws IOException{
        server=manager.openGattServer(context,gattServer);if(server==null)throw new IOException("GATT server unavailable");
        BluetoothGattService service=new BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(RX,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE));
        service.addCharacteristic(new BluetoothGattCharacteristic(TX,BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
        if(!server.addService(service)){server.close();server=null;throw new IOException("Unable to register Bluetooth service");}
    }
    private void startAdvertising(){try{if(advertising)return;advertiser=adapter.getBluetoothLeAdvertiser();if(advertiser==null){status("This device cannot advertise. It can still connect to other nodes.");return;}
        advertiser.startAdvertising(new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW).setConnectable(true).build(),new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).setIncludeDeviceName(false).build(),advertise);
    }catch(SecurityException e){status("Nearby devices permission is required.");}}
    private void stopScan(){scanning=false;main.removeCallbacks(scanFinished);try{if(scanner!=null)scanner.stopScan(scan);}catch(Exception ignored){}scanner=null;}
    private final BluetoothGattServerCallback gattServer=new BluetoothGattServerCallback(){
        @Override public void onServiceAdded(int status,BluetoothGattService service){if(status==BluetoothGatt.GATT_SUCCESS)startAdvertising();else status("Bluetooth service registration failed.");}
        @Override public void onConnectionStateChange(BluetoothDevice device,int status,int state){try{synchronized(sessions){String address=device.getAddress();if(status!=BluetoothGatt.GATT_SUCCESS||state!=BluetoothProfile.STATE_CONNECTED)sessions.remove(address);else {if(sessions.size()>=8){server.cancelConnection(device);return;}sessions.put(address,new Session());}}}catch(SecurityException ignored){}}
        @Override public void onMtuChanged(BluetoothDevice device,int mtu){synchronized(sessions){Session s=sessions.get(device.getAddress());if(s!=null)s.mtu=Math.max(23,Math.min(515,mtu));}}
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device,int requestId,BluetoothGattCharacteristic characteristic,boolean prepared,boolean responseNeeded,int offset,byte[] value){
            int status=BluetoothGatt.GATT_SUCCESS;Session session=null;boolean complete=false;
            try{
                if(!characteristic.getUuid().equals(RX)||prepared||offset!=0||value==null||value.length>BleFrames.MAX_CHARACTERISTIC)throw new IOException("Unsupported write");
                synchronized(sessions){session=sessions.get(device.getAddress());if(session==null||session.processing)throw new IOException("Connection busy");if(SystemClock.elapsedRealtime()-session.touched>60000)session.input=new BleFrames.Receiver();session.touched=SystemClock.elapsedRealtime();complete=session.input.accept(value);if(complete)session.processing=true;}
            }catch(Exception e){status=BluetoothGatt.GATT_FAILURE;}
            try{if(responseNeeded&&server!=null)server.sendResponse(device,requestId,status,0,null);}catch(SecurityException ignored){}
            if(complete&&status==BluetoothGatt.GATT_SUCCESS){final Session target=session;try{rpc.execute(()->{
                JsonObject reply;
                try{JsonObject request=object(text(target.input.result()));String method=str(request,"method");if(method.equals("identity"))reply=node.publicIdentity();else if(method.equals("mesh")){JsonObject data=request.getAsJsonObject("data");int ttl=data.get("ttl").getAsInt();if(ttl!=data.get("ttl").getAsDouble())throw new IOException("Invalid TTL");reply=obj("reply",node.route(data.getAsJsonObject("envelope"),ttl,true));}else throw new IOException("Unsupported request");}
                catch(Exception e){reply=obj("error",e.getMessage()==null?"Request rejected":e.getMessage());}
                byte[] bytes=bytes(stringify(reply));if(bytes.length>BleFrames.MAX)bytes=bytes("{\"error\":\"Bluetooth response exceeds text limit\"}");synchronized(sessions){target.reply=bytes;target.offset=0;target.sequence=0;target.touched=SystemClock.elapsedRealtime();}
            });}catch(RejectedExecutionException e){synchronized(sessions){target.reply=bytes("{\"error\":\"Node busy\"}");}}}
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device,int requestId,int offset,BluetoothGattCharacteristic characteristic){
            byte[] value=new byte[]{0};int status=BluetoothGatt.GATT_SUCCESS;
            try{
                if(!characteristic.getUuid().equals(TX)||offset!=0)throw new IOException("Unsupported read");
                synchronized(sessions){Session s=sessions.get(device.getAddress());if(s==null)throw new IOException("Unknown session");s.touched=SystemClock.elapsedRealtime();if(s.reply!=null){if(s.offset>=s.reply.length)throw new IOException("Response exhausted");value=BleFrames.frame(s.reply,s.offset,s.sequence++,s.mtu);s.offset+=value.length-BleFrames.HEADER;}}
            }catch(Exception e){status=BluetoothGatt.GATT_FAILURE;}
            try{if(server!=null)server.sendResponse(device,requestId,status,0,value);}catch(SecurityException ignored){}
        }
    };
    private boolean write(BluetoothGatt gatt,BluetoothGattCharacteristic c,byte[] value){
        if(Build.VERSION.SDK_INT>=33)return gatt.writeCharacteristic(c,value,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothGatt.GATT_SUCCESS;
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);c.setValue(value);return gatt.writeCharacteristic(c);
    }
    // Android 13 added a value-bearing read callback, but starting a read remains boolean.
    private boolean read(BluetoothGatt gatt,BluetoothGattCharacteristic c){return gatt.readCharacteristic(c);}
    @Override public JsonObject exchange(String address,String method,JsonObject data)throws Exception{
        if(adapter==null||!adapter.isEnabled())throw new IOException("Bluetooth is disabled");
        byte[] request=bytes(stringify(obj("method",method,"data",data)));if(request.length>BleFrames.MAX)throw new IOException("Bluetooth message exceeds the 32 KB text limit");
        ReentrantLock lock=clientLocks.computeIfAbsent(address,key->new ReentrantLock());if(!lock.tryLock(65,TimeUnit.SECONDS))throw new IOException("Bluetooth exchange already in progress");
        long started=SystemClock.elapsedRealtime();
        try{
        final class Client extends BluetoothGattCallback {
            final CountDownLatch ready=new CountDownLatch(1);volatile CountDownLatch operation=new CountDownLatch(0);volatile int mtu=23,status=BluetoothGatt.GATT_SUCCESS;volatile byte[] value;volatile String stage="connecting";volatile boolean connected;
            private void fail(String next,int result){stage=next;status=result==BluetoothGatt.GATT_SUCCESS?BluetoothGatt.GATT_FAILURE:result;ready.countDown();operation.countDown();}
            @Override public void onConnectionStateChange(BluetoothGatt g,int result,int state){if(result!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED)fail("connection lost",result);else if(state==BluetoothProfile.STATE_CONNECTED){connected=true;stage="discovering services";if(!g.discoverServices())fail("service discovery could not start",BluetoothGatt.GATT_FAILURE);}}
            @Override public void onServicesDiscovered(BluetoothGatt g,int result){if(result!=BluetoothGatt.GATT_SUCCESS){fail("service discovery",result);return;}stage="negotiating MTU";if(!g.requestMtu(247)){mtu=23;ready.countDown();}}
            @Override public void onMtuChanged(BluetoothGatt g,int size,int result){if(result==BluetoothGatt.GATT_SUCCESS)mtu=Math.max(23,Math.min(515,size));else mtu=23;ready.countDown();}
            @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int result){status=result;operation.countDown();}
            private void received(byte[] bytes,int result){status=result;value=bytes;operation.countDown();}
            @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int result){received(c.getValue(),result);}
            @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int result){received(value,result);}
            void await(String next)throws Exception{stage=next;if(!operation.await(8,TimeUnit.SECONDS))throw new IOException("Bluetooth "+next+" timed out");if(status!=BluetoothGatt.GATT_SUCCESS)throw new IOException("Bluetooth "+next+" failed (GATT "+status+")");}
        }
        Client client=new Client();BluetoothGatt gatt=adapter.getRemoteDevice(address.substring(6)).connectGatt(context,false,client,BluetoothDevice.TRANSPORT_LE);
        if(gatt==null)throw new IOException("Unable to connect to Bluetooth peer");
        activeClients.add(gatt);
        try{
            if(!client.ready.await(12,TimeUnit.SECONDS)||client.status!=BluetoothGatt.GATT_SUCCESS)throw new IOException("Bluetooth "+client.stage+" failed (GATT "+client.status+")");
            BluetoothGattService service=gatt.getService(SERVICE);if(service==null)throw new IOException("Nodera service unavailable");BluetoothGattCharacteristic rx=service.getCharacteristic(RX),tx=service.getCharacteristic(TX);if(rx==null||tx==null)throw new IOException("Nodera characteristics unavailable");
            int offset=0,sequence=0;long deadline=System.currentTimeMillis()+60000;
            while(offset<request.length){if(System.currentTimeMillis()>deadline)throw new IOException("Bluetooth request timed out");byte[] frame=BleFrames.frame(request,offset,sequence++,client.mtu);client.operation=new CountDownLatch(1);if(!write(gatt,rx,frame))throw new IOException("Bluetooth write could not start");client.await("write");offset+=frame.length-BleFrames.HEADER;}
            BleFrames.Receiver receiver=new BleFrames.Receiver();
            while(System.currentTimeMillis()<deadline){client.operation=new CountDownLatch(1);if(!read(gatt,tx))throw new IOException("Bluetooth response read could not start");client.await("response read");if(client.value==null)throw new IOException("Bluetooth response was empty");if(client.value.length==1&&client.value[0]==0){Thread.sleep(100);continue;}if(receiver.accept(client.value)){JsonObject response=object(text(receiver.result()));if(response.has("error"))throw new IOException("Bluetooth remote rejection: "+str(response,"error"));Log.i("NoderaBle","GATT exchange completed in "+(SystemClock.elapsedRealtime()-started)+" ms");return response;}}
            throw new IOException("Bluetooth response timed out");
        }finally{activeClients.remove(gatt);try{gatt.disconnect();}finally{gatt.close();}}
        }finally{lock.unlock();clientLocks.remove(address,lock);}
    }
    @Override public void close(){stopScan();for(BluetoothGatt gatt:activeClients){try{gatt.disconnect();gatt.close();}catch(Exception ignored){}}activeClients.clear();try{if(advertiser!=null)advertiser.stopAdvertising(advertise);}catch(Exception ignored){}try{if(server!=null)server.close();}catch(Exception ignored){}server=null;advertising=false;rpc.shutdownNow();synchronized(sessions){sessions.clear();}listener=null;}
}
