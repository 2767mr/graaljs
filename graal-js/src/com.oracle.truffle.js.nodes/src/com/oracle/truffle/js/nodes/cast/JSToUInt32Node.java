/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.cast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantBooleanNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantDoubleNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantIntegerNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantNullNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantStringNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantUndefinedNode;
import com.oracle.truffle.js.nodes.cast.JSStringToNumberNode.JSStringToNumberWithTrimNode;
import com.oracle.truffle.js.nodes.cast.JSToUInt32NodeGen.JSToUInt32WrapperNodeGen;
import com.oracle.truffle.js.nodes.interop.JSUnboxOrGetNode;
import com.oracle.truffle.js.nodes.unary.JSUnaryNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.LargeInteger;
import com.oracle.truffle.js.runtime.Symbol;

public abstract class JSToUInt32Node extends JavaScriptBaseNode {

    public static JSToUInt32Node create() {
        return JSToUInt32NodeGen.create();
    }

    public abstract Object execute(Object value);

    public final long executeLong(Object value) {
        return JSRuntime.longValue((Number) execute(value));
    }

    @Specialization(guards = "value >= 0")
    protected int doInteger(int value) {
        return value;
    }

    @Specialization(guards = "value < 0")
    protected LargeInteger doIntegerNegative(int value) {
        return LargeInteger.valueOf(value & 0x0000_0000_FFFF_FFFFL);
    }

    @Specialization
    protected Object doLargeInteger(LargeInteger value) {
        long lValue = value.longValue() & 0x0000_0000_FFFF_FFFFL;
        if (lValue > Integer.MAX_VALUE) {
            return LargeInteger.valueOf(lValue);
        }
        return (int) lValue;
    }

    @Specialization
    protected int doBoolean(boolean value) {
        return doBooleanStatic(value);
    }

    private static int doBooleanStatic(boolean value) {
        return JSRuntime.booleanToNumber(value);
    }

    @Specialization(guards = {"!isDoubleLargerThan2e32(value)"})
    protected double doDoubleFitsInt32Negative(double value) {
        return JSRuntime.toUInt32((long) value);
    }

    @Specialization(guards = {"isDoubleLargerThan2e32(value)", "isDoubleRepresentableAsLong(value)"})
    protected double doDoubleRepresentableAsLong(double value) {
        return JSRuntime.toUInt32NoTruncate(value);
    }

    @Specialization(guards = {"isDoubleLargerThan2e32(value)", "!isDoubleRepresentableAsLong(value)"})
    protected double doDouble(double value) {
        return JSRuntime.toUInt32(value);
    }

    @Specialization(guards = "isJSNull(value)")
    protected int doNull(@SuppressWarnings("unused") Object value) {
        return 0;
    }

    @Specialization(guards = "isUndefined(value)")
    protected int doUndefined(@SuppressWarnings("unused") Object value) {
        return 0;
    }

    @Specialization
    protected double doString(String value,
                    @Cached("create()") JSStringToNumberWithTrimNode stringToNumberNode) {
        return JSRuntime.toUInt32(stringToNumberNode.executeString(value));
    }

    private static double doStringStatic(String value) {
        return JSRuntime.toUInt32(JSRuntime.doubleValue(JSRuntime.stringToNumber(value)));
    }

    @Specialization
    protected final Number doSymbol(@SuppressWarnings("unused") Symbol value) {
        throw Errors.createTypeErrorCannotConvertToNumber("a Symbol value", this);
    }

    @Specialization(guards = "isJSObject(value)")
    protected double doJSObject(DynamicObject value,
                    @Cached("create()") JSToNumberNode toNumberNode) {
        return JSRuntime.toUInt32(toNumberNode.executeNumber(value));
    }

    @Specialization(guards = "isForeignObject(object)")
    protected static double doCrossLanguageToDouble(TruffleObject object,
                    @Cached("create()") JSUnboxOrGetNode unboxOrGetNode,
                    @Cached("create()") JSToUInt32Node toUInt32Node) {
        return ((Number) toUInt32Node.execute(unboxOrGetNode.executeWithTarget(object))).doubleValue();
    }

    @Specialization(guards = "isJavaNumber(value)")
    protected static double doJavaNumer(Object value) {
        return JSRuntime.toUInt32(JSRuntime.doubleValue((Number) value));
    }

    public abstract static class JSToUInt32WrapperNode extends JSUnaryNode {
        @Child private JSToUInt32Node toUInt32Node;

        protected JSToUInt32WrapperNode(JavaScriptNode operand) {
            super(operand);
        }

        public static JavaScriptNode create(JavaScriptNode child) {
            if (child instanceof JSConstantIntegerNode) {
                int value = ((JSConstantIntegerNode) child).executeInt(null);
                if (value < 0) {
                    long lValue = JSRuntime.toUInt32(value);
                    return JSRuntime.longIsRepresentableAsInt(lValue) ? JSConstantNode.createInt((int) lValue) : JSConstantNode.createDouble(lValue);
                }
                return child;
            } else if (child instanceof JSConstantDoubleNode) {
                double value = ((JSConstantDoubleNode) child).executeDouble(null);
                return JSConstantNode.createDouble(JSRuntime.toUInt32(value));
            } else if (child instanceof JSConstantBooleanNode) {
                boolean value = ((JSConstantBooleanNode) child).executeBoolean(null);
                return JSConstantNode.createInt(doBooleanStatic(value));
            } else if (child instanceof JSConstantUndefinedNode || child instanceof JSConstantNullNode) {
                return JSConstantNode.createInt(0);
            } else if (child instanceof JSConstantStringNode) {
                String value = ((JSConstantStringNode) child).executeString(null);
                return JSConstantNode.createDouble(doStringStatic(value));
            } else if (child instanceof JSToInt32Node) {
                JavaScriptNode operand = ((JSToInt32Node) child).getOperand();
                return JSToUInt32WrapperNodeGen.create(operand);
            }
            return JSToUInt32WrapperNodeGen.create(child);
        }

        @Specialization
        protected Object doDefault(Object value) {
            if (toUInt32Node == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toUInt32Node = insert(JSToUInt32Node.create());
            }
            return toUInt32Node.execute(value);
        }

        @Override
        protected JavaScriptNode copyUninitialized() {
            return JSToUInt32WrapperNodeGen.create(cloneUninitialized(getOperand()));
        }
    }
}
