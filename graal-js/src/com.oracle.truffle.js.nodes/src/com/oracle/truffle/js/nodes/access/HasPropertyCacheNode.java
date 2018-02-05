/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.access;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.builtins.JSAdapter;
import com.oracle.truffle.js.runtime.builtins.JSModuleNamespace;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.interop.JSJavaWrapper;
import com.oracle.truffle.js.runtime.interop.JavaClass;
import com.oracle.truffle.js.runtime.interop.JavaImporter;
import com.oracle.truffle.js.runtime.interop.JavaMember;
import com.oracle.truffle.js.runtime.interop.JavaPackage;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropNodeUtil;
import com.oracle.truffle.js.runtime.util.JSClassProfile;

/**
 * @see PropertyGetNode
 */
public abstract class HasPropertyCacheNode extends PropertyCacheNode<HasPropertyCacheNode> {
    public static HasPropertyCacheNode create(Object key, JSContext context, boolean hasOwnProperty) {
        if (JSTruffleOptions.PropertyCacheLimit > 0) {
            return new UninitializedHasPropertyCacheNode(key, context, hasOwnProperty);
        } else {
            return createGeneric(key, hasOwnProperty);
        }
    }

    public static HasPropertyCacheNode create(Object key, JSContext context) {
        return create(key, context, false);
    }

    protected HasPropertyCacheNode(Object key) {
        super(key);
    }

    public abstract boolean hasProperty(Object obj);

    public abstract static class LinkedHasPropertyCacheNode extends HasPropertyCacheNode {

        @Child private HasPropertyCacheNode next;
        @Child protected ReceiverCheckNode receiverCheck;

        public LinkedHasPropertyCacheNode(Object key, ReceiverCheckNode receiverCheckNode) {
            super(key);
            this.receiverCheck = receiverCheckNode;
        }

        @Override
        public final boolean hasProperty(Object thisObj) {
            try {
                boolean condition = receiverCheck.accept(thisObj);
                if (condition) {
                    return hasPropertyUnchecked(thisObj, condition);
                } else {
                    return next.hasProperty(thisObj);
                }
            } catch (InvalidAssumptionException e) {
                return rewrite().hasProperty(thisObj);
            }
        }

        @Override
        public NodeCost getCost() {
            if (next != null && next.getCost() == NodeCost.MONOMORPHIC) {
                return NodeCost.POLYMORPHIC;
            }
            return super.getCost();
        }

        public abstract boolean hasPropertyUnchecked(Object thisObj, boolean floatingCondition);

        protected HasPropertyCacheNode rewrite() {
            assert next != null;
            HasPropertyCacheNode replacedNext = replace(next);
            return replacedNext;
        }

        @Override
        protected final Shape getShape() {
            return receiverCheck.getShape();
        }

        @Override
        @TruffleBoundary
        public String debugString() {
            return getClass().getSimpleName() + "<property=" + key + ",shape=" + getShape() + ">\n" + ((next == null) ? "" : next.debugString());
        }

        @Override
        @TruffleBoundary
        public String toString() {
            return super.toString() + " property=" + key;
        }

        @Override
        public HasPropertyCacheNode getNext() {
            return next;
        }

        @Override
        protected final void setNext(HasPropertyCacheNode to) {
            next = to;
        }
    }

    public static final class PresentHasPropertyCacheNode extends LinkedHasPropertyCacheNode {
        public PresentHasPropertyCacheNode(Object key, ReceiverCheckNode shapeCheck) {
            super(key, shapeCheck);
        }

        @Override
        public boolean hasPropertyUnchecked(Object thisObj, boolean floatingCondition) {
            return true;
        }
    }

    /**
     * For use when a property is undefined. Returns undefined.
     */
    public static final class AbsentHasPropertyCacheNode extends LinkedHasPropertyCacheNode {

        public AbsentHasPropertyCacheNode(Object key, ReceiverCheckNode shapeCheckNode) {
            super(key, shapeCheckNode);
        }

        @Override
        public boolean hasPropertyUnchecked(Object thisObj, boolean floatingCondition) {
            return false;
        }
    }

