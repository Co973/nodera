package chat.mesh.android;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int NEARBY=10,PICK_FILE=11,SAVE_FILE=12;
    private final String[] bluetoothPermissions={Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE};
    private WebView web;private TextView status;private MeshService service;private boolean bound;
    private ValueCallback<Uri[]> fileCallback;private byte[] pendingExport;private String localUrl;
    private final java.util.concurrent.ExecutorService background=Executors.newSingleThreadExecutor();
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){service=((MeshService.LocalBinder)binder).service();if(service.error!=null){status.setText(service.error);return;}localUrl=service.http.url();web.loadUrl(localUrl);status.setText("LAN ready · Bluetooth off");}
        @Override public void onServiceDisconnected(ComponentName name){service=null;status.setText("Node stopped. Reopen Mesh to restart.");}
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(Color.rgb(244,245,239));
        page.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(12,4,12,4);
        status=new TextView(this);status.setText("Starting your local node…");status.setTextSize(11);status.setTextColor(Color.rgb(49,92,65));toolbar.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        Button nearby=new Button(this);nearby.setText("Nearby");nearby.setOnClickListener(v->requestNearby());toolbar.addView(nearby);
        Button info=new Button(this);info.setText("Node");info.setOnClickListener(v->showNode());toolbar.addView(info);page.addView(toolbar);
        web=new WebView(this);web.setBackgroundColor(Color.rgb(244,245,239));WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);CookieManager.getInstance().setAcceptCookie(false);
        web.addJavascriptInterface(new Downloads(),"MeshAndroid");
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return localUrl==null||!request.getUrl().toString().startsWith(localUrl+"/");}});
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,PICK_FILE);return true;}
        });
        page.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);
        Intent intent=new Intent(this,MeshService.class);startForegroundService(intent);bound=bindService(intent,connection,BIND_AUTO_CREATE);
    }
    private boolean bluetoothAllowed(){for(String permission:bluetoothPermissions)if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)return false;return true;}
    private void requestNearby(){
        if(service==null||service.node==null||!service.node.unlocked()){message("Unlock your node first.");return;}
        if(!bluetoothAllowed()){requestPermissions(bluetoothPermissions,NEARBY);return;}
        final List<String> addresses=new ArrayList<>(),labels=new ArrayList<>();
        final ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nearby Mesh Android nodes").setAdapter(adapter,(d,index)->{
            String address=addresses.get(index);status.setText("Connecting to nearby node…");background.execute(()->{try{service.node.addPeer("ble://"+address);runOnUiThread(()->{status.setText("Bluetooth ready");message("Peer added. They must also add you. Compare fingerprints before trusting the connection.");});}catch(Exception e){runOnUiThread(()->message(e.getMessage()));}});
        }).setNegativeButton("Done",null).create();
        service.bluetooth.startDiscovery(new BleMeshTransport.Listener(){
            @Override public void status(String value){runOnUiThread(()->status.setText(value));}
            @Override public void peer(String address){runOnUiThread(()->{if(!addresses.contains(address)){addresses.add(address);labels.add("Nearby node "+addresses.size()+" · tap to connect");adapter.notifyDataSetChanged();}});}
        });dialog.show();
    }
    private void showNode(){
        if(service==null||service.http==null){message("Node is not running.");return;}
        String details="Android preview 0.2.0\n\n";
        try{if(service.node.unlocked())details+="LAN addresses:\n"+service.node.snapshot(service.http.meshPort()).get("addresses").toString()+"\n\n";}catch(Exception ignored){}
        details+="Add each other's LAN address or use Nearby on two Android phones. Files use LAN; Bluetooth carries encrypted text. Keep the Mesh notification running to receive messages.\n\nExperimental encryption: no Noise sessions or forward secrecy yet.";
        new AlertDialog.Builder(this).setTitle("Your Mesh node").setMessage(details).setPositiveButton("Close",null).setNegativeButton("Stop and lock",(d,w)->{stopService(new Intent(this,MeshService.class));finish();}).show();
    }
    private void message(String value){new AlertDialog.Builder(this).setMessage(value==null?"Operation failed":value).setPositiveButton("OK",null).show();}
    public final class Downloads {
        @JavascriptInterface public void saveFile(String name,String base64){
            if(base64.length()>36*1024*1024)return;
            final byte[] data;try{data=android.util.Base64.decode(base64,android.util.Base64.DEFAULT);}catch(Exception e){return;}if(data.length>25*1024*1024)return;
            runOnUiThread(()->{if(pendingExport!=null){message("Finish saving the current file first.");return;}pendingExport=data;String filename=name.replaceAll("[\\\\/\\p{Cntrl}]","_");if(filename.length()>200)filename=filename.substring(0,200);startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,filename),SAVE_FILE);});
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==NEARBY){if(bluetoothAllowed())requestNearby();else message("Nearby devices permission is required for Bluetooth. LAN chat still works.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==PICK_FILE&&fileCallback!=null){fileCallback.onReceiveValue(result==RESULT_OK&&data!=null?new Uri[]{data.getData()}:null);fileCallback=null;}
        if(request==SAVE_FILE){byte[] bytes=pendingExport;pendingExport=null;if(result==RESULT_OK&&data!=null&&bytes!=null){Uri target=data.getData();background.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(target)){if(out==null)throw new java.io.IOException("Unable to open destination");out.write(bytes);runOnUiThread(()->Toast.makeText(this,"File saved",Toast.LENGTH_SHORT).show());}catch(Exception e){runOnUiThread(()->message(e.getMessage()));}});}}
    }
    @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){if(fileCallback!=null)fileCallback.onReceiveValue(null);if(bound)unbindService(connection);web.removeJavascriptInterface("MeshAndroid");web.destroy();background.shutdownNow();super.onDestroy();}
}
