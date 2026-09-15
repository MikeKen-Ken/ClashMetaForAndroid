#include <jni.h>
#include "libclash.h"
#include "jni_helper.h"
#include "trace.h"

JNIEXPORT jstring JNICALL
Java_com_github_kr328_clash_core_bridge_Bridge_nativeQueryNetworkDiagnostics(JNIEnv *env, jobject thiz) {
    TRACE_METHOD();
    scoped_string response = queryNetworkDiagnostics();
    return new_string(response);
}
