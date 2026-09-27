/*
 * Emitted runtime of native-obfuscator.
 *
 * Licensed under GPL-3.0 WITH an Output Exception: you may link, embed,
 * compile and distribute this file in your own programs under terms of
 * your choice. See the "Output Exception" section in the project's
 * LICENSE for the full terms.
 *
 * https://github.com/radioegor146/native-obfuscator#licensing
 */

#include "jni.h"

// Fix https://developercommunity.visualstudio.com/t/Access-violation-in-_Thrd_yield-after-up/10664660#T-N10668856
// Thanks, microsoft!
#define _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR

#include <cmath>
#include <cstring>
#include <string>
#include <cstdio>
#include <unordered_set>
#include <mutex>
#include <atomic>
#include <initializer_list>

#ifndef NATIVE_JVM_HPP_GUARD

#define NATIVE_JVM_HPP_GUARD

namespace native_jvm::utils {

    struct defining_binding;

    void init_utils(JNIEnv *env);

    void throw_re(JNIEnv *env, const char *exception_class, const char *error, int line);

    jobjectArray create_multidim_array(JNIEnv *env, jobject classloader, jint count, jint required_count,
        const char *class_name, int line, std::initializer_list<jint> sizes, int dim_index = 0,
        jobject receiver = nullptr, jobject *receiver_loader = nullptr,
        std::atomic<defining_binding *> *defining_head = nullptr);

    template <int sort>
    jarray create_array_value(JNIEnv* env, jint size);

    template <int sort>
    jarray create_multidim_array_value(JNIEnv *env, jint count, jint required_count,
        const char *name, int line, std::initializer_list<jint> sizes, int dim_index = 0) {
        if (required_count == 0) {
            env->FatalError("required_count == 0");
            return nullptr;
        }
        jint current_size = sizes.begin()[dim_index];
        if (current_size < 0) {
            throw_re(env, "java/lang/NegativeArraySizeException", "MULTIANEWARRAY size < 0", line);
            return nullptr;
        }
        if (count == 1) {
            return create_array_value<sort>(env, current_size);
        }
        jobjectArray result_array = nullptr;
        if (jclass clazz = env->FindClass((std::string(count - 1, '[') + std::string(name)).c_str())) {
            result_array = env->NewObjectArray(current_size, clazz, nullptr);
            if (env->ExceptionCheck()) {
                return nullptr;
            }
            env->DeleteLocalRef(clazz);
        }
        else
            return nullptr;

        if (required_count == 1) {
            return result_array;
        }

        for (jint i = 0; i < current_size; i++) {
            jarray inner_array = create_multidim_array_value<sort>(env, count - 1, required_count - 1,
                name, line, sizes, dim_index + 1);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(result_array);
                return nullptr;
            }
            env->SetObjectArrayElement(result_array, i, inner_array);
            env->DeleteLocalRef(inner_array);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(result_array);
                return nullptr;
            }
        }
        return result_array;
    }

#ifdef USE_HOTSPOT
    jobject link_call_site(JNIEnv *env, jobject caller_obj, jobject bootstrap_method_obj,
            jobject name_obj, jobject type_obj, jobject static_arguments, jobject appendix_result);
#endif

    jclass find_class_wo_static(JNIEnv *env, jobject classloader, jstring class_name);

    jclass find_class_fallback(JNIEnv *env, jobject classloader, jstring class_name, jobject receiver, jobject *receiver_loader);

    jclass resolve_class(JNIEnv *env, std::atomic<defining_binding *> *defining_head, jobject classloader,
        jstring class_name, jobject receiver, jobject *receiver_loader, bool *cacheable);

    jclass get_class_from_object(JNIEnv *env, jobject object);

    jobject get_classloader_from_class(JNIEnv *env, jclass clazz);

    jobject get_lookup(JNIEnv *env, jclass clazz);

    void bastore(JNIEnv *env, jarray array, jint index, jint value);
    jbyte baload(JNIEnv *env, jarray array, jint index);

    void clear_refs(JNIEnv *env, std::unordered_set<jobject> &refs);

    jstring get_interned(JNIEnv *env, jstring value);

    jboolean string_equals(JNIEnv *env, jstring a, jobject b);

    struct defining_binding {
        jclass clazz;
        jobject loader;
        std::atomic<defining_binding *> next;
    };

    inline defining_binding *single_defining_binding(std::atomic<defining_binding *> *head) {
        defining_binding *current = head->load(std::memory_order_acquire);
        if (current == nullptr || current->next.load(std::memory_order_acquire) != nullptr) {
            return nullptr;
        }
        return current;
    }

    void publish_defining_binding(JNIEnv *env, std::atomic<defining_binding *> *head, std::mutex *mtx, jclass clazz);
}

#endif