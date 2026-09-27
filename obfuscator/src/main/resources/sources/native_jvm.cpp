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

#include "native_jvm.hpp"
#include <algorithm>

namespace native_jvm::utils {

    jclass boolean_array_class;
    jmethodID string_intern_method;
    jclass class_class;
    jmethodID get_classloader_method;
    jclass object_class;
    jmethodID get_class_method;
    jclass classloader_class;
    jmethodID load_class_method;
    jclass no_class_def_found_class;
    jmethodID ncdf_init_method;
    jclass class_not_found_class;
    jclass throwable_class;
    jmethodID get_message_method;
    jmethodID init_cause_method;
    jmethodID get_cause_method;
    jclass methodhandles_lookup_class;
    jmethodID lookup_init_method;
#ifdef USE_HOTSPOT
    jclass methodhandle_natives_class;
    jmethodID link_call_site_method;
    bool is_jvm11_link_call_site;
#endif

    void init_utils(JNIEnv *env) {
        jclass clazz = env->FindClass("[Z");
        if (env->ExceptionCheck())
            return;
        boolean_array_class = (jclass) env->NewGlobalRef(clazz);
        env->DeleteLocalRef(clazz);

        jclass string_clazz = env->FindClass("java/lang/String");
        if (env->ExceptionCheck())
            return;
        string_intern_method = env->GetMethodID(string_clazz, "intern", "()Ljava/lang/String;");
        if (env->ExceptionCheck())
            return;
        env->DeleteLocalRef(string_clazz);

        jclass _class_class = env->FindClass("java/lang/Class");
        if (env->ExceptionCheck())
            return;
        class_class = (jclass) env->NewGlobalRef(_class_class);
        env->DeleteLocalRef(_class_class);

        get_classloader_method = env->GetMethodID(class_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
        if (env->ExceptionCheck())
            return;

        jclass _object_class = env->FindClass("java/lang/Object");
        if (env->ExceptionCheck())
            return;
        object_class = (jclass) env->NewGlobalRef(_object_class);
        env->DeleteLocalRef(_object_class);

        get_class_method = env->GetMethodID(object_class, "getClass", "()Ljava/lang/Class;");
        if (env->ExceptionCheck())
            return;

        jclass _classloader_class = env->FindClass("java/lang/ClassLoader");
        if (env->ExceptionCheck())
            return;
        classloader_class = (jclass) env->NewGlobalRef(_classloader_class);
        env->DeleteLocalRef(_classloader_class);

        load_class_method = env->GetMethodID(classloader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (env->ExceptionCheck())
            return;

        jclass _no_class_def_found_class = env->FindClass("java/lang/NoClassDefFoundError");
        if (env->ExceptionCheck())
            return;
        no_class_def_found_class = (jclass) env->NewGlobalRef(_no_class_def_found_class);
        env->DeleteLocalRef(_no_class_def_found_class);

        ncdf_init_method = env->GetMethodID(no_class_def_found_class, "<init>", "(Ljava/lang/String;)V");
        if (env->ExceptionCheck())
            return;

        jclass _throwable_class = env->FindClass("java/lang/Throwable");
        if (env->ExceptionCheck())
            return;
        throwable_class = (jclass) env->NewGlobalRef(_throwable_class);
        env->DeleteLocalRef(_throwable_class);

        get_message_method = env->GetMethodID(throwable_class, "getMessage", "()Ljava/lang/String;");
        if (env->ExceptionCheck())
            return;

        init_cause_method = env->GetMethodID(throwable_class, "initCause",
                                            "(Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        if (env->ExceptionCheck())
            return;

        get_cause_method = env->GetMethodID(throwable_class, "getCause", "()Ljava/lang/Throwable;");
        if (env->ExceptionCheck())
            return;

        jclass _class_not_found_class = env->FindClass("java/lang/ClassNotFoundException");
        if (env->ExceptionCheck())
            return;
        class_not_found_class = (jclass) env->NewGlobalRef(_class_not_found_class);
        env->DeleteLocalRef(_class_not_found_class);

        jclass _methodhandles_lookup_class = env->FindClass("java/lang/invoke/MethodHandles$Lookup");
        if (env->ExceptionCheck())
            return;
        methodhandles_lookup_class = (jclass) env->NewGlobalRef(_methodhandles_lookup_class);
        env->DeleteLocalRef(_methodhandles_lookup_class);

        lookup_init_method = env->GetMethodID(methodhandles_lookup_class, "<init>", "(Ljava/lang/Class;)V");
        if (env->ExceptionCheck())
            return;

#ifdef USE_HOTSPOT
        jclass _methodhandle_natives_class = env->FindClass("java/lang/invoke/MethodHandleNatives");
        if (env->ExceptionCheck())
            return;
        methodhandle_natives_class = (jclass) env->NewGlobalRef(_methodhandle_natives_class);
        env->DeleteLocalRef(_methodhandle_natives_class);

        link_call_site_method = env->GetStaticMethodID(methodhandle_natives_class, "linkCallSite",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/invoke/MemberName;");
        is_jvm11_link_call_site = false;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            link_call_site_method = env->GetStaticMethodID(methodhandle_natives_class, "linkCallSite",
                "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/invoke/MemberName;");
            is_jvm11_link_call_site = true;
            if (env->ExceptionCheck())
                return;
        }
#endif
    }

#ifdef USE_HOTSPOT
    jobject link_call_site(JNIEnv *env, jobject caller_obj, jobject bootstrap_method_obj,
        jobject name_obj, jobject type_obj, jobject static_arguments, jobject appendix_result) {
        if (is_jvm11_link_call_site) {
            return env->CallStaticObjectMethod(methodhandle_natives_class, link_call_site_method, caller_obj, 0,
                bootstrap_method_obj, name_obj, type_obj, static_arguments, appendix_result);
        }
        return env->CallStaticObjectMethod(methodhandle_natives_class, link_call_site_method, caller_obj,
            bootstrap_method_obj, name_obj, type_obj, static_arguments, appendix_result);
    }
#endif

    template <>
    jarray create_array_value<1>(JNIEnv *env, jint size) {
        return env->NewBooleanArray(size);
    }

    template <>
    jarray create_array_value<2>(JNIEnv *env, jint size) {
        return env->NewCharArray(size);
    }

    template <>
    jarray create_array_value<3>(JNIEnv *env, jint size) {
        return env->NewByteArray(size);
    }

    template <>
    jarray create_array_value<4>(JNIEnv *env, jint size) {
        return env->NewShortArray(size);
    }

    template <>
    jarray create_array_value<5>(JNIEnv *env, jint size) {
        return env->NewIntArray(size);
    }

    template <>
    jarray create_array_value<6>(JNIEnv *env, jint size) {
        return env->NewFloatArray(size);
    }

    template <>
    jarray create_array_value<7>(JNIEnv *env, jint size) {
        return env->NewLongArray(size);
    }

    template <>
    jarray create_array_value<8>(JNIEnv *env, jint size) {
        return env->NewDoubleArray(size);
    }

    jobjectArray create_multidim_array(JNIEnv *env, jobject classloader, jint count, jint required_count,
        const char *class_name, int line, std::initializer_list<jint> sizes, int dim_index,
        jobject receiver, jobject *receiver_loader, std::atomic<defining_binding *> *defining_head) {
        if (required_count == 0) {
            env->FatalError("required_count == 0");
            return nullptr;
        }
        jint current_size = sizes.begin()[dim_index];
        if (current_size < 0) {
            throw_re(env, "java/lang/NegativeArraySizeException", "MULTIANEWARRAY size < 0", line);
            return nullptr;
        }
        jobjectArray result_array = nullptr;
        if (count == 1) {
            std::string renamed_class_name(class_name);
            std::replace(renamed_class_name.begin(), renamed_class_name.end(), '/', '.');
            jstring renamed_class_name_string = env->NewStringUTF(renamed_class_name.c_str());
            jclass clazz = resolve_class(env, defining_head, classloader, renamed_class_name_string, receiver, receiver_loader, nullptr);
            env->DeleteLocalRef(renamed_class_name_string);
            if (clazz == nullptr || env->ExceptionCheck()) {
                if (clazz != nullptr) {
                    env->DeleteLocalRef(clazz);
                }
                return nullptr;
            }
            result_array = env->NewObjectArray(current_size, clazz, nullptr);
            env->DeleteLocalRef(clazz);
            if (result_array == nullptr || env->ExceptionCheck()) {
                return nullptr;
            }
            return result_array;
        }
        std::string renamed_class_name(class_name);
        std::replace(renamed_class_name.begin(), renamed_class_name.end(), '/', '.');
        jstring renamed_class_name_string = env->NewStringUTF(renamed_class_name.c_str());
        jclass component = resolve_class(env, defining_head, classloader, renamed_class_name_string, receiver, receiver_loader, nullptr);
        env->DeleteLocalRef(renamed_class_name_string);
        if (component == nullptr || env->ExceptionCheck()) {
            return nullptr;
        }
        jclass clazz = component;
        for (jint dim = 1; dim < count; dim++) {
            jobjectArray probe = env->NewObjectArray(0, clazz, nullptr);
            if (probe == nullptr || env->ExceptionCheck()) {
                env->DeleteLocalRef(clazz);
                return nullptr;
            }
            jclass next = env->GetObjectClass(probe);
            env->DeleteLocalRef(probe);
            env->DeleteLocalRef(clazz);
            if (next == nullptr || env->ExceptionCheck()) {
                return nullptr;
            }
            clazz = next;
        }
        result_array = env->NewObjectArray(current_size, clazz, nullptr);
        env->DeleteLocalRef(clazz);
        if (result_array == nullptr || env->ExceptionCheck()) {
            return nullptr;
        }

        if (required_count == 1) {
            return result_array;
        }

        for (jint i = 0; i < current_size; i++) {
            jobjectArray inner_array = create_multidim_array(env, classloader, count - 1, required_count - 1,
                class_name, line, sizes, dim_index + 1, receiver, receiver_loader, defining_head);
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

    jclass find_class_wo_static(JNIEnv *env, jobject classloader, jstring class_name_string) {
        jclass clazz = (jclass) env->CallObjectMethod(
            classloader,
            load_class_method,
            class_name_string
        );
        if (env->ExceptionCheck()) {
            jthrowable exception = env->ExceptionOccurred();
            env->ExceptionClear();
            jobject details = env->CallObjectMethod(
                exception,
                get_message_method
            );
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(exception);
                return nullptr;
            }
            jobject new_exception = env->NewObject(no_class_def_found_class,
                ncdf_init_method,
                details);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(exception);
                env->DeleteLocalRef(details);
                return nullptr;
            }
            env->CallVoidMethod(new_exception, init_cause_method, exception);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(new_exception);
                env->DeleteLocalRef(exception);
                env->DeleteLocalRef(details);
                return nullptr;
            }
            env->Throw((jthrowable) new_exception);
            env->DeleteLocalRef(exception);
            env->DeleteLocalRef(details);
            return nullptr;
        }
        return clazz;
    }

    static bool is_class_not_found(JNIEnv *env, jthrowable exception) {
        if (env->IsInstanceOf(exception, class_not_found_class)) {
            return true;
        }
        if (!env->IsInstanceOf(exception, no_class_def_found_class)) {
            return false;
        }
        jobject cause = env->CallObjectMethod(exception, get_cause_method);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        if (cause == nullptr) {
            return false;
        }
        bool not_found = env->IsInstanceOf(cause, class_not_found_class);
        env->DeleteLocalRef(cause);
        return not_found;
    }

    static int try_load(JNIEnv *env, jobject loader, jstring name, jclass *found, jthrowable *pending) {
        if (loader == nullptr) {
            return 0;
        }
        jclass clazz = find_class_wo_static(env, loader, name);
        if (clazz != nullptr) {
            if (*pending != nullptr) {
                env->DeleteLocalRef(*pending);
                *pending = nullptr;
            }
            *found = clazz;
            return 1;
        }
        jthrowable ex = env->ExceptionOccurred();
        env->ExceptionClear();
        if (ex == nullptr || !is_class_not_found(env, ex)) {
            if (*pending != nullptr) {
                env->DeleteLocalRef(*pending);
                *pending = nullptr;
            }
            if (ex != nullptr) {
                env->Throw(ex);
                env->DeleteLocalRef(ex);
            }
            return -1;
        }
        if (*pending != nullptr) {
            env->DeleteLocalRef(*pending);
        }
        *pending = ex;
        return 0;
    }

    static bool binding_has_loader(JNIEnv *env, defining_binding *head, jobject loader) {
        for (defining_binding *binding = head; binding != nullptr; binding = binding->next.load(std::memory_order_acquire)) {
            if (binding->loader != nullptr && env->IsSameObject(binding->loader, loader)) {
                return true;
            }
        }
        return false;
    }

    static jclass load_receiver(JNIEnv *env, defining_binding *head, jobject classloader, jstring class_name,
        jobject receiver, jobject *receiver_loader, jthrowable pending) {
        bool fresh = false;
        jobject loader = receiver_loader == nullptr ? nullptr : *receiver_loader;
        if (loader == nullptr) {
            jclass runtime = get_class_from_object(env, receiver);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (pending != nullptr) {
                    env->Throw(pending);
                    env->DeleteLocalRef(pending);
                }
                return nullptr;
            }
            loader = get_classloader_from_class(env, runtime);
            env->DeleteLocalRef(runtime);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (pending != nullptr) {
                    env->Throw(pending);
                    env->DeleteLocalRef(pending);
                }
                return nullptr;
            }
            fresh = true;
        }
        bool already = loader != nullptr && head != nullptr && binding_has_loader(env, head, loader);
        if (!already && loader != nullptr && head == nullptr && classloader != nullptr && env->IsSameObject(loader, classloader)) {
            already = true;
        }
        if (loader == nullptr || already) {
            if (fresh && loader != nullptr) {
                env->DeleteLocalRef(loader);
            }
            if (pending != nullptr) {
                env->Throw(pending);
                env->DeleteLocalRef(pending);
            }
            return nullptr;
        }
        if (fresh) {
            *receiver_loader = loader;
        }
        if (pending != nullptr) {
            env->DeleteLocalRef(pending);
        }
        return find_class_wo_static(env, loader, class_name);
    }