    public static final class JSAdapterHasPropertyCacheNode extends LinkedHasPropertyCacheNode {
        private final boolean isMethod;

        public JSAdapterHasPropertyCacheNode(Object key, ReceiverCheckNode receiverCheckNode, boolean isMethod) {
            super(key, receiverCheckNode);
            assert JSRuntime.isPropertyKey(key);
            this.isMethod = isMethod;
        }

        @Override
        public boolean hasPropertyUnchecked(Object thisObj, boolean floatingCondition) {
            if (isMethod) {
                throw new UnsupportedOperationException();
            } else {
                return JSObject.hasOwnProperty((DynamicObject) thisObj, key);
            }
        }
    }

    public static final class JSProxyDispatcherPropertyHasNode extends LinkedHasPropertyCacheNode {

        @Child private JSProxyHasPropertyNode proxyGet;

        public JSProxyDispatcherPropertyHasNode(JSContext context, Object key, ReceiverCheckNode receiverCheck, @SuppressWarnings("unused") boolean isMethod) {
            super(key, receiverCheck);
            assert JSRuntime.isPropertyKey(key);
            this.proxyGet = JSProxyHasPropertyNodeGen.create(context);
        }

        @Override
        public boolean hasPropertyUnchecked(Object thisObj, boolean floatingCondition) {
            return proxyGet.executeWithTargetAndKeyBoolean(receiverCheck.getStore(thisObj), key);
        }
    }

    public static final class UnspecializedHasPropertyCacheNode extends LinkedHasPropertyCacheNode {
        public UnspecializedHasPropertyCacheNode(Object key, ReceiverCheckNode receiverCheckNode) {
            super(key, receiverCheckNode);
        }

        @Override
        public boolean hasPropertyUnchecked(Object thisObj, boolean floatingCondition) {
            return JSObject.hasOwnProperty((DynamicObject) thisObj, key);
        }
    }

    public abstract static class TerminalPropertyGetNode extends HasPropertyCacheNode {

        public TerminalPropertyGetNode(Object key) {
            super(key);
        }

        @Override
        protected final HasPropertyCacheNode getNext() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected final void setNext(HasPropertyCacheNode next) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected final Shape getShape() {
            return null;
        }
    }

    @NodeInfo(cost = NodeCost.MEGAMORPHIC)
    public static final class GenericHasPropertyCacheNode extends TerminalPropertyGetNode {
        private final JSClassProfile jsclassProfile = JSClassProfile.create();
        private final boolean hasOwnProperty;

        public GenericHasPropertyCacheNode(Object key, boolean hasOwnProperty) {
            super(key);
            this.hasOwnProperty = hasOwnProperty;
        }

        @Override
        public boolean hasProperty(Object thisObj) {
            if (hasOwnProperty) {
                return JSObject.hasOwnProperty((DynamicObject) thisObj, key, jsclassProfile);
            } else {
                return JSObject.hasProperty((DynamicObject) thisObj, key, jsclassProfile);
            }
        }

        @Override
        protected boolean isHasOwnProperty() {
            return hasOwnProperty;
        }
    }

    public static final class ForeignHasPropertyCacheNode extends LinkedHasPropertyCacheNode {

        public ForeignHasPropertyCacheNode(Object key, JSContext context) {
            super(key, new InstanceofCheckNode(TruffleObject.class, context));
        }

        @Override
        public boolean hasPropertyUnchecked(Object thisObj, boolean floatingCondition) {
            return JSInteropNodeUtil.hasProperty((TruffleObject) thisObj, key);

        }
    }

    @NodeInfo(cost = NodeCost.UNINITIALIZED)
    public static final class UninitializedHasPropertyCacheNode extends TerminalPropertyGetNode {
        @CompilationFinal private boolean isMethod;
        private boolean propertyAssumptionCheckEnabled = true;
        private final JSContext context;
        private final boolean hasOwnProperty;

        public UninitializedHasPropertyCacheNode(Object key, JSContext context, boolean hasOwnProperty) {
            super(key);
            this.context = context;
            this.hasOwnProperty = hasOwnProperty;
        }

