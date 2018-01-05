/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.function;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.js.nodes.JSGuards;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.JSTargetableNode;
import com.oracle.truffle.js.nodes.access.PropertyNode;
import com.oracle.truffle.js.nodes.function.JSNewNodeGen.CachedPrototypeShapeNodeGen;
import com.oracle.truffle.js.nodes.function.JSNewNodeGen.SpecializedNewObjectNodeGen;
import com.oracle.truffle.js.nodes.interop.ExportValueNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.UserScriptException;
import com.oracle.truffle.js.runtime.builtins.JSAdapter;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.builtins.JSUserObject;
import com.oracle.truffle.js.runtime.interop.JavaClass;
import com.oracle.truffle.js.runtime.interop.JavaMethod;
import com.oracle.truffle.js.runtime.interop.JavaPackage;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropNodeUtil;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropUtil;

/**
 * 11.2.2 The new Operator.
 */
@ImportStatic(value = {JSProxy.class})
@NodeChildren({@NodeChild(value = "target", type = JavaScriptNode.class)})
public abstract class JSNewNode extends JavaScriptNode {

    @Child private JSFunctionCallNode callNew;
    @Child private JSFunctionCallNode callNewTarget;
    @Child private AbstractFunctionArgumentsNode arguments;

    protected JSNewNode(AbstractFunctionArgumentsNode arguments, JSFunctionCallNode callNew) {
        this.callNew = callNew;
        this.arguments = arguments;
    }

    public static JSNewNode create(JavaScriptNode function, AbstractFunctionArgumentsNode arguments) {
        JSFunctionCallNode callNew = JSFunctionCallNode.createNew();
        return JSNewNodeGen.create(arguments, callNew, function);
    }

    public abstract JavaScriptNode getTarget();

    public AbstractFunctionArgumentsNode getArguments() {
        return arguments;
    }

    @Specialization(guards = "isJSFunction(target)")
    public Object doNewReturnThis(VirtualFrame frame, DynamicObject target) {
        int userArgumentCount = arguments.getCount(frame);
        Object[] args = JSArguments.createInitial(JSFunction.CONSTRUCT, target, userArgumentCount);
        args = arguments.executeFillObjectArray(frame, args, JSArguments.RUNTIME_ARGUMENT_COUNT);
        return callNew.executeCall(args);
    }

    @Specialization(guards = "isJSAdapter(target)")
    public Object doJSAdapter(VirtualFrame frame, DynamicObject target) {
        Object newFunction = JSObject.get(JSAdapter.getAdaptee(target), JSAdapter.NEW);
        if (JSFunction.isJSFunction(newFunction)) {
            Object[] args = getAbstractFunctionArguments(frame);
            return JSFunction.callDirect((DynamicObject) newFunction, target, args);
        } else {
            return Undefined.instance;
        }
    }

    /**
     * Implements [[Construct]] for Proxy.
     */
    @Specialization(guards = "isProxy(proxy)")
    protected Object callJSProxy(VirtualFrame frame, DynamicObject proxy) {
        if (!JSRuntime.isCallableProxy(proxy)) {
            throw Errors.createTypeErrorNotAFunction(proxy, this);
        }
        DynamicObject handler = JSProxy.getHandlerChecked(proxy);
        TruffleObject target = JSProxy.getTarget(proxy);
        DynamicObject trap = JSProxy.getTrapFromObject(handler, JSProxy.CONSTRUCT);
        if (trap == Undefined.instance) {
            if (JSObject.isJSObject(target)) {
                // Construct(F=target, argumentsList=frame, newTarget=proxy)
                int userArgumentCount = arguments.getCount(frame);
                Object[] args = JSArguments.createInitialWithNewTarget(JSFunction.CONSTRUCT, target, proxy, userArgumentCount);
                args = arguments.executeFillObjectArray(frame, args, JSArguments.RUNTIME_ARGUMENT_COUNT + 1);
                return getCallNewTarget().executeCall(args);
            } else {
                return JSInteropNodeUtil.construct(target, getAbstractFunctionArguments(frame));
            }
        }
        Object[] args = getAbstractFunctionArguments(frame);
        Object[] trapArgs = new Object[]{target, JSArray.createConstantObjectArray(JSShape.getJSContext(proxy.getShape()), args), proxy};
        Object result = JSFunction.callDirect(trap, handler, trapArgs);
        if (!JSRuntime.isObject(result)) {
            throw Errors.createTypeErrorObjectExpected();
        }
        return result;
    }

