/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.builtins;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.builtins.ObjectPrototypeBuiltinsFactory.ObjectPrototypeHasOwnPropertyNodeGen;
import com.oracle.truffle.js.builtins.ObjectPrototypeBuiltinsFactory.ObjectPrototypeIsPrototypeOfNodeGen;
import com.oracle.truffle.js.builtins.ObjectPrototypeBuiltinsFactory.ObjectPrototypePropertyIsEnumerableNodeGen;
import com.oracle.truffle.js.builtins.ObjectPrototypeBuiltinsFactory.ObjectPrototypeToLocaleStringNodeGen;
import com.oracle.truffle.js.builtins.ObjectPrototypeBuiltinsFactory.ObjectPrototypeToStringNodeGen;
import com.oracle.truffle.js.builtins.ObjectPrototypeBuiltinsFactory.ObjectPrototypeValueOfNodeGen;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.cast.JSToObjectNode;
import com.oracle.truffle.js.nodes.cast.JSToPropertyKeyNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.nodes.interop.JSUnboxOrGetNode;
import com.oracle.truffle.js.runtime.Boundaries;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.builtins.JSUserObject;
import com.oracle.truffle.js.runtime.objects.JSLazyString;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.PropertyDescriptor;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropUtil;
import com.oracle.truffle.js.runtime.util.JSClassProfile;

/**
 * Contains builtins for Object.prototype.
 */
public final class ObjectPrototypeBuiltins extends JSBuiltinsContainer.SwitchEnum<ObjectPrototypeBuiltins.ObjectPrototype> {
    protected ObjectPrototypeBuiltins() {
        super(JSUserObject.PROTOTYPE_NAME, ObjectPrototype.class);
    }

    public enum ObjectPrototype implements BuiltinEnum<ObjectPrototype> {
        hasOwnProperty(1),
        isPrototypeOf(1),
        propertyIsEnumerable(1),
        toLocaleString(0),
        toString(0),
        valueOf(0);

        private final int length;

        ObjectPrototype(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, ObjectPrototype builtinEnum) {
        switch (builtinEnum) {
            case hasOwnProperty:
                return ObjectPrototypeHasOwnPropertyNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case isPrototypeOf:
                return ObjectPrototypeIsPrototypeOfNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case propertyIsEnumerable:
                return ObjectPrototypePropertyIsEnumerableNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case toLocaleString:
                return ObjectPrototypeToLocaleStringNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case toString:
                return ObjectPrototypeToStringNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case valueOf:
                return ObjectPrototypeValueOfNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
        }
        return null;
    }

    public abstract static class ObjectOperation extends JSBuiltinNode {

        public ObjectOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSToObjectNode toObjectNode;

        private final ConditionProfile isObject = ConditionProfile.createBinaryProfile();
        private final BranchProfile notAJSObjectBranch = BranchProfile.create();

        /**
         * Convert to a DynamicObject that is a JavaScript object.
         */
        protected final DynamicObject toObject(Object target) {
            return JSRuntime.expectJSObject(toTruffleObject(target), notAJSObjectBranch);
        }

        /**
         * Convert to a TruffleObject.
         */
        protected final TruffleObject toTruffleObject(Object target) {
            if (toObjectNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toObjectNode = insert(JSToObjectNode.createToObject(getContext()));
            }
            return toObjectNode.executeTruffleObject(target);
        }

        /**
         * Coerce to Object or throw TypeError. Must be the first statement (evaluation order!) and
         * executed only once.
         */
        protected final DynamicObject asObject(Object object) {
            if (isObject.profile(JSRuntime.isObject(object))) {
                return (DynamicObject) object;
            } else {
                throw createTypeErrorCalledOnNonObject(object);
            }
        }

        protected final DynamicObject toOrAsObject(Object thisObj) {
            if (getContext().getEcmaScriptVersion() >= 6) {
                return toObject(thisObj);
            } else {
                return asObject(thisObj); // ES5
            }
        }

        @TruffleBoundary
        protected final JSException createTypeErrorCalledOnNonObject(Object value) {
            assert !JSRuntime.isObject(value);
            return Errors.createTypeError("Object.%s called on non-object", getBuiltin().getName());
        }
    }

    public abstract static class ObjectPrototypeValueOfNode extends ObjectOperation {

        public ObjectPrototypeValueOfNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = "isJSType(thisObj)")
        protected DynamicObject valueOf(DynamicObject thisObj) {
            return toObject(thisObj);
        }

        @Specialization
        protected DynamicObject valueOf(Symbol thisObj) {
            return toObject(thisObj);
        }

        @Specialization(guards = "!isTruffleObject(thisObj)")
        protected DynamicObject valueOf(Object thisObj) {
            return toObject(thisObj);
        }

