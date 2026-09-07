package chat.mesh.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.Arrays;
import org.bouncycastle.crypto.generators.SCrypt;
import static chat.mesh.core.Json.*;
import static chat.mesh.core.MeshCrypto.*;

public final class MeshVault implements AutoCloseable {
    public final Path directory;
    private final Path file;
    private byte[] key,salt;
    public JsonObject state;
    public MeshVault(Path directory)throws Exception{this.directory=directory;Files.createDirectories(directory);file=directory.resolve("vault.json");}
    public boolean exists(){return Files.exists(file);}
    public synchronized void unlock(String password,String name)throws Exception{
        if(password.length()<12)throw new IllegalArgumentException("Use at least 12 characters for your passphrase");
        JsonObject disk=exists()?object(text(Files.readAllBytes(file))):null;
        byte[] newSalt=disk==null?random(16):un64(str(disk,"salt"));byte[] newKey=SCrypt.generate(bytes(password),newSalt,16384,8,1,32);
        JsonObject loaded;
        try {loaded=disk==null?obj("identity",identity(name),"peers",new JsonArray(),"messages",new JsonArray(),"outbox",new JsonArray(),"seen",new JsonArray(),"files",new JsonArray()):object(text(decrypt(newKey,disk.getAsJsonObject("box"),"mesh-vault-v1")));}
        catch(Exception e){Arrays.fill(newKey,(byte)0);throw new SecurityException("Unable to unlock vault: check your passphrase");}
        key=newKey;salt=newSalt;state=loaded;save();
    }
    public synchronized void save()throws Exception{atomic(file,bytes(stringify(obj("salt",b64(salt),"box",encrypt(key,bytes(stringify(state)),"mesh-vault-v1")))));}
    public synchronized void writeBlob(String hash,int index,byte[] data)throws Exception{atomic(chunk(hash,index),bytes(stringify(encrypt(key,data,hash+":"+index))));}
    public synchronized byte[] readBlob(String hash,int index)throws Exception{return decrypt(key,object(text(Files.readAllBytes(chunk(hash,index)))),hash+":"+index);}
    private Path chunk(String hash,int index)throws Exception{if(!hash.matches("[a-f0-9]{64}")||index<0||index>134)throw new IllegalArgumentException("Invalid chunk");Path dir=directory.resolve("blobs");Files.createDirectories(dir);return dir.resolve(hash+"."+index);}
    public static void atomic(Path file,byte[] data)throws Exception{Path temp=file.resolveSibling(file.getFileName()+".tmp");Files.write(temp,data);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}
    @Override public synchronized void close(){if(key!=null)Arrays.fill(key,(byte)0);key=null;state=null;}
}
