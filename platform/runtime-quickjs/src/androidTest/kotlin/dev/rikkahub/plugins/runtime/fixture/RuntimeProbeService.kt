package dev.rikkahub.plugins.runtime.fixture

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import dev.rikkahub.plugins.runtime.PluginRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Test APK only: exercises the engine under an isolated Android UID through Binder. */
class RuntimeProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtime = PluginRuntime { request -> "host:$request" }
    private val incoming =
        Messenger(
            Handler(Looper.getMainLooper()) { message ->
                val code = message.data.getString("code")
                val reply = message.replyTo
                val requestId = message.arg1
                if (message.what == 2) {
                    Process.killProcess(Process.myPid())
                } else {
                    scope.launch {
                        val result = runCatching { runtime.evaluate(requireNotNull(code)) }
                        reply.send(
                            Message.obtain().apply {
                                arg1 = requestId
                                data =
                                    Bundle().apply {
                                        putString("value", result.getOrNull())
                                        putString("error", result.exceptionOrNull()?.toString())
                                        putInt("pid", Process.myPid())
                                    }
                            },
                        )
                    }
                }
                true
            },
        )

    override fun onBind(intent: Intent): IBinder = incoming.binder

    override fun onDestroy() {
        scope.launch {
            runtime.close()
            scope.cancel()
        }
        super.onDestroy()
    }
}