    static jclass resolve_named(JNIEnv *env, std::atomic<defining_binding *> *defining_head, jobject classloader,
        jstring class_name, jobject receiver, jobject *receiver_loader, bool *cacheable) {
        if (cacheable != nullptr) {
            *cacheable = false;
        }
        defining_binding *head = defining_head == nullptr ? nullptr : defining_head->load(std::memory_order_acquire);
        defining_binding *single = defining_head == nullptr ? nullptr : single_defining_binding(defining_head);
        jthrowable pending = nullptr;
        jclass found = nullptr;
        if (receiver == nullptr || receiver_loader == nullptr) {
            int status = try_load(env, classloader, class_name, &found, &pending);
            if (status > 0) {
                if (cacheable != nullptr && single != nullptr) {
                    *cacheable = true;
                }
                return found;
            }
            if (status < 0) {
                return nullptr;
            }
            if (pending != nullptr) {
                env->Throw(pending);
                env->DeleteLocalRef(pending);
            }
            return nullptr;
        }
        if (single != nullptr) {
            int status = try_load(env, single->loader != nullptr ? single->loader : classloader, class_name, &found, &pending);
            if (status > 0) {
                if (cacheable != nullptr) {
                    *cacheable = true;
                }
                return found;
            }
            if (status < 0) {
                return nullptr;
            }
        } else if (head != nullptr) {
            for (defining_binding *binding = head; binding != nullptr; binding = binding->next.load(std::memory_order_acquire)) {
                int status = try_load(env, binding->loader, class_name, &found, &pending);
                if (status > 0) {
                    return found;
                }
                if (status < 0) {
                    return nullptr;
                }
            }
        } else {
            int status = try_load(env, classloader, class_name, &found, &pending);
            if (status > 0) {
                return found;
            }
            if (status < 0) {
                return nullptr;
            }
        }
        return load_receiver(env, single != nullptr ? single : head, classloader, class_name, receiver, receiver_loader, pending);
    }