        @Specialization(guards = "isForeignObject(thisObj)")
        protected Object valueOf(TruffleObject thisObj,
                        @Cached("create()") JSUnboxOrGetNode unboxOrGetNode) {
            return unboxOrGetNode.executeWithTarget(thisObj);
        }
    }

    public abstract static class ObjectPrototypeToStringNode extends ObjectOperation {
        public ObjectPrototypeToStringNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            getStringTagNode = PropertyGetNode.create(Symbol.SYMBOL_TO_STRING_TAG, false, context);
        }

        @Child private PropertyGetNode getStringTagNode;

        @TruffleBoundary
        private static String formatString(String name) {
            return "[object " + name + "]";
        }

        private String getToStringTag(DynamicObject thisObj) {
            if (getContext().getEcmaScriptVersion() >= 6) {
                Object toStringTag = getStringTagNode.getValue(thisObj);
                if (JSRuntime.isString(toStringTag)) {
                    return Boundaries.javaToString(toStringTag);
                }
            }
            return null;
        }

        private String getDefaultToString(DynamicObject thisObj, JSClassProfile jsclassProfile) {
            if (getContext().getEcmaScriptVersion() >= 6) {
                return jsclassProfile.getJSClass(thisObj).getBuiltinToStringTag(thisObj);
            } else {
                return jsclassProfile.getJSClass(thisObj).getClassName(thisObj);
            }
        }

        @Specialization(guards = {"isJSObject(thisObj)", "!isJSProxy(thisObj)"})
        protected String doJSObject(DynamicObject thisObj,
                        @Cached("create()") JSClassProfile jsclassProfile,
                        @Cached("create()") BranchProfile noStringTagProfile) {
            String toString = getToStringTag(thisObj);
            if (toString == null) {
                noStringTagProfile.enter();
                toString = getDefaultToString(thisObj, jsclassProfile);
            }
            return formatString(toString);
        }

        @Specialization(guards = "isJSProxy(thisObj)")
        protected String doJSProxy(DynamicObject thisObj,
                        @Cached("create()") JSClassProfile jsclassProfile,
                        @Cached("create()") BranchProfile noStringTagProfile) {
            JSRuntime.isArray(thisObj); // might throw
            String toString = getToStringTag(thisObj);
            if (toString == null) {
                noStringTagProfile.enter();
                TruffleObject target = JSProxy.getTargetNonProxy(thisObj);
                if (JSObject.isJSObject(target)) {
                    toString = jsclassProfile.getJSClass((DynamicObject) target).getBuiltinToStringTag((DynamicObject) target);
                } else {
                    toString = "Foreign";
                }
            }
            return formatString(toString);
        }

        @Specialization(guards = "isJSNull(thisObj)")
        protected String doNull(@SuppressWarnings("unused") Object thisObj) {
            return "[object Null]";
        }

        @Specialization(guards = "isUndefined(thisObj)")
        protected String doUndefined(@SuppressWarnings("unused") Object thisObj) {
            return "[object Undefined]";
        }

        @Specialization(guards = "isForeignObject(thisObj)")
        @TruffleBoundary
        protected String doForeignObject(TruffleObject thisObj) {
            return "[foreign " + thisObj.getClass().getSimpleName() + "]";
        }

        @Specialization
        protected String doSymbol(Symbol thisObj) {
            assert thisObj != null;
            return JSObject.defaultToString(toObject(thisObj));
        }

        @Specialization
        protected String doLazyString(JSLazyString thisObj) {
            return JSObject.defaultToString(toObject(thisObj));
        }

