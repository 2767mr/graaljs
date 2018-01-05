/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

#define SUITE Object

#ifdef SUITE_INTERNALS

int simpleGetterCallCount = 0;
int simpleSetterCallCount = 0;

void SimpleAccessorGetter(Local<String> property, const PropertyCallbackInfo<Value>& info) {
    Isolate* isolate = info.GetIsolate();
    simpleGetterCallCount++;
    info.GetReturnValue().Set(String::Concat(String::NewFromUtf8(isolate, "accessor getter called: "), property));
}

void SimpleAccessorSetter(Local<String> property, Local<Value> value, const PropertyCallbackInfo<void>& info) {
    Isolate* isolate = info.GetIsolate();
    simpleSetterCallCount++;
    Local<Object> obj = info.This();
    obj->Set(String::NewFromUtf8(isolate, "mySetValue"), value);
}

#endif


// Object::GetOwnPropertyNames

EXPORT_TO_JS(GetOwnPropertyNames) {
    args.GetReturnValue().Set(args[0].As<Object>()->GetOwnPropertyNames());
}

// Object::GetPropertyNames

EXPORT_TO_JS(GetPropertyNames) {
    args.GetReturnValue().Set(args[0].As<Object>()->GetPropertyNames());
}

// Object::Get

EXPORT_TO_JS(GetByName) {
    args.GetReturnValue().Set(args[0].As<Object>()->Get(args[1]));
}

EXPORT_TO_JS(GetByIndex) {
    uint32_t index = args[1].As<Integer>()->Value();
    args.GetReturnValue().Set(args[0].As<Object>()->Get(index));
}

// Object::Set

EXPORT_TO_JS(SetByName) {
    Local<Object> obj = args[0].As<Object>();
    Local<String> key = args[1].As<String>();
    Local<Value> value = args[2];
    args.GetReturnValue().Set(obj->Set(key, value));
}

EXPORT_TO_JS(SetByIndex) {
    Local<Object> obj = args[0].As<Object>();
    uint32_t index = args[1].As<Integer>()->Value();
    Local<Value> value = args[2];
    args.GetReturnValue().Set(obj->Set(index, value));
}

// Object::ForceSet

EXPORT_TO_JS(ForceSet) {
    Local<Object> obj = args[0].As<Object>();
    Local<Value> key = args[1];
    Local<Value> value = args[2];
    args.GetReturnValue().Set(obj->ForceSet(key, value));
}

// Object::Has

EXPORT_TO_JS(HasByName) {
    args.GetReturnValue().Set(args[0].As<Object>()->Has(args[1].As<String>()));
}

// Object::HasOwnProperty

EXPORT_TO_JS(HasOwnProperty) {
    args.GetReturnValue().Set(args[0].As<Object>()->HasOwnProperty(args[1].As<String>()));
}

// Object::HasRealNamedProperty

EXPORT_TO_JS(HasRealNamedProperty) {
    args.GetReturnValue().Set(args[0].As<Object>()->HasRealNamedProperty(args[1].As<String>()));
}

// Object::HasRealIndexedProperty

EXPORT_TO_JS(HasRealIndexedProperty) {
    args.GetReturnValue().Set(args[0].As<Object>()->HasRealIndexedProperty(args[1]->ToUint32()->Value()));
}

// Object::Delete

EXPORT_TO_JS(DeleteByName) {
    args.GetReturnValue().Set(args[0].As<Object>()->Delete(args[1].As<String>()));
}

// Object::InternalFieldCount

EXPORT_TO_JS(InternalFieldCount) {
    args.GetReturnValue().Set(args[0].As<Object>()->InternalFieldCount());
}

// Object::GetConstructorName

EXPORT_TO_JS(GetConstructorName) {
    args.GetReturnValue().Set(args[0].As<Object>()->GetConstructorName());
}

// Object::GetPrototype

EXPORT_TO_JS(GetPrototype) {
    args.GetReturnValue().Set(args[0].As<Object>()->GetPrototype());
}

// Object::CreationContext

EXPORT_TO_JS(CreationContextIsCurrent) {
    Local<Object> obj = args[0].As<Object>();

    Local<Context> creationContext = obj->CreationContext();
    Local<Context> currentContext = args.GetIsolate()->GetCurrentContext();
    bool result = (creationContext == currentContext);
    args.GetReturnValue().Set(result);
}

EXPORT_TO_JS(CreationContextNewContext) {
    Isolate* isolate = args.GetIsolate();
    Local<Context> newContext = Context::New(isolate);

    newContext->Enter();
    Local<Object> object = Object::New(isolate);
    newContext->Exit();

    Local<Context> creationContext = object->CreationContext();
    bool result = (creationContext == newContext);
    args.GetReturnValue().Set(result);
}

// Object::Clone

EXPORT_TO_JS(Clone) {
    Local<Object> obj = args[0].As<Object>();

    Local<Object> newObj = obj->Clone();
    args.GetReturnValue().Set(newObj);
}

// Object::SetAccessor

EXPORT_TO_JS(SetAccessor) {
    Local<Object> obj = args[0].As<Object>();
    Local<String> key = args[1].As<String>();

    obj->SetAccessor(key, SimpleAccessorGetter, SimpleAccessorSetter);

    args.GetReturnValue().Set(true);
}

#undef SUITE
