package com.siliconleap.app.managed

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.siliconleap.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class ManagedRuntimeService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    override fun onCreate(){super.onCreate();getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("managed-runtime","DSHM独立预览",NotificationManager.IMPORTANCE_LOW));startForeground(17,notification("预览服务启动中"));scope.launch {ManagedRuntimeManager.status.collect {getSystemService(NotificationManager::class.java).notify(17,notification(it.take(100)))}}}
    private fun notification(message: String)=NotificationCompat.Builder(this,"managed-runtime").setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("DSHM Linux 0.1.7 预览").setContentText(message).setOngoing(true).setContentIntent(PendingIntent.getActivity(this,17,Intent(this,ManagedRuntimeActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).addAction(0,"停止",PendingIntent.getService(this,18,Intent(this,ManagedRuntimeService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {if(intent?.action==STOP){ManagedRuntimeManager.stop(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}else ManagedRuntimeManager.start(this,true);return START_NOT_STICKY}
    override fun onDestroy(){ManagedRuntimeManager.stop(this);scope.cancel();super.onDestroy()}
    override fun onBind(intent: Intent?): IBinder?=null
    companion object {
        private const val STOP="com.siliconleap.app.managed.STOP"
        fun start(context: Context)=ContextCompat.startForegroundService(context,Intent(context,ManagedRuntimeService::class.java))
        fun stop(context: Context){ManagedRuntimeManager.stop(context);context.stopService(Intent(context,ManagedRuntimeService::class.java))}
    }
}
