package me.trevi.navparser.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// put thread to sleep for 1 sec for each send and try to reconnect without restarting the app in andriod studio
@Suppress("DEPRECATION")
class BluetoothDataService : Service() {
    val handlerState = 0 //used to identify handler message
    var bluetoothIn: Handler? = null
    private var btAdapter: BluetoothAdapter? = null
    private var mConnectingThread: ConnectingThread? = null
    private var mConnectedThread: ConnectedThread? = null
    private var stopThread = false
    private val recDataString = StringBuilder()

    //For Bluetooth
    private var mBinder: IBinder = LocalBinder()

    public inner class LocalBinder : Binder() {
        public fun getServerInstance(): BluetoothDataService {
            return this@BluetoothDataService
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BLUETOOTHTAG", "SERVICE CREATED")
        stopThread = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BLUETOOTHTAG", "SERVICE STARTED")
        Log.d("BLUETOOTHTAG", "${navData}")
        bluetoothIn = @SuppressLint("HandlerLeak")
        object : Handler() {
            override fun handleMessage(msg: Message) {
                Log.d("BLUETOOTHTAG","handleMessage")
                if (msg.what == handlerState) {                                     //if message is what we want
                    val readMessage = msg.obj as String // msg.arg1 = bytes from connect thread
                    recDataString.append(readMessage)
                    Log.d("BLUETOOTHTAG",recDataString.toString())
                    // Do stuff here with your data, like adding it to the database
                }
                recDataString.delete(0, recDataString.length) //clear all string data
            }
        }
        btAdapter = BluetoothAdapter.getDefaultAdapter() // get Bluetooth adapter
        checkBTState()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothIn?.removeCallbacksAndMessages(null)
        stopThread = true
        if (mConnectedThread != null) {
            mConnectedThread!!.closeStreams()
        }
        if (mConnectingThread != null) {
            mConnectingThread!!.closeSocket()
        }
        Log.d( "BLUETOOTHTAG","onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return mBinder
    }

    //Checks that the Android device Bluetooth is available and prompts to be turned on if off
    private fun checkBTState() {
        if (btAdapter == null) {
            Log.d("BLUETOOTHTAG","BLUETOOTH NOT SUPPORTED BY DEVICE, STOPPING SERVICE")
            stopSelf()
        } else {
            if (btAdapter!!.isEnabled) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return
                }
                Log.d("BLUETOOTHTAG",

                     "BT ENABLED! BT ADDRESS : ${btAdapter!!.address} BT NAME : , ${btAdapter!!.name}"
                )
                try {
                    val device = btAdapter!!.getRemoteDevice(MAC_ADDRESS)
                    Log.d("BLUETOOTHTAG","ATTEMPTING TO CONNECT TO REMOTE DEVICE :$MAC_ADDRESS")
                    mConnectingThread = ConnectingThread(device)
                    mConnectingThread!!.start()
                } catch (e: IllegalArgumentException) {
                    Log.d( "BLUETOOTHTAG","PROBLEM WITH MAC ADDRESS : $e")
                    Log.d("BLUETOOTHTAG","ILLEGAL MAC ADDRESS, STOPPING SERVICE")
                    stopSelf()
                }
            } else {
                Log.d( "BLUETOOTHTAG","BLUETOOTH NOT ON, STOPPING SERVICE")
                stopSelf()
            }
        }
    }


