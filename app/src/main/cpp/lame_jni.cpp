#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <vector>

#if defined(HAVE_LAME) && HAVE_LAME == 1
#include "lame.h"
#endif

#define LOG_TAG "FaintRecLameJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_converter_LameNativeBridge_nativeIsLameCompiled(JNIEnv*, jclass) {
#if defined(HAVE_LAME) && HAVE_LAME == 1
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_example_converter_LameNativeBridge_nativeInit(
        JNIEnv*,
        jclass,
        jint inSampleRate,
        jint numChannels,
        jint outSampleRate,
        jint bitrateKbps,
        jint vbrQuality,
        jboolean useVbr) {
#if defined(HAVE_LAME) && HAVE_LAME == 1
    lame_global_flags* glf = lame_init();
    if (!glf) {
        LOGE("lame_init failed");
        return 0L;
    }
    lame_set_in_samplerate(glf, inSampleRate);
    lame_set_num_channels(glf, numChannels);
    lame_set_out_samplerate(glf, outSampleRate);
    lame_set_mode(glf, numChannels == 1 ? MONO : JOINT_STEREO);
    if (useVbr) {
        lame_set_VBR(glf, vbr_default);
        lame_set_VBR_q(glf, vbrQuality);
    } else {
        lame_set_VBR(glf, vbr_off);
        lame_set_brate(glf, bitrateKbps);
    }
    lame_set_quality(glf, 2);
    if (lame_init_params(glf) < 0) {
        LOGE("lame_init_params failed");
        lame_close(glf);
        return 0L;
    }
    return reinterpret_cast<jlong>(glf);
#else
    (void)inSampleRate;
    (void)numChannels;
    (void)outSampleRate;
    (void)bitrateKbps;
    (void)vbrQuality;
    (void)useVbr;
    return 0L;
#endif
}

JNIEXPORT jint JNICALL
Java_com_example_converter_LameNativeBridge_nativeEncodeInterleaved(
        JNIEnv* env,
        jclass,
        jlong handle,
        jshortArray pcmInterleaved,
        jint numSamplesPerChannel,
        jint numChannels,
        jbyteArray mp3Buf) {
#if defined(HAVE_LAME) && HAVE_LAME == 1
    auto* glf = reinterpret_cast<lame_global_flags*>(handle);
    if (!glf) return -1;

    jshort* pcm = env->GetShortArrayElements(pcmInterleaved, nullptr);
    jbyte* mp3 = env->GetByteArrayElements(mp3Buf, nullptr);
    jsize mp3BufSize = env->GetArrayLength(mp3Buf);

    int encodedBytes = 0;
    if (numChannels == 1) {
        encodedBytes = lame_encode_buffer(
                glf,
                pcm,
                pcm,
                numSamplesPerChannel,
                reinterpret_cast<unsigned char*>(mp3),
                mp3BufSize);
    } else {
        encodedBytes = lame_encode_buffer_interleaved(
                glf,
                pcm,
                numSamplesPerChannel,
                reinterpret_cast<unsigned char*>(mp3),
                mp3BufSize);
    }

    env->ReleaseShortArrayElements(pcmInterleaved, pcm, JNI_ABORT);
    env->ReleaseByteArrayElements(mp3Buf, mp3, 0);
    return encodedBytes;
#else
    (void)env; (void)handle; (void)pcmInterleaved; (void)numSamplesPerChannel;
    (void)numChannels; (void)mp3Buf;
    return -1;
#endif
}

JNIEXPORT jint JNICALL
Java_com_example_converter_LameNativeBridge_nativeFlush(
        JNIEnv* env,
        jclass,
        jlong handle,
        jbyteArray mp3Buf) {
#if defined(HAVE_LAME) && HAVE_LAME == 1
    auto* glf = reinterpret_cast<lame_global_flags*>(handle);
    if (!glf) return -1;

    jbyte* mp3 = env->GetByteArrayElements(mp3Buf, nullptr);
    jsize mp3BufSize = env->GetArrayLength(mp3Buf);
    int flushed = lame_encode_flush(
            glf,
            reinterpret_cast<unsigned char*>(mp3),
            mp3BufSize);
    env->ReleaseByteArrayElements(mp3Buf, mp3, 0);
    return flushed;
#else
    (void)env; (void)handle; (void)mp3Buf;
    return -1;
#endif
}

JNIEXPORT void JNICALL
Java_com_example_converter_LameNativeBridge_nativeClose(
        JNIEnv*,
        jclass,
        jlong handle) {
#if defined(HAVE_LAME) && HAVE_LAME == 1
    auto* glf = reinterpret_cast<lame_global_flags*>(handle);
    if (glf) {
        lame_close(glf);
    }
#else
    (void)handle;
#endif
}

} // extern "C"
