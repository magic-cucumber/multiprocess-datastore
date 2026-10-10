#pragma once

#include <jni.h>

// Valid between successful JNI_OnLoad and logger shutdown in JNI_OnUnload.
JavaVM* native_bridge_get_vm();
jclass native_bridge_get_logger_class();
jmethodID native_bridge_get_logger_method();
