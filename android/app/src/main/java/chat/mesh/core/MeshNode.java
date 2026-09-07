package chat.mesh.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static chat.mesh.core.Json.*;
import static chat.mesh.core.MeshCrypto.*;

public final class MeshNode implements AutoCloseable {
    public static final int CHUNK=192*1024, MAX_FILE=25*1024*1024;
    public interface Radio { JsonObject exchange(String address,String method,JsonObject data)throws Exception; }
    public final MeshVault vault;
    private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(3);
    private final Map<String,Long> relayed=new LinkedHashMap<>();
    private final Set<String> transfers=new HashSet<>();
    private volatile boolean closed=false;
    private boolean flushing=false;
    public volatile Radio radio;
    public MeshNode(Path data)throws Exception{vault=new MeshVault(data);workers.scheduleWithFixedDelay(()->{try{flush();}catch(Exception ignored){}},10,10,TimeUnit.SECONDS);}
    public synchronized boolean unlocked(){return vault.state!=null;}
    private JsonObject identity(){return vault.state.getAsJsonObject("identity");}
    private JsonArray array(String key){return vault.state.getAsJsonArray(key);}
    private void requireUnlocked(){if(!unlocked())throw new IllegalStateException("Unlock your node first");}
    public synchronized void unlock(String password,String name)throws Exception{
        if(unlocked())throw new IllegalStateException("Already unlocked");if(name.length()>60)throw new IllegalArgumentException("Name is too long");
        vault.unlock(password,name);for(JsonElement value:array("files")){JsonObject f=value.getAsJsonObject();if(str(f,"status").equals("transferring"))f.addProperty("status","paused");}
    }
    public synchronized JsonObject publicIdentity()throws Exception{requireUnlocked();return card(identity());}
    public synchronized JsonObject snapshot(int meshPort)throws Exception{
        requireUnlocked();JsonArray addresses=new JsonArray();
        for(NetworkInterface net:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress address:Collections.list(net.getInetAddresses()))if(address instanceof Inet4Address&&!address.isLoopbackAddress())addresses.add(address.getHostAddress()+":"+meshPort);
        if(addresses.size()==0)addresses.add("127.0.0.1:"+meshPort);
        return obj("identity",card(identity()),"peers",array("peers").deepCopy(),"messages",array("messages").deepCopy(),"files",array("files").deepCopy(),"outbox",array("outbox").size(),"meshPort",meshPort,"addresses",addresses,"platform","android");
    }
    private JsonObject peer(String id){for(JsonElement v:array("peers")){JsonObject p=v.getAsJsonObject();if(str(p,"id").equals(id))return p;}throw new SecurityException("Add this peer before exchanging messages");}
    private JsonObject file(String hash,String peer,String direction){for(JsonElement v:array("files")){JsonObject f=v.getAsJsonObject();if(str(f,"hash").equals(hash)&&str(f,"peer").equals(peer)&&str(f,"direction").equals(direction))return f;}return null;}
    private static boolean isBle(String address){return address!=null&&address.startsWith("ble://");}
    /** Reads both new endpoint fields and the address used by preview vaults. */
    private static String endpoint(JsonObject peer,boolean bluetooth){
        String key=bluetooth?"bleAddress":"lanAddress";
        if(peer.has(key)&&!peer.get(key).isJsonNull())return str(peer,key);
        String legacy=peer.has("address")?str(peer,"address"):null;
        return legacy!=null&&isBle(legacy)==bluetooth?legacy:null;
    }
    private static void setEndpoint(JsonObject peer,String address){
        peer.addProperty(isBle(address)?"bleAddress":"lanAddress",address);
        // Keep this field for old UI/vault compatibility, preferring LAN for media actions.
        String lan=endpoint(peer,false),ble=endpoint(peer,true);peer.addProperty("address",lan!=null?lan:ble);
    }
    private static List<String> endpoints(JsonObject peer,boolean media){
        List<String> result=new ArrayList<>();String lan=endpoint(peer,false),ble=endpoint(peer,true);
        if(lan!=null)result.add(lan);if(!media&&ble!=null)result.add(ble);return result;
    }
    public static String normalize(String input)throws Exception{
        if(input.matches("ble://([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))return "ble://"+input.substring(6).toUpperCase(Locale.ROOT);
        URI u=new URI(input.contains("://")?input:"http://"+input);String h=u.getHost();
        if(!"http".equals(u.getScheme())||h==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/"))||u.getPort()<1||u.getPort()>65535)throw new IllegalArgumentException("Enter a private LAN IPv4 address and port");
        if(!h.equals("localhost")){
            if(!h.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IllegalArgumentException("Only private LAN IPv4 addresses are supported");
            String[] parts=h.split("\\.");int[] n=new int[4];for(int i=0;i<4;i++){n[i]=Integer.parseInt(parts[i]);if(n[i]>255)throw new IllegalArgumentException("Invalid IPv4 address");}
            if(!(n[0]==127||n[0]==10||n[0]==192&&n[1]==168||n[0]==172&&n[1]>=16&&n[1]<=31))throw new IllegalArgumentException("Use a private LAN address");
        }
        return "http://"+h+":"+u.getPort();
    }
    public JsonObject remote(String address,String method,JsonObject data)throws Exception{
        if(address.startsWith("ble://")){Radio r=radio;if(r==null)throw new IOException("Bluetooth transport is unavailable");return r.exchange(address,method,data);}
        HttpURLConnection c=(HttpURLConnection)new URL(address+"/"+method).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);
        try{
            if(data!=null){byte[] request=bytes(stringify(data));c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(request.length);try(OutputStream out=c.getOutputStream()){out.write(request);}}
            int responseCode=c.getResponseCode();if(responseCode!=200){String detail="";InputStream error=c.getErrorStream();if(error!=null)try{JsonObject body=object(text(readLimited(error,64*1024)));if(body.has("error"))detail=str(body,"error");}catch(Exception ignored){}throw new IOException(detail.isEmpty()?"Peer unavailable or request rejected":detail);}
            try(InputStream in=c.getInputStream()){return object(text(readLimited(in,1024*1024)));}
        }finally{c.disconnect();}
    }
    public static byte[] readLimited(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("Data exceeds limit");out.write(buffer,0,n);}return out.toByteArray();}
    public void addPeer(String input)throws Exception{
        String address=normalize(input);synchronized(this){requireUnlocked();}
        JsonObject p=remote(address,"identity",null);verifyCard(p);
        synchronized(this){
            if(str(p,"id").equals(str(card(identity()),"id")))throw new IllegalArgumentException("Cannot add yourself");
            for(JsonElement v:array("peers")){
                JsonObject existing=v.getAsJsonObject();
                for(String known:endpoints(existing,false))if(known.equals(address)&&!str(existing,"id").equals(str(p,"id")))throw new SecurityException("Identity changed at this address");
                if(str(existing,"id").equals(str(p,"id"))){if(!str(existing,"exchange").equals(str(p,"exchange")))throw new SecurityException("Pinned key changed");setEndpoint(existing,address);vault.save();retry();return;}
            }
            if(array("peers").size()>=100)throw new IllegalStateException("Peer limit reached");
            setEndpoint(p,address);p.addProperty("verified",false);array("peers").add(p);vault.save();
        }
        retry();
    }
    public synchronized void verifyPeer(String id,boolean value)throws Exception{peer(id).addProperty("verified",value);vault.save();}
    private JsonObject queue(JsonObject peer,JsonObject payload)throws Exception{if(array("outbox").size()>=1000)throw new IllegalStateException("Outbox is full");JsonObject e=seal(identity(),peer,payload);array("outbox").add(obj("envelope",e));return e;}
    public synchronized void send(String id,String value)throws Exception{
        requireUnlocked();String message=value.trim();if(message.isEmpty()||message.length()>8000)throw new IllegalArgumentException("Text must be 1–8,000 characters");if(array("messages").size()>=10000)throw new IllegalStateException("Message store is full");
        JsonObject envelope=queue(peer(id),obj("kind","text","text",message));array("messages").add(obj("id",str(envelope,"id"),"peer",id,"text",message,"direction","out","status","queued","time",System.currentTimeMillis()));vault.save();retry();
    }
    public synchronized void offer(String id,String name,byte[] data)throws Exception{
        requireUnlocked();JsonObject p=peer(id);if(endpoint(p,false)==null)throw new IllegalArgumentException("Add this peer's LAN address to share files. Bluetooth carries text only in this build.");
        if(name.length()>200||data.length==0||data.length>MAX_FILE)throw new IllegalArgumentException("Choose a file between 1 byte and 25 MB");checkQuota(data.length);
        String h=hash(data);int chunks=(data.length+CHUNK-1)/CHUNK;JsonArray have=new JsonArray();
        for(int i=0;i<chunks;i++){vault.writeBlob(h,i,Arrays.copyOfRange(data,i*CHUNK,Math.min(data.length,(i+1)*CHUNK)));have.add(i);}
        if(file(h,id,"out")==null)array("files").add(obj("hash",h,"size",data.length,"name",name,"peer",id,"chunks",chunks,"direction","out","status","shared","have",have));
        queue(p,obj("kind","file","hash",h,"size",data.length,"name",name));vault.save();retry();
    }
    private void checkQuota(int size){long total=size;for(JsonElement value:array("files"))total+=value.getAsJsonObject().get("size").getAsLong();if(total>250L*1024*1024||array("files").size()>=1000)throw new IllegalStateException("Local file quota reached");}
    public JsonObject route(JsonObject envelope,int ttl,boolean bluetooth)throws Exception{
        if(ttl<0||ttl>7)throw new IllegalArgumentException("Invalid TTL");JsonObject header=verifyEnvelope(envelope);List<JsonObject> peers=new ArrayList<>();
        synchronized(this){
            requireUnlocked();
            if(str(header,"to").equals(str(card(identity()),"id"))){
                JsonObject from=header.getAsJsonObject("from"),p=peer(str(from,"id"));if(!str(p,"exchange").equals(str(from,"exchange")))throw new SecurityException("Peer key changed");
                JsonObject payload=open(identity(),envelope);String kind=str(payload,"kind");
                if(kind.equals("chunk-request")){
                    if(bluetooth)throw new IllegalArgumentException("Media is not allowed over Bluetooth");
                    JsonObject f=file(str(payload,"hash"),str(p,"id"),"out");int index=payload.get("index").getAsInt();
                    if(f==null||index<0||index>=f.get("chunks").getAsInt())throw new IllegalArgumentException("File not shared with this peer");
                    return seal(identity(),p,obj("kind","chunk","request",str(header,"id"),"hash",str(f,"hash"),"index",index,"data",b64(vault.readBlob(str(f,"hash"),index))));
                }
                if(!array("seen").contains(new JsonPrimitive(str(header,"id")))){
                    if(array("messages").size()>=10000)throw new IllegalStateException("Message store is full");
                    if(kind.equals("text")){
                        String message=str(payload,"text");if(message.trim().isEmpty()||message.length()>8000)throw new IllegalArgumentException("Invalid text");
                        array("messages").add(obj("id",str(header,"id"),"peer",str(p,"id"),"direction","in","text",message,"time",System.currentTimeMillis(),"status","delivered"));
                    }else if(kind.equals("file")){
                        long length=payload.get("size").getAsLong();String h=str(payload,"hash"),name=str(payload,"name");
                        if(!h.matches("[a-f0-9]{64}")||length<1||length>MAX_FILE||name.length()>200)throw new IllegalArgumentException("Invalid file announcement");
                        if(file(h,str(p,"id"),"in")==null){checkQuota((int)length);array("files").add(obj("hash",h,"size",length,"name",name,"peer",str(p,"id"),"direction","in","chunks",(length+CHUNK-1)/CHUNK,"have",new JsonArray(),"status","offered"));}
                    }else throw new IllegalArgumentException("Unsupported payload");
                    array("seen").add(str(header,"id"));if(array("seen").size()>20000)array("seen").remove(0);vault.save();
                }
                return seal(identity(),p,obj("kind","ack","packet",str(header,"id")));
            }
            if(ttl==0)return null;String id=str(header,"id");Long last=relayed.get(id);if(last!=null&&System.currentTimeMillis()-last<6000)return null;
            relayed.put(id,System.currentTimeMillis());if(relayed.size()>10000)relayed.remove(relayed.keySet().iterator().next());
            for(JsonElement value:array("peers"))peers.add(value.getAsJsonObject().deepCopy());
        }
        peers.sort((a,b)->Boolean.compare(str(b,"id").equals(str(header,"to")),str(a,"id").equals(str(header,"to"))));
        IOException failure=null;
        for(JsonObject p:peers)for(String address:endpoints(p,false)){try{JsonObject result=remote(address,"mesh",obj("envelope",envelope,"ttl",ttl-1));if(result.has("reply")&&!result.get("reply").isJsonNull())return result.getAsJsonObject("reply");}catch(Exception e){failure=new IOException((isBle(address)?"Bluetooth ":"LAN ")+(e.getMessage()==null?"exchange failed":e.getMessage()),e);}}
        if(failure!=null)throw failure;
        return null;
    }
    private void retry(){if(!closed)workers.execute(()->{try{flush();}catch(Exception ignored){}});}
    public void flush()throws Exception{
        List<JsonObject> pending=new ArrayList<>();synchronized(this){if(closed||!unlocked()||flushing)return;flushing=true;for(JsonElement value:array("outbox"))pending.add(value.getAsJsonObject());}
        try{for(JsonObject item:pending){if(closed)return;JsonObject envelope=item.getAsJsonObject("envelope");String id=str(envelope,"id");
            if(envelope.get("expires").getAsLong()<System.currentTimeMillis()){synchronized(this){array("outbox").remove(item);messageStatus(id,"expired");vault.save();}continue;}
            try{
                synchronized(this){relayed.remove(id);}JsonObject reply=route(envelope,7,false);if(reply==null){synchronized(this){messageStatus(id,"waiting","No route to this peer yet");vault.save();}continue;}
                synchronized(this){JsonObject p=peer(str(envelope,"to")),from=reply.getAsJsonObject("from");if(!str(p,"id").equals(str(from,"id"))||!str(p,"exchange").equals(str(from,"exchange"))){messageStatus(id,"waiting","Reply came from an unexpected peer");vault.save();continue;}
                    JsonObject ack=open(identity(),reply);if(!str(ack,"kind").equals("ack")||!str(ack,"packet").equals(id)){messageStatus(id,"waiting","Peer did not confirm delivery");continue;}
                    array("outbox").remove(item);messageStatus(id,"delivered");vault.save();}
            }catch(Exception e){synchronized(this){messageStatus(id,"waiting",e.getMessage()==null?"Connection failed":e.getMessage());try{vault.save();}catch(Exception ignored){}}}
        }}finally{synchronized(this){flushing=false;}}
    }
    private synchronized void messageStatus(String id,String status){messageStatus(id,status,null);}
    private synchronized void messageStatus(String id,String status,String error){for(JsonElement v:array("messages")){JsonObject m=v.getAsJsonObject();if(str(m,"id").equals(id)){m.addProperty("status",status);if(error==null)m.remove("error");else m.addProperty("error",error);}}}
    public synchronized byte[] transfer(String peerId,String hash,String action)throws Exception{
        requireUnlocked();JsonObject f=file(hash,peerId,"in");if(f==null)throw new IllegalArgumentException("Unknown transfer");
        if(action.equals("save")){if(!str(f,"status").equals("complete"))throw new IllegalStateException("File is incomplete");return assemble(f);}
        if(action.equals("pause")||action.equals("decline")){f.addProperty("status",action.equals("pause")?"paused":"declined");vault.save();}
        else if(action.equals("accept")){
            if(!str(f,"status").equals("complete")&&transfers.add(peerId+hash)){f.addProperty("status","transferring");vault.save();workers.execute(()->download(f));}
        }else throw new IllegalArgumentException("Invalid action");return null;
    }
    private byte[] assemble(JsonObject f)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<f.get("chunks").getAsInt();i++)out.write(vault.readBlob(str(f,"hash"),i));byte[] data=out.toByteArray();if(!hash(data).equals(str(f,"hash")))throw new SecurityException("File integrity check failed");return data;
    }
    private void download(JsonObject f){
        String h=str(f,"hash"),peerId=str(f,"peer");
        try{
            JsonObject p; synchronized(this){p=peer(peerId).deepCopy();}
            String lan=endpoint(p,false);if(lan==null)throw new IOException("Use the peer's LAN address for file transfers");
            for(int i=0;i<f.get("chunks").getAsInt();i++){
                JsonObject request;
                synchronized(this){if(closed||!str(f,"status").equals("transferring"))return;if(f.getAsJsonArray("have").contains(new JsonPrimitive(i)))continue;request=seal(identity(),p,obj("kind","chunk-request","hash",h,"index",i));}
                JsonObject response=remote(lan,"mesh",obj("envelope",request,"ttl",0)).getAsJsonObject("reply");
                synchronized(this){
                    JsonObject from=response.getAsJsonObject("from");if(!str(from,"id").equals(peerId)||!str(from,"exchange").equals(str(p,"exchange")))throw new SecurityException("Invalid file sender");
                    JsonObject part=open(identity(),response);if(!str(part,"kind").equals("chunk")||!str(part,"request").equals(str(request,"id"))||!str(part,"hash").equals(h)||part.get("index").getAsInt()!=i)throw new SecurityException("Invalid chunk response");
                    byte[] data=un64(str(part,"data"));if(data.length!=Math.min(CHUNK,f.get("size").getAsInt()-i*CHUNK))throw new SecurityException("Invalid chunk length");
                    vault.writeBlob(h,i,data);f.getAsJsonArray("have").add(i);vault.save();
                }
            }
            synchronized(this){try{assemble(f);}catch(Exception e){f.add("have",new JsonArray());throw e;}f.addProperty("status","complete");f.remove("error");}
        }catch(Exception e){synchronized(this){f.addProperty("status","paused");f.addProperty("error",e.getMessage()==null?"Transfer interrupted":e.getMessage());}}
        finally{synchronized(this){transfers.remove(peerId+h);try{if(unlocked())vault.save();}catch(Exception ignored){}}}
    }
    @Override public void close(){closed=true;workers.shutdownNow();try{workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}synchronized(this){vault.close();}}
}
