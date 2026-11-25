package org.matrix.rustsdk

import android.content.Context
import androidx.annotation.Keep

/** JNI entry point exported by libmatrix_sdk_ffi for Android certificate verification. */
@Keep
internal object Android {
    init {
        System.loadLibrary("matrix_sdk_ffi")
    }

    @JvmStatic
    external fun init(context: Context)
}