        @Override
        public boolean hasProperty(Object thisObject) {
            return rewrite(context, thisObject, null).hasProperty(thisObject);
        }

        @Override
        protected boolean isMethod() {
            return isMethod;
        }

        @Override
        protected void setMethod() {
            CompilerAsserts.neverPartOfCompilation();
            isMethod = true;
        }

        @Override
        protected boolean isPropertyAssumptionCheckEnabled() {
            return propertyAssumptionCheckEnabled;
        }

        @Override
        protected void setPropertyAssumptionCheckEnabled(boolean value) {
            this.propertyAssumptionCheckEnabled = value;
        }

        @Override
        protected boolean isHasOwnProperty() {
            return hasOwnProperty;
        }
    }

    public static class JavaClassHasPropertyCacheNode extends LinkedHasPropertyCacheNode {
        protected final boolean isMethod;
        protected final boolean isClassFilterPresent;

        public JavaClassHasPropertyCacheNode(Object key, ReceiverCheckNode receiverCheckNode, boolean isMethod, boolean isClassFilterPresent) {
            super(key, receiverCheckNode);
            this.isMethod = isMethod;
            this.isClassFilterPresent = isClassFilterPresent;
        }

        @Override
        public boolean hasPropertyUnchecked(Object thisObj, boolean floatingCondition) {
            return hasMember((JavaClass) thisObj);
        }

        protected final boolean hasMember(JavaClass type) {
            JavaMember member = type.getMember((String) key, JavaClass.STATIC, getJavaMemberTypes(isMethod), isClassFilterPresent);
            if (member != null) {
                return true;
            }
            return type.getInnerClass((String) key) != null;
        }
    }

    public static final class CachedJavaClassHasPropertyCacheNode extends JavaClassHasPropertyCacheNode {
        private final JavaClass javaClass;
        private final boolean cachedResult;

        public CachedJavaClassHasPropertyCacheNode(Object key, ReceiverCheckNode receiverCheckNode, boolean isMethod, boolean isClassFilterPresent, JavaClass javaClass) {
            super(key, receiverCheckNode, isMethod, isClassFilterPresent);
            this.javaClass = javaClass;
            this.cachedResult = hasMember(javaClass);
        }

        @Override
        public boolean hasPropertyUnchecked(Object thisObj, boolean floatingCondition) {
            if (javaClass == thisObj) {
                return cachedResult;
            } else {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return this.replace(new JavaClassHasPropertyCacheNode(key, receiverCheck, isMethod, isClassFilterPresent)).hasPropertyUnchecked(thisObj, floatingCondition);
            }
        }
    }

    /**
     * Make a cache for a JSObject with this property map and requested property.
     *
     * @param property The particular entry of the property being accessed.
     */
    @Override
    protected LinkedHasPropertyCacheNode createCachedPropertyNode(Property property, Object thisObj, int depth, JSContext context, Object value) {
        if (isHasOwnProperty() && depth > 0) {
            return createUndefinedPropertyNode(thisObj, thisObj, 0, context, value);
        }
        ReceiverCheckNode check;
        if (JSObject.isDynamicObject(thisObj)) {
            Shape cacheShape = ((DynamicObject) thisObj).getShape();
            check = createShapeCheckNode(cacheShape, (DynamicObject) thisObj, depth, false, false);
        } else {
            check = createPrimitiveReceiverCheck(thisObj, depth, context);
        }
        return new PresentHasPropertyCacheNode(property.getKey(), check);
    }

