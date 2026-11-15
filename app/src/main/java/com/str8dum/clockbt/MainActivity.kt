package com.str8dum.clockbt

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.location.*
import android.os.Bundle
import android.os.ParcelUuid
import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.*
import java.util.*

class MainActivity : Activity() {
    private val cts = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
    private val currentTime = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
    private val clockBtService = UUID.fromString("7b6f0001-7a4d-4c6f-9a13-4254436c6f63")
    private val speedUuid = UUID.fromString("7b6f0002-7a4d-4c6f-9a13-4254436c6f63")
    private var gatt: BluetoothGattServer? = null
    @Volatile private var speedKmh = 0f
    private lateinit var status: TextView
    private var usbConnection: UsbDeviceConnection? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        status = TextView(this).apply {
            text = "Clock BT\nStarting Bluetooth time and GPS service..."
            textSize = 24f
            setPadding(40, 40, 40, 40)
        }
        setContentView(status)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7)
        }
        try {
            startLocation()
            startServer()
            startUsbSerial()
            status.text = "Clock BT\nRunning\nTime and GPS speed available to StamPLC"
        } catch (e: SecurityException) {
            status.text = "Clock BT\nUSB serial permission required"
        } catch (e: Exception) {
            status.text = "Clock BT\nUSB serial unavailable: ${e.javaClass.simpleName}"
        }
    }

    private fun startLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, object : LocationListener {
                override fun onLocationChanged(l: Location) { speedKmh = (l.speed * 3.6f).coerceAtLeast(0f) }
            })
    }

    private fun ctsValue(): ByteArray {
        val n = ZonedDateTime.now()
        return byteArrayOf((n.year and 0xff).toByte(), ((n.year ushr 8) and 0xff).toByte(),
            n.monthValue.toByte(), n.dayOfMonth.toByte(), n.hour.toByte(), n.minute.toByte(),
            n.second.toByte(), n.dayOfWeek.value.toByte(), 0, 0)
    }

    private fun speedValue(): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(speedKmh).array()

    private fun startServer() {
        val mgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val a = getSystemService(BLUETOOTH_SERVICE) as BluetoothAdapter
        val cb = object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(d: BluetoothDevice, id: Int, offset: Int, c: BluetoothGattCharacteristic) {
                when (c.uuid) {
                    currentTime -> gatt?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, offset, ctsValue())
                    speedUuid -> gatt?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, offset, speedValue())
                    else -> gatt?.sendResponse(d, id, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                }
            }
        }
        gatt = mgr.openGattServer(this, cb) ?: error("GATT server unavailable")
        val timeService = BluetoothGattService(cts, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        timeService.addCharacteristic(BluetoothGattCharacteristic(currentTime, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        gatt?.addService(timeService)
        val speedService = BluetoothGattService(clockBtService, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        speedService.addCharacteristic(BluetoothGattCharacteristic(speedUuid, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ))
        gatt?.addService(speedService)
        val adv = a.bluetoothLeAdvertiser ?: error("BLE advertiser unavailable")
        adv.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW).build(),
            AdvertiseData.Builder().setIncludeDeviceName(true).addServiceUuid(ParcelUuid(cts)).addServiceUuid(ParcelUuid(clockBtService)).build(),
            object : AdvertiseCallback() {})
    }

    private fun startUsbSerial() {
        val um = getSystemService(USB_SERVICE) as UsbManager
        val dev = um.deviceList.values.firstOrNull() ?: return
        if (!um.hasPermission(dev)) { status.text = "Clock BT\nWaiting for USB permission"; return }
        val iface = dev.getInterface(0)
        val out = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull { it.direction == 0 }
        usbConnection = um.openDevice(dev)
        if (usbConnection != null && out != null) {
            Thread {
                while (!isFinishing) {
                    val n = ZonedDateTime.now()
                    val msg = "TIME,%02d:%02d\\nSPEED,%.1f\\n".format(n.hour, n.minute, speedKmh * 0.621371f)
                    usbConnection?.bulkTransfer(out, msg.toByteArray(), msg.length, 1000)
                    Thread.sleep(10000)
                }
            }.start()
        }
    }

    override fun onDestroy() { gatt?.close(); super.onDestroy() }
}
