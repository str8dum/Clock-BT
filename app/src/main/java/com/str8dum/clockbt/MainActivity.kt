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

class MainActivity : Activity() {
    private lateinit var label: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            label.text = "Clock USB\n\n" + ClockService.state
            if (ClockService.transferring) { moveTaskToBack(true); return }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        label = TextView(this).apply { textSize = 24f; setPadding(32,32,32,32); setTextColor(0xffffb000.toInt()); setBackgroundColor(0xff101010.toInt()) }
        setContentView(label)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7)
        startForegroundService(Intent(this, ClockService::class.java))
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { c.startForegroundService(Intent(c, ClockService::class.java)) }
}

class ClockService : Service(), LocationListener {
    companion object { @Volatile var state = "Starting USB service"; @Volatile var transferring = false }
    private val permissionAction = "com.str8dum.clockbt.USB_PERMISSION"
    private lateinit var usb: UsbManager
    private lateinit var locations: LocationManager
    private var connection: UsbDeviceConnection? = null
    private var dataInterface: UsbInterface? = null
    private var output: UsbEndpoint? = null
    private var controlId = 0
    @Volatile private var running = true
    @Volatile private var mph = 0f
    @Volatile private var fixAt = 0L
    private var requested: String? = null
    private var locationStarted = false
    private var writes = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == permissionAction) {
                requested = null
                state = if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) "USB access granted; opening StamPLC" else "USB access denied. Reconnect StamPLC to retry."
            }
            if (i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) { closeUsb(); requested = null }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("clock", "Clock USB service", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this,"clock").setContentTitle("Clock USB").setContentText("Sending time and GPS speed to StamPLC").setSmallIcon(android.R.drawable.ic_menu_recent_history).build())
        usb = getSystemService(USB_SERVICE) as UsbManager
        locations = getSystemService(LOCATION_SERVICE) as LocationManager
        val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        Thread({
            while(running) {
                try { startGps(); tick() } catch(e: Exception) { state = "USB error: " + e.message; Log.e("ClockUSB", state, e); closeUsb() }
                try { Thread.sleep(1000) } catch(e: InterruptedException) { break }
            }
        }, "ClockUSB").start()
    }
    private fun startGps() {
        if (!locationStarted && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            locationStarted = true
        }
    }
    override fun onLocationChanged(l: Location) { mph = if(l.hasSpeed()) (l.speed * 2.2369363f).coerceAtLeast(0f) else 0f; fixAt = SystemClock.elapsedRealtime() }
    override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) { fixAt = 0 }
    private fun tick() {
        if(connection == null) {
            val dev = usb.deviceList.values.firstOrNull { it.vendorId == 0x303a && it.productId == 0x1001 }
            if(dev == null) { state = "Waiting for StamPLC USB device"; if (writes == 0L) Log.i("ClockUSB", state); return }
            if(!usb.hasPermission(dev)) {
                state = "Waiting for Android USB permission"
                if(requested != dev.deviceName) {
                    requested = dev.deviceName
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    usb.requestPermission(dev, PendingIntent.getBroadcast(this, 1, Intent(permissionAction).setPackage(packageName), flags))
                }
                return
            }
            var selected: UsbInterface? = null
            var ep: UsbEndpoint? = null
            for(n in 0 until dev.interfaceCount) {
                val iface = dev.getInterface(n)
                if(iface.interfaceClass == UsbConstants.USB_CLASS_COMM) controlId = iface.id
                if(iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                    for(k in 0 until iface.endpointCount) {
                        val endpoint = iface.getEndpoint(k)
                        if(endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) { selected = iface; ep = endpoint }
                    }
                }
            }
            val iface = selected ?: error("CDC data interface missing")
            val conn = usb.openDevice(dev) ?: error("Android could not open StamPLC")
            connection = conn
            if(!conn.claimInterface(iface, true)) error("Could not claim CDC data interface")
            dataInterface = iface
            output = ep ?: error("Bulk OUT endpoint missing")
            val rate = byteArrayOf(0x00,0xc2.toByte(),0x01,0x00,0x00,0x00,0x08)
            conn.controlTransfer(0x21,0x20,0,controlId,rate,rate.size,1000)
            conn.controlTransfer(0x21,0x22,1,controlId,null,0,1000)
        }
        val now = LocalTime.now()
        val speed = if(SystemClock.elapsedRealtime() - fixAt < 5000 && fixAt != 0L) mph else 0f
        val bytes = String.format(Locale.US,"TIME,%02d:%02d\nSPEED,%.1f\n",now.hour,now.minute,speed).toByteArray(Charsets.US_ASCII)
        val sent = connection!!.bulkTransfer(output!!,bytes,bytes.size,1000)
        if(sent != bytes.size) error("USB write failed ($sent of ${bytes.size} bytes)")
        transferring = true
        writes++
        state = String.format(Locale.US,"USB writes confirmed: %d\nTime %02d:%02d\nSpeed %.1f MPH\nGPS: %s",writes,now.hour,now.minute,speed,if(fixAt != 0L && SystemClock.elapsedRealtime()-fixAt < 5000) "receiving" else "waiting for fix")
        if(writes % 10L == 1L) Log.i("ClockUSB",state)
    }
    @Synchronized private fun closeUsb() {
        try { dataInterface?.let { connection?.releaseInterface(it) }; connection?.close() } catch(_: Exception) {}
        transferring = false
        connection = null; dataInterface = null; output = null
    }
    override fun onStartCommand(i: Intent?, flags: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?) : IBinder? = null
    override fun onDestroy() { running = false; closeUsb(); try { unregisterReceiver(receiver); locations.removeUpdates(this) } catch(_: Exception) {}; super.onDestroy() }
}