    @Override
    protected LinkedHasPropertyCacheNode createUndefinedPropertyNode(Object thisObj, Object store, int depth, JSContext context, Object value) {
        LinkedHasPropertyCacheNode specialized = createJavaPropertyNodeMaybe(thisObj, context);
        if (specialized != null) {
            return specialized;
        }
        if (JSObject.isDynamicObject(thisObj)) {
            DynamicObject thisJSObj = (DynamicObject) thisObj;
            Shape cacheShape = thisJSObj.getShape();
            AbstractShapeCheckNode shapeCheck = createShapeCheckNode(cacheShape, thisJSObj, depth, false, false);
            ReceiverCheckNode receiverCheck = (depth == 0) ? new JSClassCheckNode(JSObject.getJSClass(thisJSObj)) : shapeCheck;
            if (JSAdapter.isJSAdapter(store)) {
                return new JSAdapterHasPropertyCacheNode(key, receiverCheck, isMethod());
            } else if (JSProxy.isProxy(store)) {
                return new JSProxyDispatcherPropertyHasNode(context, key, receiverCheck, isMethod());
            } else if (JSModuleNamespace.isJSModuleNamespace(store)) {
                return new UnspecializedHasPropertyCacheNode(key, receiverCheck);
            } else {
                return new AbsentHasPropertyCacheNode(key, shapeCheck);
            }
        } else {
            return new AbsentHasPropertyCacheNode(key, new InstanceofCheckNode(thisObj.getClass(), context));
        }
    }

    @Override
    protected LinkedHasPropertyCacheNode createJavaPropertyNodeMaybe(Object thisObj, JSContext context) {
        if (!JSTruffleOptions.NashornJavaInterop) {
            return null;
        } else if (JSObject.isDynamicObject(thisObj)) {
            assert !JSJavaWrapper.isJSJavaWrapper(thisObj);
            DynamicObject thisJSObj = (DynamicObject) thisObj;
            if (JavaPackage.isJavaPackage(thisJSObj)) {
                return new PresentHasPropertyCacheNode(key, new JSClassCheckNode(JSObject.getJSClass(thisJSObj)));
            } else if (JavaImporter.isJavaImporter(thisJSObj)) {
                return new UnspecializedHasPropertyCacheNode(key, new JSClassCheckNode(JSObject.getJSClass(thisJSObj)));
            }
            return null;
        } else if (thisObj instanceof JavaClass) {
            return new CachedJavaClassHasPropertyCacheNode(key, new InstanceofCheckNode(JavaClass.class, context), isMethod(), JSJavaWrapper.isClassFilterPresent(context), (JavaClass) thisObj);
        } else {
            JavaMember member = getInstanceMember(thisObj, context);
            if (member != null) {
                return new PresentHasPropertyCacheNode(key, new InstanceofCheckNode(thisObj.getClass(), context));
            }
            return null;
        }
    }

    private JavaMember getInstanceMember(Object thisObj, JSContext context) {
        if (thisObj == null) {
            return null;
        }
        if (!(key instanceof String)) {
            // could be Symbol!
            return null;
        }
        JavaClass javaClass = JavaClass.forClass(thisObj.getClass());
        return javaClass.getMember((String) key, JavaClass.INSTANCE, getJavaMemberTypes(isMethod()), JSJavaWrapper.isClassFilterPresent(context));
    }

    /**
     * Make a generic-case node, for when polymorphism becomes too high.
     */
    @Override
    protected HasPropertyCacheNode createGenericPropertyNode(JSContext context) {
        return createGeneric(key, isHasOwnProperty());
    }

    private static HasPropertyCacheNode createGeneric(Object key, boolean hasOwnProperty) {
        return new GenericHasPropertyCacheNode(key, hasOwnProperty);
    }

    protected boolean isMethod() {
        throw new UnsupportedOperationException();
    }

    protected void setMethod() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean isGlobal() {
        return false;
    }

    protected boolean isHasOwnProperty() {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSContext getContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected final Class<HasPropertyCacheNode> getBaseClass() {
        return HasPropertyCacheNode.class;
    }

    @Override
    protected Class<? extends HasPropertyCacheNode> getUninitializedNodeClass() {
        return UninitializedHasPropertyCacheNode.class;
    }

    protected static Class<? extends JavaMember>[] getJavaMemberTypes(boolean isMethod) {
        return isMethod ? JavaClass.METHOD_GETTER : JavaClass.GETTER_METHOD;
    }

    @Override
    protected HasPropertyCacheNode createTruffleObjectPropertyNode(TruffleObject thisObject, JSContext context) {
        return new ForeignHasPropertyCacheNode(key, context);
    }
}
