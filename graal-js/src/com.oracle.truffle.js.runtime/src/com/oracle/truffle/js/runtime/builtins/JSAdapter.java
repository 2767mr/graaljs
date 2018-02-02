/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime.builtins;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.LocationModifier;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;
import com.oracle.truffle.js.runtime.objects.PropertyDescriptor;

public final class JSAdapter extends AbstractJSClass implements JSConstructorFactory.Default {

    public static final String CLASS_NAME = "JSAdapter";

    private static final JSAdapter INSTANCE = new JSAdapter();

    private static final Property ADAPTEE_PROPERTY;
    private static final Property OVERRIDES_PROPERTY;

    private static final String GET = "__get__";
    private static final String PUT = "__put__";
    private static final String HAS = "__has__";
    private static final String CALL = "__call__";
    private static final String DELETE = "__delete__";
    public static final String NEW = "__new__";
    public static final String GET_IDS = "__getIds__";
    public static final String GET_VALUES = "__getValues__";

    private static final HiddenKey ADAPTEE_ID = new HiddenKey("adaptee");
    private static final HiddenKey OVERRIDES_ID = new HiddenKey("overrides");

    static {
        Shape.Allocator allocator = JSShape.makeAllocator(JSObject.LAYOUT);
        ADAPTEE_PROPERTY = JSObjectUtil.makeHiddenProperty(ADAPTEE_ID, allocator.locationForType(Object.class, EnumSet.of(LocationModifier.Final)));
        OVERRIDES_PROPERTY = JSObjectUtil.makeHiddenProperty(OVERRIDES_ID, allocator.locationForType(Object.class, EnumSet.of(LocationModifier.Final)));
    }

