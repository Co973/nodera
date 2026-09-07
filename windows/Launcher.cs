using System;
using System.Collections;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
[assembly: AssemblyTitle("Mesh Chat")]
[assembly: AssemblyDescription("Mesh Chat desktop launcher")]
[assembly: AssemblyCompany("Mesh Chat")]
[assembly: AssemblyProduct("Mesh Chat")]
[assembly: AssemblyVersion("0.2.1.0")]
[assembly: AssemblyFileVersion("0.2.1.0")]

internal static class Launcher {
    private static Process node;
    private static IntPtr job;
    private static string url, instance, stateDirectory;
    private static bool stopping;
    private static readonly object logLock = new object();
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode)] private static extern IntPtr CreateJobObject(IntPtr attributes,string name);
    [DllImport("kernel32.dll")] private static extern bool SetInformationJobObject(IntPtr job,int info,IntPtr data,uint length);
    [DllImport("kernel32.dll")] private static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
    [StructLayout(LayoutKind.Sequential)] private struct BasicLimits { public long ProcessTime,JobTime;public uint Flags;public UIntPtr MinWorkingSet,MaxWorkingSet;public uint ActiveProcesses;public UIntPtr Affinity;public uint Priority,Scheduling; }
    [StructLayout(LayoutKind.Sequential)] private struct IoCounters { public ulong ReadOperations,WriteOperations,OtherOperations,ReadBytes,WriteBytes,OtherBytes; }
    [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits { public BasicLimits Basic;public IoCounters Io;public UIntPtr ProcessMemory,JobMemory,PeakProcessMemory,PeakJobMemory; }

    [STAThread] private static int Main(string[] args) {
        bool smoke = args.Length==2 && args[0]=="--smoke-test";
        Application.EnableVisualStyles();
        stateDirectory = Environment.GetEnvironmentVariable("MESH_DATA_DIR");
        if(String.IsNullOrEmpty(stateDirectory)) stateDirectory=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Mesh Chat");
        stateDirectory=Path.GetFullPath(stateDirectory);
        Directory.CreateDirectory(stateDirectory);
        bool created;
        using(Mutex mutex=new Mutex(true,smoke?"Local\\MeshChatSmoke-"+Guid.NewGuid():"Local\\MeshChatDesktop",out created)) {
            if(!created) { OpenExisting(); return 0; }
            try {
                string root=AppDomain.CurrentDomain.BaseDirectory;
                TcpListener reservation=new TcpListener(IPAddress.Loopback,0);reservation.Start();int port=((IPEndPoint)reservation.LocalEndpoint).Port;reservation.Stop();
                instance=Guid.NewGuid().ToString("N");url="http://127.0.0.1:"+port;
                NormalizeEnvironment();
                ProcessStartInfo start=new ProcessStartInfo(Path.Combine(root,"runtime","node.exe"),"\""+Path.Combine(root,"src","server.js")+"\"");
                start.WorkingDirectory=root;start.UseShellExecute=false;start.CreateNoWindow=true;start.RedirectStandardOutput=true;start.RedirectStandardError=true;
                start.EnvironmentVariables["PORT"]=port.ToString();
                start.EnvironmentVariables["MESH_PORT"]=Environment.GetEnvironmentVariable("MESH_TEST_PORT")??"4341";
                start.EnvironmentVariables["DATA_DIR"]=Path.Combine(stateDirectory,"data");
                start.EnvironmentVariables["MESH_INSTANCE_ID"]=instance;
                if(smoke)start.EnvironmentVariables["MESH_HOST"]="127.0.0.1";
                node=new Process();node.StartInfo=start;node.EnableRaisingEvents=true;
                node.OutputDataReceived+=(s,e)=>Log(e.Data);node.ErrorDataReceived+=(s,e)=>Log(e.Data);
                node.Start();AttachJob(node);node.BeginOutputReadLine();node.BeginErrorReadLine();
                bool ready=false;
                for(int n=0;n<100;n++){if(node.HasExited)throw new Exception("The local node could not start. Another application may be using mesh port 4341. See launcher.log in "+stateDirectory);if(IsReady(url,instance)){ready=true;break;}Thread.Sleep(100);}
                if(!ready)throw new Exception("The local node did not become ready. See launcher.log in "+stateDirectory);
                File.WriteAllLines(Path.Combine(stateDirectory,"running.txt"),new string[]{url,instance});
                if(smoke){File.WriteAllText(args[1],"Packaged runtime started; authenticated readiness check passed.\n"+url);return 0;}
                OpenBrowser();
                using(NotifyIcon tray=new NotifyIcon()) {
                    tray.Icon=Icon.ExtractAssociatedIcon(Assembly.GetExecutingAssembly().Location);tray.Text="Mesh Chat · local node running";
                    ContextMenuStrip menu=new ContextMenuStrip();menu.Items.Add("Open Mesh",null,(s,e)=>OpenBrowser());menu.Items.Add("Quit and lock",null,(s,e)=>Application.Exit());tray.ContextMenuStrip=menu;tray.DoubleClick+=(s,e)=>OpenBrowser();tray.Visible=true;
                    System.Windows.Forms.Timer monitor=new System.Windows.Forms.Timer();monitor.Interval=2000;monitor.Tick+=(s,e)=>{if(!stopping&&node.HasExited){monitor.Stop();MessageBox.Show("The Mesh node stopped. Reopen Mesh to restart it.","Mesh Chat");Application.Exit();}};monitor.Start();Application.Run();monitor.Dispose();tray.Visible=false;menu.Dispose();
                }
                return 0;
            } catch(Exception e) { Log(e.ToString());if(smoke)File.WriteAllText(args[1],"FAILED: "+e.Message);else MessageBox.Show(e.Message,"Mesh Chat",MessageBoxButtons.OK,MessageBoxIcon.Error);return 1; }
            finally { stopping=true;if(job!=IntPtr.Zero){CloseHandle(job);job=IntPtr.Zero;}if(node!=null){try{if(!node.HasExited)node.Kill();node.WaitForExit(3000);}catch{}node.Dispose();}try{File.Delete(Path.Combine(stateDirectory,"running.txt"));}catch{}mutex.ReleaseMutex(); }
        }
    }
    private static void Log(string value){if(value==null)return;lock(logLock){try{string file=Path.Combine(stateDirectory,"launcher.log");if(File.Exists(file)&&new FileInfo(file).Length>262144)File.WriteAllText(file,"");File.AppendAllText(file,DateTime.UtcNow.ToString("s")+" "+value+Environment.NewLine);}catch{}}}
    // Some parent processes supply both Path and PATH. Framework's child-process
    // environment dictionary rejects these duplicate Windows variable names.
    private static void NormalizeEnvironment(){
        Dictionary<string,string> values=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
        List<string> duplicates=new List<string>();
        foreach(DictionaryEntry entry in Environment.GetEnvironmentVariables()){
            string key=(string)entry.Key;
            if(values.ContainsKey(key))duplicates.Add(key);
            else values.Add(key,(string)entry.Value);
        }
        foreach(string key in duplicates){
            string value=values[key];
            while(Environment.GetEnvironmentVariable(key)!=null)Environment.SetEnvironmentVariable(key,null);
            Environment.SetEnvironmentVariable(key,value);
        }
    }
    private static bool IsReady(string target,string expected){try{HttpWebRequest request=(HttpWebRequest)WebRequest.Create(target+"/api/session");request.Proxy=null;request.Timeout=500;using(WebResponse response=request.GetResponse())using(StreamReader reader=new StreamReader(response.GetResponseStream()))return reader.ReadToEnd().Contains("\"instance\":\""+expected+"\"");}catch{return false;}}
    private static void OpenBrowser(){Process.Start(new ProcessStartInfo(url){UseShellExecute=true});}
    private static void OpenExisting(){for(int n=0;n<30;n++){try{string[] info=File.ReadAllLines(Path.Combine(stateDirectory,"running.txt"));if(info.Length==2&&Regex.IsMatch(info[0],@"^http://127\.0\.0\.1:[0-9]{1,5}$")&&IsReady(info[0],info[1])){Process.Start(new ProcessStartInfo(info[0]){UseShellExecute=true});return;}}catch{}Thread.Sleep(100);}MessageBox.Show("Mesh is already starting. Try opening it again shortly.","Mesh Chat");}
    private static void AttachJob(Process process){job=CreateJobObject(IntPtr.Zero,null);if(job==IntPtr.Zero)throw new Win32Exception();ExtendedLimits limits=new ExtendedLimits();limits.Basic.Flags=0x2000;int size=Marshal.SizeOf(typeof(ExtendedLimits));IntPtr buffer=Marshal.AllocHGlobal(size);try{Marshal.StructureToPtr(limits,buffer,false);if(!SetInformationJobObject(job,9,buffer,(uint)size)||!AssignProcessToJobObject(job,process.Handle))throw new Win32Exception();}finally{Marshal.FreeHGlobal(buffer);}}
}
