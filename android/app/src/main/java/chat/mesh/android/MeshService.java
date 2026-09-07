package chat.mesh.android;
import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import chat.mesh.core.*;

public final class MeshService extends Service {
    public final class LocalBinder extends Binder { public MeshService service(){return MeshService.this;} }
    private final LocalBinder binder=new LocalBinder();
    public MeshNode node;public NodeHttp http;public BleMeshTransport bluetooth;public String error;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("mesh-node","Mesh node",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MeshService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,"mesh-node").setSmallIcon(chat.mesh.android.R.drawable.ic_mesh).setContentTitle("Mesh node is running").setContentText("Local messaging stays active. Tap Stop to lock and disconnect.").setContentIntent(open).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(1,notification);
        try{
            node=new MeshNode(getNoBackupFilesDir().toPath());
            http=new NodeHttp(node,name->{try(java.io.InputStream in=getAssets().open(name)){return MeshNode.readLimited(in,2*1024*1024);}},0,4311,"0.0.0.0");
            bluetooth=new BleMeshTransport(this,node);node.radio=bluetooth;
        }catch(Exception e){error=e.getMessage();}
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"STOP".equals(intent.getAction()))stopSelf();return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public void onDestroy(){if(bluetooth!=null)bluetooth.close();if(http!=null)http.close();if(node!=null)node.close();super.onDestroy();}
}
