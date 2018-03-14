/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime.interop;

import java.lang.reflect.Array;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.LocationModifier;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.runtime.Boundaries;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.builtins.AbstractJSClass;
import com.oracle.truffle.js.runtime.builtins.JSClass;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;

public final class JSJavaWrapper extends AbstractJSClass {
    public static final String CLASS_NAME = "JSJavaWrapper";

    static class LazyState {
        private static final HiddenKey VALUE_ID = new HiddenKey("value");
        static final Property VALUE_PROPERTY;
        static final JSJavaWrapper INSTANCE;

        static {
            Shape.Allocator allocator = JSShape.makeAllocator(JSObject.LAYOUT);
            VALUE_PROPERTY = JSObjectUtil.makeHiddenProperty(VALUE_ID, allocator.locationForType(Object.class, EnumSet.of(LocationModifier.Final, LocationModifier.NonNull)));
            INSTANCE = new JSJavaWrapper();
        }
    }

    private JSJavaWrapper() {
        assert JSTruffleOptions.NashornJavaInterop;
    }

    @Override
    public String getClassName(DynamicObject object) {
        return CLASS_NAME;
    }

    @Override
    public String toString() {
        return CLASS_NAME;
    }

    public static DynamicObject create(JSContext context, Object value) {
        if (JSTruffleOptions.NashornJavaInterop) {
            return doCreate(context, value);
        } else {
            /*
             * This path should never be reached when JavaInterop is disabled. To help the static
             * analysis of Substrate VM, we throw an exception.
             */
            throw new UnsupportedOperationException();
        }
    }

    /* In a separate method for Substrate VM support. */
    private static DynamicObject doCreate(JSContext context, Object value) {
        assert !(value instanceof TruffleObject);
        DynamicObject obj = JSObject.create(context, context.getJavaWrapperFactory(), value);
        assert isJSJavaWrapper(obj);
        return obj;
    }

    public static boolean isJSJavaWrapper(Object obj) {
        return JSObject.isDynamicObject(obj) && isJSJavaWrapper((DynamicObject) obj);
    }

    public static boolean isJSJavaWrapper(DynamicObject obj) {
        if (JSTruffleOptions.NashornJavaInterop) {
            return isJSJavaWrapper0(obj);
        } else {
            return false;
        }
    }

    /* In a separate method for Substrate VM support. */
    private static boolean isJSJavaWrapper0(DynamicObject obj) {
        return isInstance(obj, LazyState.INSTANCE);
    }

    private static Object getOwnPropertyJavaSuper(String name, Object wrapped, boolean classFilterPresent) {
        JavaClass type = JavaClass.forClass(((JavaSuperAdapter) wrapped).getAdapter().getClass());
        return JSRuntime.nullToUndefined(type.getSuperMethod(name, classFilterPresent));
    }

    private static Object getOwnPropertyJavaClass(String name, Object wrapped, boolean isMethod, boolean classFilterPresent) {
        JavaClass type = (JavaClass) wrapped;
        Member member = type.getMember(name, JavaClass.STATIC, isMethod ? JavaClass.METHOD_GETTER : JavaClass.GETTER_METHOD, classFilterPresent);
        if (member == null) {
            return type.getInnerClass(name);
        }
        if (member instanceof JavaGetter) {
            return ((JavaGetter) member).getValue(null);
        }
        return member;
    }

    private static Object getOwnPropertyArrayOrList(long index, Object wrapped) {
        assert isArrayOrList(wrapped) : wrapped;
        if (wrapped instanceof List && index >= 0 && index < ((List<?>) wrapped).size()) {
            return Converters.JAVA_TO_JS_CONVERTER.convert(((List<?>) wrapped).get((int) index));
        } else if (wrapped.getClass().isArray() && index >= 0 && index < Array.getLength(wrapped)) {
            return Converters.JAVA_TO_JS_CONVERTER.convert(Array.get(wrapped, (int) index));
        }
        return null;
    }

