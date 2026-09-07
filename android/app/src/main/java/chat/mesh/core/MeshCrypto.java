package chat.mesh.core;

import com.google.gson.*;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import static chat.mesh.core.Json.*;

/** Interoperability implementation of the experimental desktop sealed-envelope protocol. */
public final class MeshCrypto {
    private static final SecureRandom RANDOM=new SecureRandom();
    public static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static String text(byte[] b){return new String(b,StandardCharsets.UTF_8);}
    public static byte[] random(int size){byte[] b=new byte[size];RANDOM.nextBytes(b);return b;}
    public static String b64(byte[] b){return Base64.getEncoder().encodeToString(b);}
    public static byte[] un64(String s){return Base64.getDecoder().decode(s);}
    public static String hash(byte[] b)throws Exception {byte[] digest=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte v:digest)s.append(String.format("%02x",v&255));return s.toString();}
    private static String pem(String type,byte[] value){return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(value)+"\n-----END "+type+"-----\n";}
    private static byte[] der(String value){return un64(value.replaceAll("-----[^-]+-----","").replaceAll("\\s",""));}
    private static AsymmetricKeyParameter key(String value,boolean privateKey)throws Exception{return privateKey?PrivateKeyFactory.createKey(der(value)):PublicKeyFactory.createKey(der(value));}
    private static String pub(AsymmetricKeyParameter key)throws Exception{return pem("PUBLIC KEY",SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(key).getEncoded());}
    private static String priv(AsymmetricKeyParameter key)throws Exception{return pem("PRIVATE KEY",PrivateKeyInfoFactory.createPrivateKeyInfo(key).getEncoded());}
    private static String sign(String privateKey,JsonObject value)throws Exception{Ed25519Signer s=new Ed25519Signer();s.init(true,key(privateKey,true));byte[] b=bytes(stringify(value));s.update(b,0,b.length);return b64(s.generateSignature());}
    private static void verify(String publicKey,JsonObject value,String signature)throws Exception{Ed25519Signer s=new Ed25519Signer();s.init(false,key(publicKey,false));byte[] b=bytes(stringify(value));s.update(b,0,b.length);if(!s.verifySignature(un64(signature)))throw new SecurityException("Invalid signature");}
    public static JsonObject identity(String name)throws Exception{
        Ed25519PrivateKeyParameters ed=new Ed25519PrivateKeyParameters(RANDOM);X25519PrivateKeyParameters x=new X25519PrivateKeyParameters(RANDOM);
        return obj("name",name,"signing",pub(ed.generatePublicKey()),"exchange",pub(x.generatePublicKey()),"signingPrivate",priv(ed),"exchangePrivate",priv(x));
    }
    public static JsonObject card(JsonObject identity)throws Exception{
        JsonObject card=obj("name",str(identity,"name"),"signing",str(identity,"signing"),"exchange",str(identity,"exchange"));
        String signature=sign(str(identity,"signingPrivate"),card);
        card.addProperty("id",hash(bytes(str(identity,"signing"))));card.addProperty("signature",signature);return card;
    }
    public static void verifyCard(JsonObject card)throws Exception{
        if(str(card,"name").length()>60||!str(card,"id").equals(hash(bytes(str(card,"signing")))))throw new SecurityException("Invalid identity");
        if(!(key(str(card,"exchange"),false) instanceof X25519PublicKeyParameters)||!(key(str(card,"signing"),false) instanceof Ed25519PublicKeyParameters))throw new SecurityException("Invalid key types");
        verify(str(card,"signing"),obj("name",str(card,"name"),"signing",str(card,"signing"),"exchange",str(card,"exchange")),str(card,"signature"));
    }
    public static JsonObject encrypt(byte[] key,byte[] plain,String aad)throws Exception{
        byte[] nonce=random(12);ChaCha20Poly1305 cipher=new ChaCha20Poly1305();cipher.init(true,new AEADParameters(new KeyParameter(key),128,nonce,bytes(aad)));
        byte[] out=new byte[cipher.getOutputSize(plain.length)];int size=cipher.processBytes(plain,0,plain.length,out,0);size+=cipher.doFinal(out,size);
        return obj("nonce",b64(nonce),"data",b64(Arrays.copyOf(out,size-16)),"tag",b64(Arrays.copyOfRange(out,size-16,size)));
    }
    public static byte[] decrypt(byte[] key,JsonObject box,String aad)throws Exception{
        byte[] data=un64(str(box,"data")),tag=un64(str(box,"tag"));if(tag.length!=16)throw new SecurityException("Invalid tag");byte[] input=Arrays.copyOf(data,data.length+16);System.arraycopy(tag,0,input,data.length,16);
        ChaCha20Poly1305 cipher=new ChaCha20Poly1305();cipher.init(false,new AEADParameters(new KeyParameter(key),128,un64(str(box,"nonce")),bytes(aad)));
        byte[] out=new byte[cipher.getOutputSize(input.length)];int size=cipher.processBytes(input,0,input.length,out,0);size+=cipher.doFinal(out,size);return Arrays.copyOf(out,size);
    }
    private static byte[] shared(String privateKey,String publicKey,String context)throws Exception{
        byte[] secret=new byte[32];((X25519PrivateKeyParameters)key(privateKey,true)).generateSecret((X25519PublicKeyParameters)key(publicKey,false),secret,0);
        HKDFBytesGenerator hkdf=new HKDFBytesGenerator(new SHA256Digest());hkdf.init(new HKDFParameters(secret,new byte[0],bytes(context)));byte[] result=new byte[32];hkdf.generateBytes(result,0,32);Arrays.fill(secret,(byte)0);return result;
    }
    public static JsonObject seal(JsonObject sender,JsonObject recipient,JsonObject payload)throws Exception{
        X25519PrivateKeyParameters ephemeral=new X25519PrivateKeyParameters(RANDOM);
        JsonObject header=obj("version",1,"id",UUID.randomUUID().toString(),"from",card(sender),"to",str(recipient,"id"),"ephemeral",pub(ephemeral.generatePublicKey()),"expires",System.currentTimeMillis()+72*3600000L);
        String aad=stringify(header);JsonObject body=header.deepCopy();body.add("box",encrypt(shared(priv(ephemeral),str(recipient,"exchange"),aad),bytes(stringify(payload)),aad));
        String signature=sign(str(sender,"signingPrivate"),body);body.addProperty("signature",signature);return body;
    }
    public static JsonObject verifyEnvelope(JsonObject envelope)throws Exception{
        JsonObject body=envelope.deepCopy();String signature=str(body,"signature");body.remove("signature");JsonObject from=body.getAsJsonObject("from");verifyCard(from);
        long expires=body.get("expires").getAsLong(),now=System.currentTimeMillis();
        if(body.get("version").getAsInt()!=1||!str(body,"to").matches("[a-f0-9]{64}")||str(body,"id").length()>64||expires<now||expires>now+73*3600000L)throw new SecurityException("Invalid or expired envelope");
        verify(str(from,"signing"),body,signature);body.remove("box");return body;
    }
    public static JsonObject open(JsonObject receiver,JsonObject envelope)throws Exception{
        JsonObject header=verifyEnvelope(envelope);if(!str(header,"to").equals(str(card(receiver),"id")))throw new SecurityException("Wrong recipient");
        String aad=stringify(header);return object(text(decrypt(shared(str(receiver,"exchangePrivate"),str(header,"ephemeral"),aad),envelope.getAsJsonObject("box"),aad)));
    }
}