    @TruffleBoundary
    @Specialization(guards = "isJavaPackage(target)")
    public Object createClassNotFoundError(DynamicObject target) {
        throw UserScriptException.createJavaException(new ClassNotFoundException(JavaPackage.getPackageName(target)), this);
    }

    @Specialization
    public Object doNewJavaObject(VirtualFrame frame, JavaClass target) {
        if (!target.isPublic()) {
            throwCannotExtendError(target);
        }
        Object[] args = JSArguments.createInitial(target, target, arguments.getCount(frame));
        args = arguments.executeFillObjectArray(frame, args, JSArguments.RUNTIME_ARGUMENT_COUNT);
        return callNew.executeCall(args);
    }

    @TruffleBoundary
    private static void throwCannotExtendError(JavaClass target) {
        throw Errors.createTypeError("new cannot be used with non-public java type " + target.getType().getName() + ".");
    }

    @Specialization(guards = "isJavaConstructor(target)")
    public Object doNewJavaObjectSpecialConstructor(VirtualFrame frame, JavaMethod target) {
        Object[] args = JSArguments.createInitial(target, target, arguments.getCount(frame));
        args = arguments.executeFillObjectArray(frame, args, JSArguments.RUNTIME_ARGUMENT_COUNT);
        return callNew.executeCall(args);
    }

    @Specialization(guards = {"isForeignObject(target)"})
    public Object doNewForeignObject(VirtualFrame frame, TruffleObject target,
                    @Cached("createNewCache(frame)") Node newNode,
                    @Cached("create()") ExportValueNode convert) {
        int count = arguments.getCount(frame);
        Object[] args = new Object[count];
        args = arguments.executeFillObjectArray(frame, args, 0);
        // We need to convert (e.g., bind functions) before invoking the constructor
        for (int i = 0; i < args.length; i++) {
            args[i] = convert.executeWithTarget(args[i], Undefined.instance);
        }
        return JSInteropNodeUtil.construct(target, args, newNode, this);
    }

    protected Node createNewCache(VirtualFrame frame) {
        return JSInteropUtil.createNew(arguments.getCount(frame));
    }

    @Specialization(guards = {"!isJSFunction(target)", "!isJavaClass(target)", "!isJSAdapter(target)", "!isJavaPackage(target)", "!isJavaConstructor(target)", "!isForeignObject(target)"})
    public Object createFunctionTypeError(Object target) {
        throw Errors.createTypeErrorNotAFunction(target, this);
    }

    private Object[] getAbstractFunctionArguments(VirtualFrame frame) {
        Object[] args = new Object[arguments.getCount(frame)];
        args = arguments.executeFillObjectArray(frame, args, 0);
        return args;
    }

    private JSFunctionCallNode getCallNewTarget() {
        if (callNewTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callNewTarget = insert(JSFunctionCallNode.createNewTarget());
        }
        return callNewTarget;
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return create(cloneUninitialized(getTarget()), AbstractFunctionArgumentsNode.cloneUninitialized(arguments));
    }

    @NodeChildren({@NodeChild(value = "target", type = JavaScriptNode.class), @NodeChild(value = "shape", type = CachedPrototypeShapeNode.class, executeWith = "target")})
    public abstract static class SpecializedNewObjectNode extends JSTargetableNode {
        private final JSContext context;
        protected final boolean isBuiltin;
        protected final boolean isConstructor;

        public SpecializedNewObjectNode(JSContext context, boolean isBuiltin, boolean isConstructor) {
            this.context = context;
            this.isBuiltin = isBuiltin;
            this.isConstructor = isConstructor;
        }

