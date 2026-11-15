package com.str8dum.clockbt

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.location.*
import android.os.*
import android.util.Log
import android.widget.TextView
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class MainActivity : Activity() {
    private lateinit var label: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            label.text = "Clock USB\n\n" + ClockService.state
            if (ClockService.confirmed && !hasWindowFocus()) { handler.postDelayed(this, 500); return }
            if (ClockService.confirmed) { moveTaskToBack(true); return }
            handler.postDelayed(this, 500)
        }
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        label = TextView(this).apply { textSize=24f; setPadding(32,32,32,32); setTextColor(0xfff2a900.toInt()); setBackgroundColor(0xff101010.toInt()) }
        setContentView(label)
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),7)
        startForegroundService(Intent(this,ClockService::class.java))
    }
    override fun onResume(){super.onResume();handler.post(refresh)}
    override fun onPause(){handler.removeCallbacks(refresh);super.onPause()}
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) {
        if(i.action!=Intent.ACTION_BOOT_COMPLETED && i.action!=Intent.ACTION_MY_PACKAGE_REPLACED) return
        try { c.startForegroundService(Intent(c,ClockService::class.java));Log.i("ClockUSB","Boot/update service start requested") }
        catch(e: Exception){Log.e("ClockUSB","System rejected automatic startup",e)}
    }
}
class ClockService : Service(), LocationListener {
    companion object {@Volatile var state="Starting USB service";@Volatile var confirmed=false}
    private val action="com.str8dum.clockbt.USB_PERMISSION"
    private lateinit var usb: UsbManager
    private lateinit var gps: LocationManager
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private val events=ConcurrentLinkedQueue<Intent>()
    private var conn: UsbDeviceConnection?=null
    private var iface: UsbInterface?=null
    private var input: UsbEndpoint?=null
    private var output: UsbEndpoint?=null
    private var deviceName: String?=null
    private var requested: String?=null
    private var denied: String?=null
    private var retryAt=0L
    private var nextSend=0L
    private var sentAt=0L
    private var pending=-1L
    private var seq=0L
    private var lastAck=0L
    private var ackCount=0L
    private var line=StringBuilder()
    private var expected=""
    @Volatile private var running=true
    @Volatile private var fixAt=0L
    @Volatile private var mph=0f
    @Volatile private var gpsState="Waiting for GPS permission"
    private var gpsStarted=false
    private var gpsRetryAt=0L
    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(c:Context,i:Intent){events.add(Intent(i))}
    }
    override fun onCreate(){
        super.onCreate()
        val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("clock","Clock USB service",NotificationManager.IMPORTANCE_LOW))
        startForeground(1,Notification.Builder(this,"clock").setContentTitle("Clock USB").setContentText("Waiting for verified StamPLC link").setSmallIcon(android.R.drawable.ic_menu_recent_history).build())
        usb=getSystemService(USB_SERVICE) as UsbManager
        gps=getSystemService(LOCATION_SERVICE) as LocationManager
        val filter=IntentFilter(action).apply {addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)else registerReceiver(receiver,filter)
        thread=HandlerThread("ClockUSB").apply{start()}
        worker=Handler(thread.looper)
        worker.post(loop)
    }
    private val loop=object:Runnable{
        override fun run(){
            if(!running){closeUsb();return}
            try{updateGps()}catch(e:Exception){gpsState="GPS unavailable: "+e.javaClass.simpleName;gpsRetryAt=SystemClock.elapsedRealtime()+15000;Log.w("ClockUSB",gpsState,e)}
            try{processEvents();tick()}catch(e:Exception){Log.e("ClockUSB","USB transaction failed",e);closeUsb();state="USB error: "+e.message;retryAt=SystemClock.elapsedRealtime()+5000}
            if(running)worker.postDelayed(this,100)
        }
    }
    private fun updateGps(){
        if(gpsStarted || SystemClock.elapsedRealtime()<gpsRetryAt)return
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){gpsState="GPS permission not granted; time still available";return}
        if(!gps.isProviderEnabled(LocationManager.GPS_PROVIDER)){gpsState="GPS disabled; time still available";return}
        gps.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this,Looper.getMainLooper())
        gpsStarted=true;gpsState="Waiting for GPS fix"
    }
    override fun onLocationChanged(l:Location){
        val age=SystemClock.elapsedRealtimeNanos()-l.elapsedRealtimeNanos
        if(age<0 || age>5000000000L || !l.hasSpeed() || !l.speed.isFinite())return
        mph=(l.speed*2.2369363f).coerceIn(0f,300f);fixAt=SystemClock.elapsedRealtime();gpsState="GPS receiving"
    }
    override fun onStatusChanged(p:String?,s:Int,b:Bundle?){}
    override fun onProviderEnabled(p:String){}
    override fun onProviderDisabled(p:String){fixAt=0;gpsState="GPS disabled"}
    private fun processEvents(){
        while(true){
            val i=events.poll()?:break
            val d=i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?:continue
            if(d.vendorId!=0x303a || d.productId!=0x1001)continue
            when(i.action){
                action->{requested=null;if(!i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)){denied=d.deviceName;state="USB access denied. Reconnect StamPLC to retry."}}
                UsbManager.ACTION_USB_DEVICE_DETACHED->{if(d.deviceName==deviceName)closeUsb();if(requested==d.deviceName)requested=null;if(denied==d.deviceName)denied=null}
                UsbManager.ACTION_USB_DEVICE_ATTACHED->{denied=null;requested=null;retryAt=0}
            }
        }
    }
    private fun openUsb():Boolean {
        val matches=usb.deviceList.values.filter{it.vendorId==0x303a && it.productId==0x1001}
        if(matches.isEmpty()){state="Waiting for StamPLC USB device";return false}
        if(matches.size!=1){state="Multiple ESP32 devices: connect only StamPLC to this USB host";return false}
        val d=matches.single()
        if(!usb.hasPermission(d)){
            if(denied==d.deviceName){state="USB access denied. Reconnect StamPLC to retry.";return false}
            state="Approve Android USB access to StamPLC"
            if(requested!=d.deviceName){
                requested=d.deviceName
                val flags=PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0
                usb.requestPermission(d,PendingIntent.getBroadcast(this,1,Intent(action).setPackage(packageName),flags))
            }
            return false
        }
        var found:UsbInterface?=null
        for(n in 0 until d.interfaceCount){
            val it=d.getInterface(n)
            if(it.interfaceClass==UsbConstants.USB_CLASS_CDC_DATA)found=it
        }
        val it=found?:error("CDC data interface missing")
        var inEp:UsbEndpoint?=null;var outEp:UsbEndpoint?=null
        for(k in 0 until it.endpointCount){
            val e=it.getEndpoint(k)
            if(e.type==UsbConstants.USB_ENDPOINT_XFER_BULK){if(e.direction==UsbConstants.USB_DIR_IN)inEp=e else outEp=e}
        }
        check(inEp!=null && outEp!=null){"CDC bulk endpoints missing"}
        val c=usb.openDevice(d)?:error("Android USB open failed")
        if(!c.claimInterface(it,true)){c.close();error("CDC interface claim failed")}
        conn=c;iface=it;input=inEp;output=outEp;deviceName=d.deviceName
        // ESP32-S3 fixed USB Serial/JTAG is not a UART bridge. Avoid reset-capable
        // DTR/RTS and unnecessary line-coding requests. Use only its CDC bulk pipes.
        nextSend=0;pending=-1;lastAck=0;line.setLength(0)
        state="USB opened; waiting for StamPLC acknowledgment"
        Log.i("ClockUSB",state)
        return true
    }
    private fun tick(){
        var now=SystemClock.elapsedRealtime()
        if(now<retryAt)return
        if(conn==null && !openUsb())return
        if(!usb.deviceList.containsKey(deviceName)){closeUsb();return}
        if(pending>=0 && now-sentAt>5000)error("No controller acknowledgment; requires CLOCK protocol firmware")
        if(pending<0 && now>=nextSend){
            val t=LocalTime.now()
            val speed=if(fixAt!=0L && now-fixAt<5000)mph else 0f
            seq=(seq+1)%2147483647L
            expected=String.format(Locale.US,"%d,%02d,%02d,%d",seq,t.hour,t.minute,(speed*10).toInt())
            val bytes=("CLOCK,"+expected+"\n").toByteArray(Charsets.US_ASCII)
            var offset=0
            while(offset<bytes.size){
                val sent=conn!!.bulkTransfer(output!!,bytes,offset,bytes.size-offset,500)
                check(sent>0){"USB write failed ($sent)"}
                offset+=sent
            }
            pending=seq;sentAt=now
        }
        val buffer=ByteArray(512)
        val count=conn!!.bulkTransfer(input!!,buffer,buffer.size,100)
        if(count>0)for(k in 0 until count){
            val ch=(buffer[k].toInt() and 255).toChar()
            if(ch=='\n'){
                if(line.toString().trimEnd('\r')=="CLOCK_ACK,"+expected && pending>=0){
                    lastAck=SystemClock.elapsedRealtime();pending=-1;nextSend=lastAck+1000;confirmed=true;ackCount++
                    state="StamPLC acknowledged time/speed\nConfirmed updates: $ackCount\n$gpsState"
                    if(ackCount%10L==1L)Log.i("ClockUSB",state)
                }
                line.setLength(0)
            }else if(line.length<512)line.append(ch)else line.setLength(0)
        }
        now=SystemClock.elapsedRealtime()
        if(lastAck!=0L && now-lastAck>5000)confirmed=false
    }
    // Only the USB worker owns the connection; receivers enqueue events.
    private fun closeUsb(){
        try{iface?.let{conn?.releaseInterface(it)}}catch(_:Exception){}
        try{conn?.close()}catch(_:Exception){}
        conn=null;iface=null;input=null;output=null;deviceName=null;pending=-1;confirmed=false;line.setLength(0)
    }
    override fun onStartCommand(i:Intent?,flags:Int,id:Int)=START_STICKY
    override fun onBind(i:Intent?):IBinder?=null
    override fun onDestroy(){
        running=false
        try{unregisterReceiver(receiver)}catch(_:Exception){}
        try{gps.removeUpdates(this)}catch(_:Exception){}
        worker.removeCallbacks(loop)
        worker.post{closeUsb();thread.quitSafely()}
        super.onDestroy()
    }
}
