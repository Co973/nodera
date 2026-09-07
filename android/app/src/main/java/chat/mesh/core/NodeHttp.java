package chat.mesh.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static chat.mesh.core.Json.*;
import static chat.mesh.core.MeshCrypto.*;

/** A bounded HTTP/1.1 adapter shared by the Android service and JVM integration tests. */
public final class NodeHttp implements AutoCloseable {
    public interface Assets { byte[] read(String name)throws Exception; }
    private final MeshNode node;private final Assets assets;
    private final ServerSocket ui,mesh;
    private final ThreadPoolExecutor requests=new ThreadPoolExecutor(2,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32));
    private final Map<String,long[]> rates=new HashMap<>();
    private final String token=b64(random(32));
    private volatile boolean closed;
    public NodeHttp(MeshNode node,Assets assets,int uiPort,int meshPort,String meshHost)throws Exception{
        this.node=node;this.assets=assets;ui=new ServerSocket(uiPort,50,InetAddress.getByName("127.0.0.1"));
        ServerSocket peerSocket;try{peerSocket=new ServerSocket(meshPort,50,InetAddress.getByName(meshHost));}catch(Exception e){ui.close();throw e;}mesh=peerSocket;
        listen(ui,true);listen(mesh,false);
    }
    public int uiPort(){return ui.getLocalPort();}public int meshPort(){return mesh.getLocalPort();}
    public String url(){return "http://127.0.0.1:"+uiPort();}
    private void listen(ServerSocket server,boolean management){Thread thread=new Thread(()->{while(!closed){try{Socket s=server.accept();s.setSoTimeout(15000);try{requests.execute(()->handle(s,management));}catch(RejectedExecutionException e){s.close();}}catch(IOException e){if(!closed)close();}}},"mesh-http-accept");thread.setDaemon(true);thread.start();}
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();int b;while((b=in.read())!=-1){if(b==10)break;if(out.size()>8192)throw new IOException("Header too long");if(b!=13)out.write(b);}if(b==-1&&out.size()==0)return null;return new String(out.toByteArray(),StandardCharsets.US_ASCII);}
    private void handle(Socket socket,boolean management){
        try(Socket s=socket){InputStream in=new BufferedInputStream(s.getInputStream());OutputStream out=s.getOutputStream();
            try{
                String request=line(in);if(request==null)return;String[] parts=request.split(" ");if(parts.length!=3)throw new IOException("Invalid request");String method=parts[0],path=parts[1];
                Map<String,String> headers=new HashMap<>();String header;int count=0;while((header=line(in))!=null&&!header.isEmpty()){if(++count>50)throw new IOException("Too many headers");int split=header.indexOf(':');if(split<1)throw new IOException("Invalid header");String key=header.substring(0,split).toLowerCase(Locale.ROOT);if(headers.put(key,header.substring(split+1).trim())!=null)throw new IOException("Duplicate header");}
                if(headers.containsKey("transfer-encoding"))throw new IOException("Use Content-Length framing");
                int length=Integer.parseInt(headers.getOrDefault("content-length","0"));int limit=management&&path.equals("/api/file")?36*1024*1024:1024*1024;
                if(length<0||length>limit)throw new IOException("Request too large");
                if(management){
                    if(!("127.0.0.1:"+uiPort()).equals(headers.get("host"))||headers.containsKey("origin")&&!url().equals(headers.get("origin"))){respond(out,403,obj("error","Invalid origin or host"));return;}
                    if(method.equals("GET")&&(path.equals("/")||path.equals("/app.js")||path.equals("/style.css"))){String name=path.equals("/")?"index.html":path.substring(1);respond(out,200,name.endsWith("html")?"text/html; charset=utf-8":name.endsWith("css")?"text/css":"text/javascript",assets.read(name));return;}
                    if(method.equals("GET")&&path.equals("/api/session")){respond(out,200,obj("token",token,"locked",!node.unlocked(),"exists",node.vault.exists()));return;}
                    if(!token.equals(headers.get("x-mesh-token"))){respond(out,403,obj("error","Invalid session"));return;}
                }else{
                    if(!node.unlocked()){respond(out,503,obj("error","Node locked"));return;}
                    String ip=s.getInetAddress().getHostAddress();long now=System.currentTimeMillis();synchronized(rates){rates.entrySet().removeIf(e->now-e.getValue()[0]>60000);long[] r=rates.get(ip);if(r==null){if(rates.size()>1000)throw new IOException("Peer capacity reached");r=new long[]{now,0};rates.put(ip,r);}if(++r[1]>240){respond(out,429,obj("error","Rate limited"));return;}}
                }
                byte[] data=new byte[length];int offset=0;while(offset<length){int n=in.read(data,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
                JsonObject body=length==0?new JsonObject():object(text(data));
                if(!management){
                    if(method.equals("GET")&&path.equals("/identity")){respond(out,200,node.publicIdentity());return;}
                    if(method.equals("POST")&&path.equals("/mesh")){int ttl=body.get("ttl").getAsInt();if(body.get("ttl").getAsDouble()!=ttl)throw new IllegalArgumentException("Invalid TTL");respond(out,200,obj("reply",node.route(body.getAsJsonObject("envelope"),ttl,false)));return;}
                }else{
                    if(method.equals("POST")&&path.equals("/api/unlock")){node.unlock(str(body,"password"),body.has("name")?str(body,"name"):"Explorer");respond(out,200,obj("ok",true));return;}
                    if(!node.unlocked()){respond(out,423,obj("error","Unlock your node first"));return;}
                    if(method.equals("GET")&&path.equals("/api/state")){respond(out,200,node.snapshot(meshPort()));return;}
                    if(method.equals("POST")){
                        switch(path){
                            case "/api/peer":node.addPeer(str(body,"address"));break;
                            case "/api/verify":node.verifyPeer(str(body,"peer"),body.get("verified").getAsBoolean());break;
                            case "/api/send":node.send(str(body,"peer"),str(body,"text"));break;
                            case "/api/file":node.offer(str(body,"peer"),str(body,"name"),un64(str(body,"data")));break;
                            case "/api/transfer":byte[] file=node.transfer(str(body,"peer"),str(body,"hash"),str(body,"action"));if(file!=null){respond(out,200,"application/octet-stream",file);return;}break;
                            default:respond(out,404,obj("error","Not found"));return;
                        }
                        respond(out,200,obj("ok",true));return;
                    }
                }
                respond(out,404,obj("error","Not found"));
            }catch(Exception e){respond(out,400,obj("error",e.getMessage()==null?"Invalid request":e.getMessage()));}
        }catch(IOException ignored){}
    }
    private void respond(OutputStream out,int status,JsonObject body)throws IOException{respond(out,status,"application/json; charset=utf-8",bytes(stringify(body)));}
    private void respond(OutputStream out,int status,String type,byte[] body)throws IOException{
        String headers="HTTP/1.1 "+status+" Response\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n\r\n";
        out.write(bytes(headers));out.write(body);out.flush();
    }
    @Override public void close(){closed=true;try{ui.close();}catch(IOException ignored){}try{mesh.close();}catch(IOException ignored){}requests.shutdownNow();}
}