        @Specialization(guards = {"!isTruffleObject(thisObj)"})
        protected String doObject(Object thisObj) {
            assert thisObj != null;
            return JSObject.defaultToString(toObject(thisObj));
        }
    }

    public abstract static class ObjectPrototypeToLocaleStringNode extends ObjectOperation {
        @Child private PropertyGetNode getToString;
        @Child private JSFunctionCallNode callNode;

        public ObjectPrototypeToLocaleStringNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            getToString = PropertyGetNode.create(JSRuntime.TO_STRING, false, context);
            callNode = JSFunctionCallNode.createCall();
        }

        @Specialization
        protected Object toLocaleString(Object obj) {
            Object objConv = obj;
            if (getContext().getEcmaScriptVersion() < 6 || getContext().isOptionV8CompatibilityMode()) {
                objConv = toObject(obj);
            }
            Object toStringFn = getToString.getValue(objConv);
            JSFunction.checkIsFunction(toStringFn);
            return callNode.executeCall(JSArguments.createZeroArg(obj, toStringFn));
        }
    }

    public abstract static class ObjectPrototypePropertyIsEnumerableNode extends ObjectOperation {
        public ObjectPrototypePropertyIsEnumerableNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSToPropertyKeyNode toPropertyKeyNode = JSToPropertyKeyNode.create();
        private final ConditionProfile descNull = ConditionProfile.createBinaryProfile();
        private final JSClassProfile classProfile = JSClassProfile.create();

        @Specialization
        protected boolean propertyIsEnumerable(Object obj, Object key) {
            Object propertyKey = toPropertyKeyNode.execute(key);
            DynamicObject thisJSObj = toObject(obj);
            PropertyDescriptor desc = JSObject.getOwnProperty(thisJSObj, propertyKey, classProfile);
            if (descNull.profile(desc == null)) {
                return false;
            } else {
                return desc.getEnumerable();
            }
        }
    }

    @ImportStatic(value = JSInteropUtil.class)
    public abstract static class ObjectPrototypeHasOwnPropertyNode extends ObjectOperation {

        private final JSClassProfile classProfile = JSClassProfile.create();
        @Child private JSToPropertyKeyNode toPropertyKeyNode;

        public ObjectPrototypeHasOwnPropertyNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean hasOwnProperty(DynamicObject thisObj, String propName) {
            return JSObject.hasOwnProperty(thisObj, propName, classProfile);
        }

        @Specialization
        protected boolean hasOwnProperty(DynamicObject thisObj, int idx) {
            return JSObject.hasOwnProperty(thisObj, idx, classProfile);
        }

        @Specialization(guards = "isJSObject(thisObj)")
        protected boolean hasOwnPropertyJSObject(DynamicObject thisObj, Object propName) {
            Object key = getToPropertyKeyNode().execute(propName); // ordering 15.2.4.5 Note2
            return JSObject.hasOwnProperty(thisObj, key, classProfile);
        }

        @Specialization(guards = "isNullOrUndefined(thisObj)")
        protected boolean hasOwnPropertyNullOrUndefined(DynamicObject thisObj, Object propName) {
            getToPropertyKeyNode().execute(propName); // ordering 15.2.4.5 Note2
            throw Errors.createTypeErrorNotObjectCoercible(thisObj);
        }

        @Specialization
        protected boolean hasOwnPropertyLazyString(JSLazyString thisObj, Object propName) {
            Object key = getToPropertyKeyNode().execute(propName); // ordering 15.2.4.5 Note2
            return JSObject.hasOwnProperty(toObject(thisObj), key, classProfile);
        }

        @Specialization(guards = "!isTruffleObject(thisObj)")
        protected boolean hasOwnPropertyPrimitive(Object thisObj, Object propName) {
            Object key = getToPropertyKeyNode().execute(propName); // ordering 15.2.4.5 Note2
            return JSObject.hasOwnProperty(toObject(thisObj), key, classProfile);
        }

        @Specialization
        protected boolean hasOwnPropertySymbol(Symbol thisObj, Object propName) {
            return hasOwnPropertyPrimitive(thisObj, propName);
        }

        @Specialization(guards = "isForeignObject(thisObj)")
        protected boolean hasOwnPropertyForeign(TruffleObject thisObj, Object propName,
                        @Cached("createRead()") Node readNode) {
            Object key = getToPropertyKeyNode().execute(propName); // ordering 15.2.4.5 Note2
            Object value;
            try {
                value = ForeignAccess.sendRead(readNode, thisObj, key);
            } catch (UnknownIdentifierException e) {
                return false;
            } catch (UnsupportedMessageException e) {
                throw Errors.createError("foreign object does not respond to READ message");
            }
            return (value != null && value != Null.instance);
        }

        protected JSToPropertyKeyNode getToPropertyKeyNode() {
            if (toPropertyKeyNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toPropertyKeyNode = insert(JSToPropertyKeyNode.create());
            }
            return toPropertyKeyNode;
        }
    }

    public abstract static class ObjectPrototypeIsPrototypeOfNode extends ObjectOperation {

        public ObjectPrototypeIsPrototypeOfNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        private final ConditionProfile argIsNull = ConditionProfile.createBinaryProfile();
        private final ConditionProfile firstPrototypeFits = ConditionProfile.createBinaryProfile();

        @Specialization(guards = "isJSObject(arg)")
        protected boolean isPrototypeOf(Object thisObj, DynamicObject arg) {
            DynamicObject object = toObject(thisObj);
            if (argIsNull.profile(arg == null)) {
                return false;
            }
            // unroll one iteration
            DynamicObject pobj = JSObject.getPrototype(arg);
            if (firstPrototypeFits.profile(pobj == object)) {
                return true;
            }
            int counter = 0;
            do {
                counter++;
                if (counter > JSTruffleOptions.MaxExpectedPrototypeChainLength) {
                    throw Errors.createRangeError("prototype chain length exceeded");
                }
                pobj = JSObject.getPrototype(pobj);
                if (pobj == object) {
                    return true;
                }
            } while (pobj != Null.instance);
            return false;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSObject(arg)")
        protected boolean isPrototypeOfNoObject(Object thisObj, Object arg) {
            return false;
        }
    }
}