        public static SpecializedNewObjectNode create(JSContext context, boolean isBuiltin, boolean isConstructor, JavaScriptNode target) {
            return SpecializedNewObjectNodeGen.create(context, isBuiltin, isConstructor, target, CachedPrototypeShapeNode.create(context));
        }

        @Override
        public abstract JavaScriptNode getTarget();

        @Override
        public final Object evaluateTarget(VirtualFrame frame) {
            return getTarget().execute(frame);
        }

        @Specialization(guards = {"!isBuiltin", "isConstructor"})
        public DynamicObject createUserObject(@SuppressWarnings("unused") DynamicObject target, Shape shape) {
            return JSObject.create(context, shape);
        }

        @Specialization(guards = {"!isBuiltin", "isConstructor", "isJSObject(proto)"})
        public DynamicObject createUserObject(@SuppressWarnings("unused") DynamicObject target, DynamicObject proto) {
            return JSUserObject.createWithPrototypeInObject(proto, context);
        }

        @Specialization(guards = {"!isBuiltin", "isConstructor", "isUndefined(shape)"})
        public DynamicObject createUserObjectAsObject(DynamicObject target, Object shape) {
            assert shape == Undefined.instance;
            // user-provided prototype is not an object
            return createUserObject(target, context.getInitialUserObjectShape());
        }

        @Specialization(guards = {"isBuiltin", "isConstructor"})
        public Object useConstruct(@SuppressWarnings("unused") DynamicObject target, @SuppressWarnings("unused") Object shape) {
            return JSFunction.CONSTRUCT;
        }

        @TruffleBoundary
        @Specialization(guards = {"!isConstructor"})
        public Object throwNotConstructorFunctionTypeError(DynamicObject target, @SuppressWarnings("unused") Object shape) {
            throw Errors.createTypeErrorNotConstructible(target);
        }

        @Override
        protected JavaScriptNode copyUninitialized() {
            return create(context, isBuiltin, isConstructor, cloneUninitialized(getTarget()));
        }
    }

    @ImportStatic(JSTruffleOptions.class)
    protected abstract static class CachedPrototypeShapeNode extends JavaScriptBaseNode {
        protected final JSContext context;
        @Child private JSTargetableNode getPrototype;

        protected CachedPrototypeShapeNode(JSContext context) {
            this.context = context;
            this.getPrototype = PropertyNode.createProperty(context, null, JSObject.PROTOTYPE);
        }

        public static CachedPrototypeShapeNode create(JSContext context) {
            return CachedPrototypeShapeNodeGen.create(context);
        }

        public final Object executeWithTarget(VirtualFrame frame, Object target) {
            Object result = getPrototype.executeWithTarget(frame, target);
            return executeWithPrototype(frame, result);
        }

        public abstract Object executeWithPrototype(VirtualFrame frame, Object prototype);

        protected Object getProtoChildShape(Object prototype) {
            CompilerAsserts.neverPartOfCompilation();
            if (JSGuards.isJSObject(prototype)) {
                return JSObjectUtil.getProtoChildShape(((DynamicObject) prototype), JSUserObject.INSTANCE, context);
            }
            return Undefined.instance;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "prototype == cachedPrototype", limit = "PropertyCacheLimit")
        protected static Object doCached(Object prototype,
                        @Cached("prototype") Object cachedPrototype,
                        @Cached("getProtoChildShape(prototype)") Object cachedShape) {
            return cachedShape;
        }

        /** Many different prototypes. */
        @Specialization(replaces = "doCached")
        protected final Object doUncached(Object prototype,
                        @Cached("create()") BranchProfile notAnObjectBranch,
                        @Cached("create()") BranchProfile slowBranch) {
            if (JSGuards.isJSObject(prototype)) {
                return JSObjectUtil.getProtoChildShape(((DynamicObject) prototype), JSUserObject.INSTANCE, context, slowBranch);
            }
            notAnObjectBranch.enter();
            return Undefined.instance;
        }
    }
}