    static bool primitive_array_desc(const char *desc) {
        if (desc == nullptr || desc[0] != '[') {
            return false;
        }
        const char *cursor = desc;
        while (*cursor == '[') {
            cursor++;
        }
        return *cursor != 'L' && *cursor != '\0';
    }

    static jclass resolve_array_desc(JNIEnv *env, std::atomic<defining_binding *> *defining_head, jobject classloader,
        const char *desc, jobject receiver, jobject *receiver_loader, bool *cacheable);

    static jclass resolve_array_desc(JNIEnv *env, std::atomic<defining_binding *> *defining_head, jobject classloader,
        const char *desc, jobject receiver, jobject *receiver_loader, bool *cacheable) {
        if (primitive_array_desc(desc)) {
            jclass clazz = env->FindClass(desc);
            if (cacheable != nullptr) {
                *cacheable = clazz != nullptr;
            }
            return clazz;
        }
        const char *inner = desc + 1;
        jclass component = nullptr;
        bool inner_cacheable = false;
        if (inner[0] == '[') {
            component = resolve_array_desc(env, defining_head, classloader, inner, receiver, receiver_loader, &inner_cacheable);
        } else if (inner[0] == 'L') {
            std::string binary(inner + 1);
            if (binary.empty() || binary.back() != ';') {
                return nullptr;
            }
            binary.pop_back();
            std::replace(binary.begin(), binary.end(), '/', '.');
            jstring name = env->NewStringUTF(binary.c_str());
            if (name == nullptr) {
                return nullptr;
            }
            component = resolve_named(env, defining_head, classloader, name, receiver, receiver_loader, &inner_cacheable);
            env->DeleteLocalRef(name);
        }
        if (component == nullptr) {
            return nullptr;
        }
        jobjectArray probe = env->NewObjectArray(0, component, nullptr);
        env->DeleteLocalRef(component);
        if (probe == nullptr || env->ExceptionCheck()) {
            return nullptr;
        }
        jclass array_class = env->GetObjectClass(probe);
        env->DeleteLocalRef(probe);
        if (cacheable != nullptr) {
            *cacheable = inner_cacheable;
        }
        return array_class;
    }

