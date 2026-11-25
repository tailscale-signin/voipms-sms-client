package net.kourlas.voipms_sms.utils

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** A document provider can stream to a pipe, support only "w", or offer no read access. */
internal object DocumentOutput {
    class Unavailable : IOException()
    class Incomplete : IOException()

    /** Returns true after readback matches. False means "wt" closed successfully but the destination was unreadable. */
    fun write(
        openOutput: (String) -> OutputStream?,
        openInput: () -> InputStream?,
        writeContent: (OutputStream) -> Unit,
        verifying: () -> Unit,
        checkActive: () -> Unit
    ): Boolean {
        var truncateRequested = true
        fun fallback(): OutputStream? {
            truncateRequested = false
            return openOutput("w")
        }
        checkActive()
        val destination = try {
            openOutput("wt")
        } catch (_: FileNotFoundException) {
            fallback()
        } catch (_: IllegalArgumentException) {
            fallback()
        } catch (_: UnsupportedOperationException) {
            fallback()
        } ?: throw Unavailable()

        val sent = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val countingStream = object : DigestOutputStream(destination, sent) {
            override fun write(value: Int) {
                super.write(value)
                written++
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                written += length
            }
        }
        countingStream.use { stream ->
            writeContent(stream)
            stream.flush()
        }
        checkActive()
        verifying()
        val readback = try {
            openInput()
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
        if (readback == null) {
            // "w" is allowed to leave old trailing bytes. A write-only provider must honor "wt".
            if (!truncateRequested) throw Unavailable()
            return false
        }
        val received = MessageDigest.getInstance("SHA-256")
        var seen = 0L
        readback.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val n = it.read(buffer)
                if (n < 0) break
                seen += n
                if (seen > written) throw Incomplete()
                received.update(buffer, 0, n)
            }
        }
        if (seen != written || !MessageDigest.isEqual(sent.digest(), received.digest())) throw Incomplete()
        return true
    }
}