    private static Object getOwnPropertyDefault(String name, Object wrapped, boolean isMethod, boolean classFilterPresent) {
        JavaClass type = JavaClass.forClass(wrapped.getClass());
        Member member = type.getMember(name, JavaClass.INSTANCE, isMethod ? JavaClass.METHOD_GETTER : JavaClass.GETTER_METHOD, classFilterPresent);
        if (member instanceof JavaGetter) {
            return ((JavaGetter) member).getValue(wrapped);
        } else if (member != null) {
            return member;
        } else {
            assert member == null;
            if (wrapped instanceof Map) {
                return JSRuntime.toJSNull(((Map<?, ?>) wrapped).get(name));
            } else if (isArrayOrList(wrapped)) {
                return getOwnPropertyArrayOrList(JSRuntime.propertyNameToArrayIndex(name), wrapped);
            }
            // Nashorn returns null only for element access!
            // i.e. list["missing"] === null but list.missing === undefined
            return null;
        }
    }

    @Override
    public Object getOwnHelper(DynamicObject store, Object thisObj, long index) {
        Object wrapped = getWrapped(store);
        if (isArrayOrList(wrapped)) {
            return getOwnPropertyArrayOrList(index, wrapped);
        }
        return getOwnHelper(store, thisObj, Boundaries.stringValueOf(index));
    }

    @Override
    public Object getOwnHelper(DynamicObject store, Object thisObj, Object name) {
        if (!(name instanceof String)) {
            return null;
        }
        return getOwnPropertyHelper(store, (String) name, false);
    }

