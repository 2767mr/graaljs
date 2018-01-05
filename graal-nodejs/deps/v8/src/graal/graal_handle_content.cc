/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

#include "graal_handle_content.h"
#include "graal_isolate.h"
#include "graal_value.h"
#include <string.h>

GraalHandleContent::GraalHandleContent(GraalIsolate* isolate, jobject java_object) :
isolate_(isolate),
java_object_(java_object),
ref_type_(0),
ref_count(0) {
#ifdef DEBUG
    if (isolate == nullptr) {
        fprintf(stderr, "NULL isolate passed to GraalHandleContent!\n");
    }
    if (java_object == NULL) {
        fprintf(stderr, "NULL jobject passed to GraalHandleContent!\n");
    }
#endif
}

GraalHandleContent::~GraalHandleContent() {
    JNIEnv* env = isolate_->GetJNIEnv();
    // env can be nullptr during the destruction of static variables
    // on process exit (when the isolate was disposed already)
    if (env != nullptr) {
        if (IsGlobal()) {
            if (IsWeak()) {
                env->DeleteWeakGlobalRef(java_object_);
            } else {
                env->DeleteGlobalRef(java_object_);
            }
        } else {
            env->DeleteLocalRef(java_object_);
        }
    }
}

GraalHandleContent* GraalHandleContent::Copy(bool global) {
    jobject java_object = GetJavaObject();
    if (java_object != NULL) {
        if (global) {
            java_object = isolate_->GetJNIEnv()->NewGlobalRef(java_object);
        } else {
            java_object = isolate_->GetJNIEnv()->NewLocalRef(java_object);
        }
    }
    GraalHandleContent* copy = CopyImpl(java_object);
    if (global) {
        copy->ref_type_ = GLOBAL_FLAG;
        copy->ReferenceAdded();
    }
    return copy;
}

void GraalHandleContent::ClearWeak() {
    jobject java_object = java_object_;
    JNIEnv* env = isolate_->GetJNIEnv();
    java_object_ = env->NewGlobalRef(java_object);
    env->DeleteWeakGlobalRef(java_object);
    ref_type_ = GLOBAL_FLAG;
}

void GraalHandleContent::MakeWeak() {
    jobject java_object = java_object_;
    JNIEnv* env = isolate_->GetJNIEnv();
    java_object_ = env->NewWeakGlobalRef(java_object);
    env->DeleteGlobalRef(java_object);
    ref_type_ = GLOBAL_FLAG | WEAK_FLAG;
}

bool GraalHandleContent::IsString() const {
    return false;
}

bool GraalHandleContent::SameData(GraalHandleContent* this_content, GraalHandleContent* that_content) {
    if (this_content == that_content) {
        return true;
    }
    jobject this_java = this_content->GetJavaObject();
    jobject that_java = that_content->GetJavaObject();
    if (this_java == NULL || that_java == NULL) {
        // If the handle content is not supported by jobject then it is
        // considered equal to itself only.
        return false;
    }
    JNIEnv* env = this_content->Isolate()->GetJNIEnv();
    if (env->IsSameObject(this_java, that_java)) {
        // Check for same jobjects
        return true;
    } else if (this_content->IsString() && that_content->IsString()) {
        // Check for same strings
        jstring this_string = (jstring) this_content->GetJavaObject();
        jstring that_string = (jstring) that_content->GetJavaObject();
        jsize this_length = env->GetStringLength(this_string);
        jsize that_length = env->GetStringLength(that_string);
        if (this_length == that_length) {
            const jchar* this_data = env->GetStringCritical(this_string, nullptr);
            const jchar* that_data = env->GetStringCritical(that_string, nullptr);
            int diff = memcmp(this_data, that_data, sizeof (jchar) * this_length);
            env->ReleaseStringCritical(this_string, this_data);
            env->ReleaseStringCritical(that_string, that_data);
            return (diff == 0);
        }
    }
    return false;
}

jobject GraalHandleContent::ToNewLocalJavaObject() {
    return isolate_->GetJNIEnv()->NewLocalRef(java_object_);
}