    jclass resolve_class(JNIEnv *env, std::atomic<defining_binding *> *defining_head, jobject classloader,
        jstring class_name, jobject receiver, jobject *receiver_loader, bool *cacheable) {
        if (class_name == nullptr) {
            return nullptr;
        }
        const char *utf = env->GetStringUTFChars(class_name, nullptr);
        if (utf == nullptr) {
            return nullptr;
        }
        bool array = utf[0] == '[';
        std::string owned(utf);
        env->ReleaseStringUTFChars(class_name, utf);
        if (array) {
            return resolve_array_desc(env, defining_head, classloader, owned.c_str(), receiver, receiver_loader, cacheable);
        }
        return resolve_named(env, defining_head, classloader, class_name, receiver, receiver_loader, cacheable);
    }

    jclass find_class_fallback(JNIEnv *env, jobject classloader, jstring class_name, jobject receiver, jobject *receiver_loader) {
        return resolve_class(env, nullptr, classloader, class_name, receiver, receiver_loader, nullptr);
    }

    void throw_re(JNIEnv *env, const char *exception_class, const char *error, int line) {
        jclass exception_class_ptr = env->FindClass(exception_class);
        if (env->ExceptionCheck()) {
            return;
        }
        env->ThrowNew(exception_class_ptr, ("\"" + std::string(error) + "\" on " + std::to_string(line)).c_str());
        env->DeleteLocalRef(exception_class_ptr);
    }

