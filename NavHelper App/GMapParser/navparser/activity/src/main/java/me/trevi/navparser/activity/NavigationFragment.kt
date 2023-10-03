package me.trevi.navparser.activity

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import me.trevi.navparser.activity.databinding.FragmentNavigationBinding
import me.trevi.navparser.lib.NavigationData
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

class NavigationFragment : Fragment() {

    private var _binding: FragmentNavigationBinding? = null
    private val binding get() = _binding!!
    private val navDataModel: NavigationDataModel by activityViewModels()

//    // For bluetooth
//    private lateinit var mService: BluetoothSDKService

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNavigationBinding.inflate(inflater, container, false)

//        bindBluetoothService()

        // Register Listener
//        BluetoothSDKListenerHelper.registerBluetoothSDKListener(requireContext(), mBluetoothListener)
        return _binding!!.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.stopNavButton.setOnClickListener {
            (activity as NavParserActivity).stopNavigation()
        }

        setNavigationData(NavigationData(true))
        navDataModel.liveData.observe(viewLifecycleOwner) { setNavigationData(it) }
    }

    override fun onStart() {
        super.onStart()
        (activity as NavParserActivity).hideMissingDataSnackbar()
    }

    override fun onStop() {
        (activity as NavParserActivity).stopServiceListener()
        super.onStop()
    }

    private fun setNavigationData(navData: NavigationData) {
        binding.navigationDest.text = getString(
            if (navData.isRerouting)
                R.string.recalculating_navigation
            else
                R.string.navigate_to
        ).format(navData.finalDirection)

        binding.nextDirection.text = navData.nextDirection.localeString
        binding.nextAction.text = navData.nextDirection.navigationDistance?.localeString

        binding.eta.text = getString(R.string.eta).format(
            if (navData.eta.time != null) {
                DateFormat.getTimeFormat(activity?.applicationContext).format(
                    Date.from(
                        LocalDateTime.of(LocalDate.now(), navData.eta.time).atZone(
                            ZoneId.systemDefault()
                        ).toInstant()
                    )
                )
            } else {
                navData.eta.localeString
            },
            navData.eta.duration?.localeString
        )

        binding.distance.text = getString(R.string.distance).format(navData.remainingDistance.localeString)
        binding.stopNavButton.isEnabled = navData.canStop
        binding.actionDirection.setImageBitmap(navData.actionIcon.bitmap)
    }
//
//    /**
//     * Bind Bluetooth Service
//     */
//    private fun bindBluetoothService() {
//        // Bind to LocalService
//        Intent(
//            requireActivity().applicationContext,
//            BluetoothSDKService::class.java
//        ).also { intent ->
//            requireActivity().applicationContext.bindService(
//                intent,
//                connection,
//                Context.BIND_AUTO_CREATE
//            )
//        }
//    }
//
//    /**
//     * Handle service connection
//     */
//    private val connection = object : ServiceConnection {
//
//        override fun onServiceConnected(className: ComponentName, service: IBinder) {
//            val binder = service as BluetoothSDKService.LocalBinder
//            mService = binder.getService()
//        }
//
//        override fun onServiceDisconnected(arg0: ComponentName) {
//
//        }
//    }
//
//    private val mBluetoothListener: IBluetoothSDKListener = object : IBluetoothSDKListener {
//        override fun onDiscoveryStarted() {
//        }
//
//        override fun onDiscoveryStopped() {
//        }
//
//        override fun onDeviceDiscovered(device: BluetoothDevice?) {
//        }
//
//        override fun onDeviceConnected(device: BluetoothDevice?) {
//            // Do stuff when is connected
//            Log.i("BLUETAG","HC 05 Connected")
//        }
//
//        override fun onMessageReceived(device: BluetoothDevice?, message: String?) {
//        }
//
//        override fun onMessageSent(device: BluetoothDevice?) {
//        }
//
//        override fun onError(message: String?) {
//        }
//
//        override fun onDeviceDisconnected() {
//            TODO("Not yet implemented")
//            Log.i("BLUETAG","HC 05 Disconnected")
//        }
//
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        // Unregister Listener
//        BluetoothSDKListenerHelper.unregisterBluetoothSDKListener(requireContext(), mBluetoothListener)
//    }
}
