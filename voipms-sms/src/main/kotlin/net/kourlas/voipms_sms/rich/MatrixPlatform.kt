package net.kourlas.voipms_sms.rich

import android.content.Context
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform
import org.matrix.rustsdk.Android

internal object MatrixPlatform {
    private var ready = false

    @Synchronized
    fun initialize(context: Context) {
        if (ready) return
        Android.init(context.applicationContext)
        initPlatform(
            TracingConfiguration(
                logLevel = LogLevel.ERROR,
                traceLogPacks = emptyList(),
                extraTargets = emptyList(),
                writeToStdoutOrSystem = false,
                writeToFiles = null,
                sentryConfig = null
            ),
            useLightweightTokioRuntime = false
        )
        ready = true
    }
}
