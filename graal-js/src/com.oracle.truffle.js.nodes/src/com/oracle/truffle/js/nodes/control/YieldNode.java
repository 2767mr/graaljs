/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.control;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.CreateIterResultObjectNode;
import com.oracle.truffle.js.nodes.access.GetIteratorNode;
import com.oracle.truffle.js.nodes.access.GetMethodNode;
import com.oracle.truffle.js.nodes.access.IteratorCloseNode;
import com.oracle.truffle.js.nodes.access.IteratorCompleteNode;
import com.oracle.truffle.js.nodes.access.IteratorNextNode;
import com.oracle.truffle.js.nodes.access.IteratorValueNode;
import com.oracle.truffle.js.nodes.access.JSWriteFrameSlotNode;
import com.oracle.truffle.js.nodes.access.WriteNode;
import com.oracle.truffle.js.nodes.control.ReturnNode.FrameReturnNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.UserScriptException;
import com.oracle.truffle.js.runtime.objects.Completion;
import com.oracle.truffle.js.runtime.objects.Undefined;

public class YieldNode extends JavaScriptNode implements ResumableNode, SuspendNode {

    @Child protected JavaScriptNode expression;
    @Child private CreateIterResultObjectNode createIterResultObjectNode;
    @Child protected JavaScriptNode yieldValue;
    @Child private ReturnNode returnNode;
    @Child private YieldResultNode generatorYieldNode;
    private final JSContext context;

    protected YieldNode(JSContext context, JavaScriptNode expression, JavaScriptNode yieldValue, ReturnNode returnNode, JSWriteFrameSlotNode writeYieldResultNode) {
        this.context = context;
        this.expression = expression;
        this.returnNode = returnNode;
        this.createIterResultObjectNode = CreateIterResultObjectNode.create(context);
        this.yieldValue = yieldValue;
        this.generatorYieldNode = writeYieldResultNode == null ? new ExceptionYieldResultNode() : new FrameYieldResultNode(writeYieldResultNode);
    }

    public static YieldNode createYield(JSContext context, JavaScriptNode expression, JavaScriptNode yieldValue, ReturnNode returnNode, JSWriteFrameSlotNode writeYieldResultNode) {
        return new YieldNode(context, expression, yieldValue, returnNode, writeYieldResultNode);
    }

    public static YieldNode createYieldStar(JSContext context, JavaScriptNode expression, JavaScriptNode yieldValue, ReturnNode returnNode, JSWriteFrameSlotNode writeYieldResultNode) {
        return new YieldStarNode(context, expression, yieldValue, returnNode, writeYieldResultNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = expression.execute(frame);
        DynamicObject iterNextObj = createIterResultObjectNode.execute(frame, value, false);
        return generatorYield(frame, iterNextObj);
    }

    protected final Object generatorYield(VirtualFrame frame, DynamicObject iterNextObj) {
        throw generatorYieldNode.generatorYield(frame, iterNextObj);
    }

    @Override
    public Object resume(VirtualFrame frame) {
        int index = getStateAsInt(frame);
        if (index == 0) {
            Object value = expression.execute(frame);
            DynamicObject iterNextObj = createIterResultObjectNode.execute(frame, value, false);
            setState(frame, 1);
            return generatorYield(frame, iterNextObj);
        } else {
            assert index == 1;
            setState(frame, 0);
            Object value = yieldValue.execute(frame);
            if (value instanceof Completion) {
                Completion completion = (Completion) value;
                value = completion.getValue();
                if (completion.isThrow()) {
                    return throwValue(value);
                } else {
                    assert completion.isReturn();
                    return returnValue(frame, value);
                }
            }
            return value;
        }
    }

    protected final Object throwValue(Object value) {
        throw UserScriptException.create(value, this);
    }

    protected final Object returnValue(VirtualFrame frame, Object value) {
        if (returnNode instanceof FrameReturnNode) {
            ((WriteNode) returnNode.expression).executeWrite(frame, value);
        }
        throw new ReturnException(value);
    }

    public abstract static class YieldResultNode extends JavaScriptBaseNode {
        public abstract YieldException generatorYield(VirtualFrame frame, Object value);
    }

    public static final class ExceptionYieldResultNode extends YieldResultNode {
        @Override
        public YieldException generatorYield(VirtualFrame frame, Object value) {
            throw new YieldException(value);
        }
    }

    public static final class FrameYieldResultNode extends YieldResultNode {
        @Child private JSWriteFrameSlotNode writeYieldValueNode;

        public FrameYieldResultNode(JSWriteFrameSlotNode writeYieldValueNode) {
            this.writeYieldValueNode = writeYieldValueNode;
        }

        @Override
        public YieldException generatorYield(VirtualFrame frame, Object value) {
            writeYieldValueNode.executeWrite(frame, value);
            throw YieldException.YIELD_NULL;
        }
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        JavaScriptNode expressionCopy = cloneUninitialized(expression);
        JavaScriptNode yieldValueCopy = cloneUninitialized(yieldValue);
        ReturnNode returnCopy = cloneUninitialized(returnNode);
        JSWriteFrameSlotNode writeYieldValueCopy = cloneUninitialized(generatorYieldNode instanceof FrameYieldResultNode ? ((FrameYieldResultNode) generatorYieldNode).writeYieldValueNode : null);
        if (this instanceof YieldStarNode) {
            return createYieldStar(context, expressionCopy, yieldValueCopy, returnCopy, writeYieldValueCopy);
        } else {
            return createYield(context, expressionCopy, yieldValueCopy, returnCopy, writeYieldValueCopy);
        }
    }
}

class YieldStarNode extends YieldNode {
    @Child private GetIteratorNode getIteratorNode;
    @Child private IteratorNextNode iteratorNextNode;
    @Child private IteratorCompleteNode iteratorCompleteNode;
    @Child private IteratorValueNode iteratorValueNode;
    @Child private GetMethodNode getThrowMethodNode;
    @Child private GetMethodNode getReturnMethodNode;
    @Child private JSFunctionCallNode callThrowNode;
    @Child private JSFunctionCallNode callReturnNode;
    @Child private IteratorCloseNode iteratorCloseNode;

