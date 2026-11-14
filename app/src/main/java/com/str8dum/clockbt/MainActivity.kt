package com.str8dum.clockbt

import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.Bundle
import android.os.ParcelUuid
import java.time.*
import java.util.*

class MainActivity : Activity() {
    private val cts = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
    private val currentTime = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
    private var gatt: BluetoothGattServer? = null
    override fun onCreate(b: Bundle?) { super.onCreate(b); startServer() }
    private fun startServer() {
        val mgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val a = getSystemService(BLUETOOTH_SERVICE) as BluetoothAdapter
        gatt = mgr.openGattServer(this, object: BluetoothGattServerCallback() {})
        val s = BluetoothGattService(cts, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        s.addCharacteristic(BluetoothGattCharacteristic(currentTime, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ))
        gatt?.addService(s)
        val adv = a.bluetoothLeAdvertiser ?: return
        adv.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW).build(), AdvertiseData.Builder().setIncludeDeviceName(true).addServiceUuid(ParcelUuid(cts)).build(), object: AdvertiseCallback(){})
    }
    override fun onDestroy(){ gatt?.close(); super.onDestroy() }
}