    void bastore(JNIEnv *env, jarray array, jint index, jint value) {
        if (env->IsInstanceOf(array, boolean_array_class))
            env->SetBooleanArrayRegion((jbooleanArray) array, index, 1, (jboolean*) (&value));
        else
            env->SetByteArrayRegion((jbyteArray) array, index, 1, (jbyte*) (&value));
    }

    jbyte baload(JNIEnv *env, jarray array, jint index) {
        jbyte ret_value;
        if (env->IsInstanceOf(array, boolean_array_class))
            env->GetBooleanArrayRegion((jbooleanArray) array, index, 1, (jboolean*) (&ret_value));
        else
            env->GetByteArrayRegion((jbyteArray) array, index, 1, (jbyte*) (&ret_value));
        return ret_value;
    }

    jclass get_class_from_object(JNIEnv *env, jobject object) {
        jobject result_class = env->CallObjectMethod(object, get_class_method);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        return (jclass) result_class;
    }

    jobject get_classloader_from_class(JNIEnv *env, jclass clazz) {
        jobject result_classloader = env->CallObjectMethod(clazz, get_classloader_method);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        return result_classloader;
    }

    jobject get_lookup(JNIEnv *env, jclass clazz) {
        jobject lookup = env->NewObject(methodhandles_lookup_class, lookup_init_method, clazz);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        return lookup;
    }

