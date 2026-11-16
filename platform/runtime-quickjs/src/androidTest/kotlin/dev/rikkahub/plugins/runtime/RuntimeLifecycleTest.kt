package dev.rikkahub.plugins.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.runtime.fixture.RuntimeProbeActivity
import dev.rikkahub.plugins.runtime.fixture.RuntimeProbeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLifecycleTest {
    @Test(timeout = 40_000)
    fun activityRecreationKeepsServiceStateAndProcessDeathRequiresReconstruction() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            var activity: RuntimeProbeActivity? = null
            val first = Connection(context)
            try {
                val started = first.evaluate("globalThis.count = 4; 'ready'")
                val originalPid = started.getInt("pid")
                assertNotEquals(Process.myPid(), originalPid)
                instrumentation.startActivitySync(Intent(context, RuntimeProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activity = withTimeout(5_000) { RuntimeProbeActivity.created.receive() }
                instrumentation.runOnMainSync { activity!!.recreate() }
                val recreated = withTimeout(5_000) { RuntimeProbeActivity.created.receive() }
                assertTrue(recreated !== activity)
                activity = recreated
                val afterRotation = first.evaluate("String(++count)")
                assertEquals("5", afterRotation.getString("value"))
                assertEquals(originalPid, afterRotation.getInt("pid"))
                first.killProcess()
                withTimeout(5_000) { first.disconnected.await() }
            } finally {
                first.close()
                instrumentation.runOnMainSync { activity?.finish() }
            }
            val restored = Connection(context)
            try {
                assertEquals("undefined", restored.evaluate("typeof count").getString("value"))
                assertEquals("6", restored.evaluate("globalThis.count = 5; String(++count)").getString("value"))
            } finally {
                restored.close()
            }
        }

    /** One request at a time; replies carry IDs so misrouting fails the test. */
    private class Connection(
        private val context: Context,
    ) : ServiceConnection {
        private val connected = CompletableDeferred<Messenger>()
        val disconnected = CompletableDeferred<Unit>()
        private val responses = Channel<Pair<Int, Bundle>>(Channel.UNLIMITED)
        private var nextRequest = 0
        private val receiver =
            Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    responses.trySend(message.arg1 to message.data)
                    true
                },
            )

        init {
            check(context.bindService(Intent(context, RuntimeProbeService::class.java), this, Context.BIND_AUTO_CREATE))
        }

        override fun onServiceConnected(
            name: ComponentName,
            binder: IBinder,
        ) {
            connected.complete(Messenger(binder))
        }

        override fun onServiceDisconnected(name: ComponentName) {
            disconnected.complete(Unit)
        }

        suspend fun evaluate(code: String): Bundle =
            withTimeout(5_000) {
                val id = ++nextRequest
                connected.await().send(
                    Message.obtain().apply {
                        what = 1
                        arg1 = id
                        replyTo = receiver
                        data = Bundle().apply { putString("code", code) }
                    },
                )
                val (responseId, result) = responses.receive()
                assertEquals(id, responseId)
                assertNull(result.getString("error"))
                result
            }

        suspend fun killProcess() {
            connected.await().send(Message.obtain().apply { what = 2 })
        }

        fun close() {
            context.unbindService(this)
            responses.close()
        }
    }
}
