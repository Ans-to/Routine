package app.routine.privilege.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.routine.privilege.adb.discovery.PairingReceiverEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-establishes Routine's internal Wireless Debugging ADB Bridge after a device reboot
 * when `WRITE_SECURE_SETTINGS` was previously self-granted.
 */
class AdbBridgeBootReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val engine = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PairingReceiverEntryPoint::class.java,
        ).adbBridgeEngine()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                engine.reconnectAfterBoot()
            } finally {
                pending?.finish()
            }
        }
    }
}
