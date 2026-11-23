// Dex Hub TV (DEXHUB.md): the bridge to the Usenet engine library.
//
// libnuviousenet.so is NuvioTV's Usenet engine built as a library (script.dexhub.usenet's
// lib/libnuviousenet-android-<arch>.so, GPL-3.0), packed into the APK by the build. It exports
// three C calls, not JNI ones (source/nuvio-usenet-lib/main.go):
//   int  NuvioUsenetStart(const char *json)   the start record; the port, or < 0
//   int  NuvioUsenetPort(void)                the port while it runs, else 0
//   void NuvioUsenetStop(void)                closes every session and the listener
// This library loads it on first use and hands those calls to UsenetEngine.kt.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "DexHubUsenet"

typedef int (*start_fn)(const char *);
typedef int (*port_fn)(void);
typedef void (*stop_fn)(void);

static void *engine;
static start_fn engine_start;
static port_fn engine_port;
static stop_fn engine_stop;

static int load(void) {
    if (engine_start && engine_port && engine_stop) return 1;
    if (!engine) engine = dlopen("libnuviousenet.so", RTLD_NOW);
    if (!engine) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "engine not loaded: %s", dlerror());
        return 0;
    }
    engine_start = (start_fn) dlsym(engine, "NuvioUsenetStart");
    engine_port = (port_fn) dlsym(engine, "NuvioUsenetPort");
    engine_stop = (stop_fn) dlsym(engine, "NuvioUsenetStop");
    return engine_start && engine_port && engine_stop;
}

JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_dexhub_usenet_UsenetEngine_nativeAvailable(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nuvio_tv_dexhub_usenet_UsenetEngine_nativeStart(JNIEnv *env, jclass cls, jstring json) {
    (void) cls;
    if (!load()) return -100;
    const char *record = (*env)->GetStringUTFChars(env, json, NULL);
    if (!record) return -101;
    int port = engine_start(record);
    (*env)->ReleaseStringUTFChars(env, json, record);
    return port;
}

JNIEXPORT jint JNICALL
Java_com_nuvio_tv_dexhub_usenet_UsenetEngine_nativePort(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return load() ? engine_port() : 0;
}

JNIEXPORT void JNICALL
Java_com_nuvio_tv_dexhub_usenet_UsenetEngine_nativeStop(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    if (load()) engine_stop();
}