    @TruffleBoundary
    private static Object getOwnPropertyHelper(DynamicObject store, String name, boolean isMethod) {
        Object wrapped = getWrapped(store);
        boolean classFilterPresent = isClassFilterPresent(store);
        if (wrapped instanceof JavaClass) {
            return getOwnPropertyJavaClass(name, wrapped, isMethod, classFilterPresent);
        } else if (wrapped instanceof JavaSuperAdapter) {
            return getOwnPropertyJavaSuper(name, wrapped, classFilterPresent);
        } else if (wrapped != null) {
            return getOwnPropertyDefault(name, wrapped, isMethod, classFilterPresent);
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getMethodHelper(DynamicObject store, Object thisObj, Object name) {
        if (!(name instanceof String)) {
            return null;
        }
        return getOwnPropertyHelper(store, (String) name, true);
    }

    @Override
    public boolean hasOwnProperty(DynamicObject thisObj, long propIdx) {
        return false;
    }

    @Override
    public boolean hasOwnProperty(DynamicObject thisObj, Object name) {
        return getOwnHelper(thisObj, thisObj, name) != null;
    }

    @TruffleBoundary
    @Override
    public boolean setOwn(DynamicObject thisObj, Object name, Object value, Object receiver, boolean isStrict) {
        if (!(name instanceof String)) {
            return true;
        }
        Object wrapped = getWrapped(thisObj);
        if (wrapped instanceof JavaClass) {
            JavaClass javaClass = (JavaClass) wrapped;
            final JavaMember member = javaClass.getMember((String) name, JavaClass.STATIC, JavaClass.SETTER, isClassFilterPresent(thisObj));
            if (member != null) {
                JavaSetter setter = (JavaSetter) member;
                setter.setValue(wrapped, value);
            }
            return true;
        } else {
            JavaClass javaClass = JavaClass.forClass(wrapped.getClass());
            final JavaMember member = javaClass.getMember((String) name, JavaClass.INSTANCE, JavaClass.SETTER, isClassFilterPresent(thisObj));
            if (member != null) {
                JavaSetter setter = (JavaSetter) member;
                setter.setValue(wrapped, value);
            } else if (wrapped instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> map = (Map<Object, Object>) wrapped;
                map.put(name, value);
            }
            return true;
        }
    }

    private static boolean isClassFilterPresent(DynamicObject thisObj) {
        return isClassFilterPresent(JSObject.getJSContext(thisObj));
    }

    public static boolean isClassFilterPresent(JSContext context) {
        assert JSTruffleOptions.NashornJavaInterop;
        TruffleLanguage.Env env = context.getEnv();
        if (env != null && env.isHostLookupAllowed()) {
            try {
                env.lookupHostSymbol(Class.class.getName());
                return false;
            } catch (Exception ex) {
            }
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public Iterable<Object> ownPropertyKeys(DynamicObject thisObj) {
        final Object wrapped = getWrapped(thisObj);
        if (wrapped instanceof Map) {
            return getKeysMap(wrapped);
        } else if (wrapped instanceof List || wrapped.getClass().isArray()) {
            return getKeysList(wrapped);
        }
        return Collections.emptyList();
    }

    private static Iterable<Object> getKeysList(final Object wrapped) {
        return new Iterable<Object>() {
            @Override
            public Iterator<Object> iterator() {
                return new Iterator<Object>() {
                    private int cursor = 0;
                    private final int limit = getLimit();

                    @Override
                    public boolean hasNext() {
                        return cursor != limit;
                    }

                    @Override
                    public Object next() {
                        try {
                            return String.valueOf(cursor);
                        } finally {
                            cursor++;
                        }
                    }

                    private int getLimit() {
                        return (wrapped instanceof List) ? ((List<?>) wrapped).size() : Array.getLength(wrapped);
                    }
                };
            }
        };
    }

    private static Iterable<Object> getKeysMap(final Object wrapped) {
        return new Iterable<Object>() {
            @Override
            public Iterator<Object> iterator() {
                return new Iterator<Object>() {
                    @SuppressWarnings("unchecked") private final Iterator<String> nestedIterator = ((Map<String, Object>) wrapped).keySet().iterator();

                    @Override
                    public boolean hasNext() {
                        return nestedIterator.hasNext();
                    }

                    @Override
                    public Object next() {
                        return nestedIterator.next();
                    }
                };
            }
        };
    }

    private static boolean isArrayOrList(Object wrapped) {
        return wrapped instanceof List || wrapped.getClass().isArray();
    }

    @TruffleBoundary
    public static Iterable<?> getValues(DynamicObject thisObj) {
        final Object wrapped = getWrapped(thisObj);
        if (wrapped instanceof Map) {
            return ((Map<?, ?>) wrapped).values();
        } else if (wrapped instanceof Iterable) {
            return (Iterable<?>) wrapped;
        } else if (wrapped.getClass().isArray()) {
            return listFromArray(wrapped);
        }
        return Collections.emptyList();
    }

    private static Iterable<?> listFromArray(Object wrapped) {
        assert wrapped.getClass().isArray();
        int len = Array.getLength(wrapped);
        ArrayList<Object> list = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            list.add(Array.get(wrapped, i));
        }
        return list;
    }

    @Override
    public String defaultToString(DynamicObject object) {
        return formatToString(getBuiltinToStringTag(object));
    }

    @Override
    public String getBuiltinToStringTag(DynamicObject thisObj) {
        Object wrapped = getWrapped(thisObj);
        if (wrapped instanceof JavaClass) {
            return "JavaClass";
        } else if (wrapped instanceof JavaMethod) {
            return "JavaMethod";
        } else {
            return wrapped.getClass().getName();
        }
    }

    // internal methods

    public static Object getWrapped(DynamicObject wrapper) {
        if (JSTruffleOptions.NashornJavaInterop) {
            return getWrapped0(wrapper);
        } else {
            /*
             * This path should never be reached when JavaInterop is disabled. To help the static
             * analysis of Substrate VM, we throw an exception.
             */
            throw new UnsupportedOperationException();
        }
    }

    /* In a separate method for Substrate VM support. */
    private static Object getWrapped0(DynamicObject wrapper) {
        assert JSJavaWrapper.isJSJavaWrapper(wrapper);
        return LazyState.VALUE_PROPERTY.get(wrapper, isJSJavaWrapper(wrapper));
    }

    @Override
    public String safeToString(DynamicObject object) {
        return "[JSJavaWrapper]";
    }

    public static JSClass getJSClassInstance() {
        return LazyState.INSTANCE;
    }

    @Override
    public boolean testIntegrityLevel(DynamicObject obj, boolean frozen) {
        return false;
    }

    public static Shape makeShape(JSContext context) {
        assert JSTruffleOptions.NashornJavaInterop;
        return JSShape.makeNotExtensible(JSShape.makeEmptyRoot(JSObject.LAYOUT, LazyState.INSTANCE, context)).addProperty(LazyState.VALUE_PROPERTY);
    }
}
