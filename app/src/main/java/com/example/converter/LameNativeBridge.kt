package com.example.converter

/**
 * JNI bridge to native libfaintrec_lame.so (compiled via app/src/main/cpp/CMakeLists.txt
 * and app/src/main/cpp/lame_jni.cpp against LAME 3.100 C sources).
 */
object LameNativeBridge {

    private var libraryLoaded: Boolean = false

    init {
        libraryLoaded = try {
            System.loadLibrary("faintrec_lame")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    val isAvailable: Boolean
        get() = libraryLoaded && try {
            nativeIsLameCompiled()
        } catch (_: UnsatisfiedLinkError) {
            false
        }

    val unavailableReason: String
        get() = when {
            !libraryLoaded -> "Native LAME library (libfaintrec_lame.so) not bundled in this build. Build with Android NDK + LAME 3.100 sources in app/src/main/cpp/lame/."
            else -> "JNI stub built without LAME C sources. Place LAME 3.100 sources in app/src/main/cpp/lame/ and rebuild."
        }

    @JvmStatic
    external fun nativeIsLameCompiled(): Boolean

    @JvmStatic
    external fun nativeInit(
        inSampleRate: Int,
        numChannels: Int,
        outSampleRate: Int,
        bitrateKbps: Int,
        vbrQuality: Int,
        useVbr: Boolean
    ): Long

    @JvmStatic
    external fun nativeEncodeInterleaved(
        handle: Long,
        pcmInterleaved: ShortArray,
        numSamplesPerChannel: Int,
        numChannels: Int,
        mp3Buf: ByteArray
    ): Int

    @JvmStatic
    external fun nativeFlush(handle: Long, mp3Buf: ByteArray): Int

    @JvmStatic
    external fun nativeClose(handle: Long)
}
