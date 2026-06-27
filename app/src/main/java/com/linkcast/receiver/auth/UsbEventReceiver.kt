package com.linkcast.receiver.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.linkcast.receiver.ProjectionService

class UsbEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            LocalMfiAuthProvider.ACTION_USB_PERMISSION -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "USB MFI permission result granted=$granted")
                if (granted) ProjectionService.connect(context)
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.i(TAG, "USB device attached; probing local MFI auth")
                ProjectionService.connect(context)
            }
        }
    }

    private companion object {
        private const val TAG = "UsbEventReceiver"
    }
}
