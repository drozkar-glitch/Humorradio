package cz.taximares.humorradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        RadioController.init(context.applicationContext)
        RadioController.onPreAlarmTriggered(context.applicationContext)
    }
}
