package net.kourlas.voipms_sms.rich

import org.matrix.rustcomponents.sdk.ClientBuilder

/** Set in memory by the emulator instrumentation for its disposable loopback homeserver. */
@Volatile internal var matrixLoopbackTestCa: ByteArray? = null
internal fun ClientBuilder.configureTrust(host: String): ClientBuilder {
    val ca = matrixLoopbackTestCa
    return if (host == "localhost" && ca != null) addRootCertificates(listOf(ca)) else this
}