    void clear_refs(JNIEnv *env, std::unordered_set<jobject> &refs) {
        for (jobject ref : refs)
            if (env->GetObjectRefType(ref) == JNILocalRefType)
                env->DeleteLocalRef(ref);
        refs.clear();
    }

    jstring get_interned(JNIEnv *env, jstring value) {
        jstring result = (jstring) env->CallObjectMethod(value, string_intern_method);
        if (env->ExceptionCheck())
            return nullptr;
        return result;
    }

    void publish_defining_binding(JNIEnv *env, std::atomic<defining_binding *> *head, std::mutex *mtx, jclass clazz) {
        jobject loader_local = get_classloader_from_class(env, clazz);
        if (env->ExceptionCheck()) {
            return;
        }
        jclass clazz_global = (jclass) env->NewGlobalRef(clazz);
        if (clazz_global == nullptr) {
            if (loader_local != nullptr) {
                env->DeleteLocalRef(loader_local);
            }
            return;
        }
        jobject loader_global = nullptr;
        if (loader_local != nullptr) {
            loader_global = env->NewGlobalRef(loader_local);
            env->DeleteLocalRef(loader_local);
            if (loader_global == nullptr) {
                env->DeleteGlobalRef(clazz_global);
                return;
            }
        }
        defining_binding *node = new defining_binding();
        node->clazz = clazz_global;
        node->loader = loader_global;
        node->next.store(nullptr, std::memory_order_relaxed);
        std::lock_guard<std::mutex> guard(*mtx);
        defining_binding *current = head->load(std::memory_order_relaxed);
        if (current == nullptr) {
            head->store(node, std::memory_order_release);
            return;
        }
        while (true) {
            if (env->IsSameObject(current->clazz, clazz)) {
                env->DeleteGlobalRef(clazz_global);
                if (loader_global != nullptr) {
                    env->DeleteGlobalRef(loader_global);
                }
                delete node;
                return;
            }
            defining_binding *next = current->next.load(std::memory_order_acquire);
            if (next == nullptr) {
                current->next.store(node, std::memory_order_release);
                return;
            }
            current = next;
        }
    }
}