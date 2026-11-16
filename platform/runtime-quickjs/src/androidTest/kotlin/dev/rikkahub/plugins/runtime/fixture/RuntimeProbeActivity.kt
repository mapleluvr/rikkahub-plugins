package dev.rikkahub.plugins.runtime.fixture

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.channels.Channel

class RuntimeProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        created.trySend(this)
    }

    companion object {
        val created = Channel<RuntimeProbeActivity>(Channel.UNLIMITED)
    }
}