    private JSAdapter() {
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @Override
    public String getClassName(DynamicObject object) {
        return getClassName();
    }

    @Override
    public String toString() {
        return getClassName();
    }

    public static DynamicObject create(JSContext context, DynamicObject adaptee, DynamicObject overrides, DynamicObject proto) {
        DynamicObject obj = JSObject.create(context, context.getRealm().getJSAdapterFactory(), adaptee, overrides);
        if (proto != null) {
            JSObject.setPrototype(obj, proto);
        }
        return obj;
    }

    public static DynamicObject getAdaptee(DynamicObject obj) {
        assert isJSAdapter(obj);
        return (DynamicObject) ADAPTEE_PROPERTY.get(obj, isJSAdapter(obj));
    }

    public static DynamicObject getOverrides(DynamicObject obj) {
        assert isJSAdapter(obj);
        return (DynamicObject) OVERRIDES_PROPERTY.get(obj, isJSAdapter(obj));
    }

    public static boolean isJSAdapter(Object obj) {
        return JSObject.isDynamicObject(obj) && isJSAdapter((DynamicObject) obj);
    }

    public static boolean isJSAdapter(DynamicObject obj) {
        return isInstance(obj, INSTANCE);
    }

    private static JSException typeError() {
        return Errors.createTypeError("operation not supported");
    }

    @TruffleBoundary
    @Override
    public Object getOwnHelper(DynamicObject store, Object thisObj, Object name) {
        assert JSRuntime.isPropertyKey(name);
        DynamicObject overrides = getOverrides(store);
        if (overrides != null && JSObject.hasOwnProperty(overrides, name)) {
            return JSObject.get(overrides, name);
        }

        return getIntl(store, name);
    }

    @TruffleBoundary
    @Override
    public Object getOwnHelper(DynamicObject store, Object thisObj, long index) {
        DynamicObject overrides = getOverrides(store);
        if (overrides != null && JSObject.hasOwnProperty(overrides, index)) {
            return JSObject.get(overrides, index);
        }

        assert JSRuntime.longIsRepresentableAsInt(index);
        return getIntl(store, (int) index);
    }

    private static Object getIntl(DynamicObject thisObj, Object name) {
        if (name instanceof Symbol) {
            return null;
        }
        DynamicObject adaptee = getAdaptee(thisObj);
        Object get = JSObject.get(adaptee, GET);
        if (JSFunction.isJSFunction(get)) {
            return JSFunction.call((DynamicObject) get, thisObj, new Object[]{name});
        }
        return null;
    }

    @TruffleBoundary
    @Override
    public boolean hasOwnProperty(DynamicObject thisObj, long propIdx) {
        DynamicObject overrides = getOverrides(thisObj);
        if (overrides != null && JSObject.hasOwnProperty(overrides, propIdx)) {
            return true;
        }
        return hasOwnPropertyIntl(thisObj, propIdx);
    }

    @TruffleBoundary
    @Override
    public boolean hasOwnProperty(DynamicObject thisObj, Object propName) {
        assert JSRuntime.isPropertyKey(propName);
        DynamicObject overrides = getOverrides(thisObj);
        if (overrides != null && JSObject.hasOwnProperty(overrides, propName)) {
            return true;
        }
        return hasOwnPropertyIntl(thisObj, propName);
    }

    private static boolean hasOwnPropertyIntl(DynamicObject thisObj, Object property) {
        DynamicObject adaptee = getAdaptee(thisObj);
        Object has = JSObject.get(adaptee, HAS);
        if (JSFunction.isJSFunction(has)) {
            return JSRuntime.toBoolean(JSFunction.call((DynamicObject) has, thisObj, new Object[]{property}));
        }
        return false;
    }

    @TruffleBoundary
    @Override
    public boolean setOwn(DynamicObject thisObj, long index, Object value, Object receiver, boolean isStrict) {
        DynamicObject overrides = getOverrides(thisObj);
        if (overrides != null && JSObject.hasOwnProperty(overrides, index)) {
            JSObject.set(overrides, index, value, isStrict);
            return true;
        }

        DynamicObject adaptee = getAdaptee(thisObj);
        Object set = JSObject.get(adaptee, PUT);
        if (JSFunction.isJSFunction(set)) {
            assert JSRuntime.longIsRepresentableAsInt(index);
            JSFunction.call((DynamicObject) set, thisObj, new Object[]{(int) index, value});
        }
        return true;
    }

    @TruffleBoundary
    @Override
    public boolean setOwn(DynamicObject thisObj, Object key, Object value, Object receiver, boolean isStrict) {
        assert JSRuntime.isPropertyKey(key);
        DynamicObject overrides = getOverrides(thisObj);
        if (overrides != null && JSObject.hasOwnProperty(overrides, key)) {
            return JSObject.set(overrides, key, value, isStrict);
        }

        DynamicObject adaptee = getAdaptee(thisObj);
        Object set = JSObject.get(adaptee, PUT);
        if (JSFunction.isJSFunction(set)) {
            JSFunction.call((DynamicObject) set, thisObj, new Object[]{key, value});
        }
        return true;
    }

    @TruffleBoundary
    @Override
    public boolean delete(DynamicObject thisObj, long propIdx, boolean isStrict) {
        DynamicObject overrides = getOverrides(thisObj);
        if (overrides != null && JSObject.delete(overrides, propIdx, isStrict)) {
            return true;
        }

        DynamicObject adaptee = getAdaptee(thisObj);
        Object delete = JSObject.get(adaptee, DELETE);
        if (JSFunction.isJSFunction(delete)) {
            JSFunction.call((DynamicObject) delete, thisObj, new Object[]{propIdx});
        }
        return true;
    }

    @TruffleBoundary
    @Override
    public boolean delete(DynamicObject thisObj, Object propName, boolean isStrict) {
        DynamicObject overrides = getOverrides(thisObj);
        if (overrides != null && JSObject.delete(overrides, propName, isStrict)) {
            return true;
        }

        DynamicObject adaptee = getAdaptee(thisObj);
        Object delete = JSObject.get(adaptee, DELETE);
        if (JSFunction.isJSFunction(delete)) {
            JSFunction.call((DynamicObject) delete, thisObj, new Object[]{propName});
        }
        return true;
    }

    @Override
    public boolean defineOwnProperty(DynamicObject thisObj, Object key, PropertyDescriptor desc, boolean doThrow) {
        boolean exists = setOwn(thisObj, key, desc.getValue(), thisObj, doThrow);
        assert exists;
        return exists;
    }

    @Override
    public boolean preventExtensions(DynamicObject thisObj) {
        throw typeError();
    }

    @Override
    public boolean isExtensible(DynamicObject thisObj) {
        throw typeError();
    }

    @Override
    @TruffleBoundary
    public Iterable<Object> ownPropertyKeys(DynamicObject thisObj) {
        DynamicObject adaptee = getAdaptee(thisObj);
        Object getIds = JSObject.get(adaptee, GET_IDS);
        List<Object> list = new ArrayList<>();
        if (JSFunction.isJSFunction(getIds)) {
            Object returnValue = JSFunction.call((DynamicObject) getIds, thisObj, JSArguments.EMPTY_ARGUMENTS_ARRAY);
            if (JSRuntime.isObject(returnValue)) {
                return JSRuntime.createListFromArrayLikeAllowSymbolString(returnValue);
            }
        }
        return list;
    }

    @Override
    public String safeToString(DynamicObject object) {
        return defaultToString(object);
    }

    @Override
    public DynamicObject createPrototype(final JSRealm realm, DynamicObject ctor) {
        DynamicObject prototype = JSObject.create(realm, realm.getObjectPrototype(), new JSBuiltinObject() {
            @Override
            public String safeToString(DynamicObject object) {
                return CLASS_NAME;
            }

            @Override
            public String getClassName(DynamicObject object) {
                return CLASS_NAME;
            }

            @Override
            public String toString() {
                return CLASS_NAME;
            }
        });
        JSObjectUtil.putConstructorProperty(realm.getContext(), prototype, ctor);
        return prototype;
    }

    public static Shape makeInitialShape(JSContext context, DynamicObject prototype) {
        assert JSShape.getProtoChildTree(prototype.getShape(), INSTANCE) == null;
        Shape initialShape = JSObjectUtil.getProtoChildShape(prototype, INSTANCE, context);
        initialShape = initialShape.addProperty(ADAPTEE_PROPERTY);
        initialShape = initialShape.addProperty(OVERRIDES_PROPERTY);
        return initialShape;
    }

    public static JSConstructor createConstructor(JSRealm realm) {
        return INSTANCE.createConstructorAndPrototype(realm);
    }

    @TruffleBoundary
    @Override
    public Object getMethodHelper(DynamicObject store, Object thisObj, Object name) {
        if (name instanceof Symbol) {
            return null;
        }
        DynamicObject adaptee = getAdaptee(store);
        Object call = JSObject.get(adaptee, CALL);
        if (JSFunction.isJSFunction(call)) {
            return JSFunction.bind(JSFunction.getRealm((DynamicObject) call), (DynamicObject) call, store, new Object[]{name});
        } else {
            throw typeErrorNoSuchFunction(store, name);
        }
    }

    @Override
    public DynamicObject getPrototypeOf(DynamicObject thisObj) {
        return (DynamicObject) JSShape.getPrototypeProperty(thisObj.getShape()).get(thisObj, false);
    }

    @Override
    public boolean setPrototypeOf(DynamicObject thisObj, DynamicObject newPrototype) {
        return JSBuiltinObject.setPrototypeStatic(thisObj, newPrototype);
    }

    @Override
    public PropertyDescriptor getOwnProperty(DynamicObject thisObj, Object propertyKey) {
        throw typeError();
    }

    @Override
    public ForeignAccess getForeignAccessFactory(DynamicObject object) {
        return JSObject.getJSContext(object).getInteropRuntime().getForeignAccessFactory();
    }
}