    protected YieldStarNode(JSContext context, JavaScriptNode expression, JavaScriptNode yieldValue, ReturnNode returnNode, JSWriteFrameSlotNode writeYieldResultNode) {
        super(context, expression, yieldValue, returnNode, writeYieldResultNode);
        this.getIteratorNode = GetIteratorNode.create(context);
        this.iteratorNextNode = IteratorNextNode.create(context);
        this.iteratorCompleteNode = IteratorCompleteNode.create(context);
        this.iteratorValueNode = IteratorValueNode.create(context, null);
        this.getThrowMethodNode = GetMethodNode.create(context, null, "throw");
        this.getReturnMethodNode = GetMethodNode.create(context, null, "return");
        this.callThrowNode = JSFunctionCallNode.createCall();
        this.callReturnNode = JSFunctionCallNode.createCall();
        this.iteratorCloseNode = IteratorCloseNode.create(context);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        DynamicObject iterator = getIteratorNode.execute(expression.execute(frame));
        Object received = Undefined.instance;
        DynamicObject innerResult = iteratorNextNode.execute(iterator, received);
        if (iteratorCompleteNode.execute(innerResult)) {
            return iteratorValueNode.execute(innerResult);
        }
        return saveStateAndYield(frame, iterator, innerResult);
    }

    private Object saveStateAndYield(VirtualFrame frame, DynamicObject iterator, DynamicObject innerResult) {
        setState(frame, iterator);
        return generatorYield(frame, innerResult);
    }

    @Override
    public Object resume(VirtualFrame frame) {
        DynamicObject iterator = (DynamicObject) getState(frame);
        if (iterator == Undefined.instance) {
            return execute(frame);
        } else {
            setState(frame, Undefined.instance);
            Object received = yieldValue.execute(frame);
            if (!(received instanceof Completion)) {
                DynamicObject innerResult = iteratorNextNode.execute(iterator, received);
                if (iteratorCompleteNode.execute(innerResult)) {
                    return iteratorValueNode.execute(innerResult);
                }
                return saveStateAndYield(frame, iterator, innerResult);
            } else {
                Completion completion = (Completion) received;
                received = completion.getValue();
                if (completion.isThrow()) {
                    return resumeThrow(frame, iterator, received);
                } else {
                    assert completion.isReturn();
                    return resumeReturn(frame, iterator, received);
                }
            }
        }
    }

    private Object resumeReturn(VirtualFrame frame, DynamicObject iterator, Object received) {
        Object returnMethod = getReturnMethodNode.executeWithTarget(iterator);
        if (returnMethod == Undefined.instance) {
            return returnValue(frame, received);
        } else {
            DynamicObject innerReturnResult = callReturnMethod(iterator, received, returnMethod);
            if (iteratorCompleteNode.execute(innerReturnResult)) {
                return returnValue(frame, iteratorValueNode.execute(innerReturnResult));
            }
            return saveStateAndYield(frame, iterator, innerReturnResult);
        }
    }

    private Object resumeThrow(VirtualFrame frame, DynamicObject iterator, Object received) {
        Object throwMethod = getThrowMethodNode.executeWithTarget(iterator);
        if (throwMethod != Undefined.instance) {
            DynamicObject innerResult = callThrowMethod(iterator, received, throwMethod);
            if (iteratorCompleteNode.execute(innerResult)) {
                return returnValue(frame, iteratorValueNode.execute(innerResult));
            }
            return saveStateAndYield(frame, iterator, innerResult);
        } else {
            JSException error = Errors.createTypeError("yield* protocol violation: iterator does not have a throw method");
            iteratorCloseNode.executeAbrupt(iterator);
            throw error; // should happen inside iteratorClose
        }
    }

    private DynamicObject callThrowMethod(DynamicObject iterator, Object received, Object throwMethod) {
        Object innerResult = callThrowNode.executeCall(JSArguments.createOneArg(iterator, throwMethod, received));
        if (!JSRuntime.isObject(innerResult)) {
            throw Errors.createTypeErrorNotAnObject(innerResult);
        }
        return (DynamicObject) innerResult;
    }

    private DynamicObject callReturnMethod(DynamicObject iterator, Object received, Object returnMethod) {
        Object innerResult = callReturnNode.executeCall(JSArguments.createOneArg(iterator, returnMethod, received));
        if (!JSRuntime.isObject(innerResult)) {
            throw Errors.createTypeErrorNotAnObject(innerResult);
        }
        return (DynamicObject) innerResult;
    }
}
