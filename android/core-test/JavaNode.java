import chat.mesh.core.*;
import java.nio.file.*;

public final class JavaNode {
    public static void main(String[] args)throws Exception{
        MeshNode node=new MeshNode(Paths.get(args[0]));
        Path assets=Paths.get(args[1]);
        NodeHttp http=new NodeHttp(node,name->Files.readAllBytes(assets.resolve(name)),Integer.parseInt(args[2]),Integer.parseInt(args[3]),"127.0.0.1");
        Runtime.getRuntime().addShutdownHook(new Thread(()->{http.close();node.close();}));
        System.out.println(http.url());System.out.flush();
        Thread.currentThread().join();
    }
}