    // New Class for Connecting Thread
    private inner class ConnectingThread(device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket?
        private val mmDevice: BluetoothDevice

        init {
            Log.d("BLUETOOTHTAG","IN CONNECTING THREAD")
            mmDevice = device
            var temp: BluetoothSocket? = null
            Log.d("BLUETOOTHTAG","MAC ADDRESS : $MAC_ADDRESS")
            Log.d("BLUETOOTHTAG","BT UUID :$BTMODULEUUID")
            try {
                if (ActivityCompat.checkSelfPermission(
                        this@BluetoothDataService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                }
                temp = mmDevice.createRfcommSocketToServiceRecord(BTMODULEUUID)
                Log.d("BLUETOOTHTAG","SOCKET CREATED : $temp")
            } catch (e: IOException) {
                Log.d("BLUETOOTHTAG","SOCKET CREATION FAILED :$e")
                Log.d("BLUETOOTHTAG","SOCKET CREATION FAILED, STOPPING SERVICE")
                stopSelf()
            }
            mmSocket = temp
        }

        override fun run() {
            super.run()
            Log.d("BLUETOOTHTAG","IN CONNECTING THREAD RUN")
            // Establish the Bluetooth socket connection.
            // Cancelling discovery as it may slow down connection
            if (ActivityCompat.checkSelfPermission(
                    this@BluetoothDataService,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            btAdapter!!.cancelDiscovery()
            try {
                mmSocket!!.connect()
                Log.d( "BLUETOOTHTAG","BT SOCKET CONNECTED")
                mConnectedThread = ConnectedThread(mmSocket)
                mConnectedThread!!.start()
                Log.d( "BLUETOOTHTAG","CONNECTED THREAD STARTED")
                //I send a character when resuming.beginning transmission to check device is connected
                //If it is not an exception will be thrown in the write method and finish() will be called
                // mConnectedThread!!.write("x")
//                while(navData != "null"){
//                    mConnectedThread!!.write(navData)
//                    Log.d("BLUETOOTHTAG",navData)
//                }
            } catch (e: IOException) {
                try {
                    Log.d( "BLUETOOTHTAG","SOCKET CONNECTION FAILED : $e")
                    Log.d("BLUETOOTHTAG","SOCKET CONNECTION FAILED, STOPPING SERVICE")
                    mmSocket!!.close()
                    stopSelf()
                } catch (e2: IOException) {
                    Log.d("BLUETOOTHTAG","SOCKET CLOSING FAILED :$e2")
                    Log.d("BLUETOOTHTAG","SOCKET CLOSING FAILED, STOPPING SERVICE")
                    stopSelf()
                    //insert code to deal with this
                }
            } catch (e: IllegalStateException) {
                Log.d("BLUETOOTHTAG","CONNECTED THREAD START FAILED : $e")
                Log.d("BLUETOOTHTAG","CONNECTED THREAD START FAILED, STOPPING SERVICE")
                stopSelf()
            }
        }


        fun closeSocket() {
            try {
                //Don't leave Bluetooth sockets open when leaving activity
                mmSocket!!.close()
            } catch (e2: IOException) {
                //insert code to deal with this
                Log.d("BLUETOOTHTAG", e2.toString())
                Log.d("BLUETOOTHTAG","SOCKET CLOSING FAILED, STOPPING SERVICE")
                stopSelf()
            }
        }
    }

    // New Class for Connected Thread
    private inner class ConnectedThread(socket: BluetoothSocket?) : Thread() {
        private val mmInStream: InputStream?
        private val mmOutStream: OutputStream?

        //creation of the connect thread
        init {
            Log.d("BLUETOOTHTAG","IN CONNECTED THREAD")
            var tmpIn: InputStream? = null
            var tmpOut: OutputStream? = null
            try {
                //Create I/O streams for connection
                tmpIn = socket!!.inputStream
                tmpOut = socket.outputStream
            } catch (e: IOException) {
                Log.d("BLUETOOTHTAG",e.toString())
                Log.d( "BLUETOOTHTAG","UNABLE TO READ/WRITE, STOPPING SERVICE")
                stopSelf()
            }
            mmInStream = tmpIn
            mmOutStream = tmpOut
        }

        override fun run() {
            Log.d("BLUETOOTHTAG", "IN CONNECTED THREAD RUN")
//            val buffer = ByteArray(256)
//            var bytes: Int
            while(navData!="null"){
                write(navData)
                sleep(1000)
            }
            Log.d("BLUETOOTHTAG","MESSAGE STOPPED")
            // Keep looping to listen for received messages
//            while (true && !stopThread) {
//                try {
//                    bytes = mmInStream!!.read(buffer) //read bytes from input buffer
//                    val readMessage = String(buffer, 0, bytes)
//                    Log.d("BLUETOOTHTAG", "CONNECTED THREAD $readMessage")
//                    // Send the obtained bytes to the UI Activity via handler
//                    bluetoothIn?.obtainMessage(handlerState, bytes, -1, readMessage)?.sendToTarget()
//                } catch (e: IOException) {
//                    Log.d("BLUETOOTHTAG", e.toString())
//                    Log.d("BLUETOOTHTAG","UNABLE TO READ/WRITE, STOPPING SERVICE")
//                    stopSelf()
//                    break
//                }
//            }
        }

        //write method
        fun write(input: String) {
            val msgBuffer = input.toByteArray() //converts entered String into bytes
            try {
                Log.d("BLUETOOTHTAG","TRYING TO WRITE")
                Log.d("BLUETOOTHTAG",input)
                mmOutStream!!.write(msgBuffer) //write bytes over BT connection via outstream
            } catch (e: IOException) {
                //if you cannot write, close the application
                Log.d("BLUETOOTHTAG","UNABLE TO READ/WRITE $e")
                Log.d("BLUETOOTHTAG","UNABLE TO READ/WRITE, STOPPING SERVICE")
                stopSelf()
            }
        }

        fun closeStreams() {
            try {
                //Don't leave Bluetooth sockets open when leaving activity
                mmInStream!!.close()
                mmOutStream!!.close()
            } catch (e2: IOException) {
                //insert code to deal with this
                Log.d("BLUETOOTHTAG", e2.toString())
                Log.d("BLUETOOTHTAG","STREAM CLOSING FAILED, STOPPING SERVICE")
                stopSelf()
            }
        }
    }

    companion object {
        // SPP UUID service - this should work for most devices
        private val BTMODULEUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        public var navData: String = "null"

        // String for MAC address
        private const val MAC_ADDRESS = "00:22:06:01:36:B7"
    }
}