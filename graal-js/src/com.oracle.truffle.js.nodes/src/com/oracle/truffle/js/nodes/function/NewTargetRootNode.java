/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.function;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.js.runtime.AbstractJavaScriptLanguage;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.objects.Undefined;

public abstract class NewTargetRootNode extends JavaScriptRootNode {
    protected final CallTarget callTarget;

    @Child protected DirectCallNode callNode;

    protected NewTargetRootNode(CallTarget callTarget) {
        super(((RootCallTarget) callTarget).getRootNode().getLanguage(AbstractJavaScriptLanguage.class), ((RootCallTarget) callTarget).getRootNode().getSourceSection(), null);
        this.callTarget = callTarget;
    }

    public static JavaScriptRootNode createNewTargetConstruct(CallTarget callTarget) {
        return createNewTarget(callTarget, true);
    }

    public static JavaScriptRootNode createNewTargetCall(CallTarget callTarget) {
        return createNewTarget(callTarget, false);
    }

    private static JavaScriptRootNode createNewTarget(CallTarget callTarget, boolean construct) {
        return new InsertNewTargetRootNode(callTarget, construct);
    }

    public static JavaScriptRootNode createDropNewTarget(CallTarget callTarget) {
        return new DropNewTargetRootNode(callTarget);
    }

    @Override
    public boolean isCloningAllowed() {
        return true;
    }

    @Override
    protected boolean isCloneUninitializedSupported() {
        return true;
    }

    @Override
    public boolean isInternal() {
        return true;
    }

    @Override
    protected abstract JavaScriptRootNode cloneUninitialized();

    @Override
    @TruffleBoundary
    public String toString() {
        String callTargetName = ((RootCallTarget) callTarget).getRootNode().toString();
        return JSTruffleOptions.DetailedCallTargetNames ? JSRuntime.stringConcat("[NewTarget]", callTargetName) : callTargetName;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (callNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            this.callNode = insert(Truffle.getRuntime().createDirectCallNode(callTarget));
        }
        return doCall(frame);
    }

    protected abstract Object doCall(VirtualFrame frame);

    public static class InsertNewTargetRootNode extends NewTargetRootNode {
        private final boolean construct;

        protected InsertNewTargetRootNode(CallTarget callTarget, boolean construct) {
            super(callTarget);
            this.construct = construct;
        }

        private static Object[] copyAndInsertArgument(Object[] arguments, int insertPosition, Object newTarget) {
            final int insertLength = 1;
            Object[] newArguments = new Object[arguments.length + insertLength];
            System.arraycopy(arguments, 0, newArguments, 0, insertPosition);
            newArguments[insertPosition] = newTarget;
            System.arraycopy(arguments, insertPosition, newArguments, insertPosition + insertLength, arguments.length - insertPosition);
            return newArguments;
        }

        @Override
        protected Object doCall(VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            Object newTarget = construct ? JSArguments.getFunctionObject(frame.getArguments()) : Undefined.instance;
            Object[] newArguments = copyAndInsertArgument(arguments, JSArguments.RUNTIME_ARGUMENT_COUNT, newTarget);
            return callNode.call(newArguments);
        }

        @Override
        protected JavaScriptRootNode cloneUninitialized() {
            return new InsertNewTargetRootNode(callTarget, construct);
        }
    }

    public static class DropNewTargetRootNode extends NewTargetRootNode {
        protected DropNewTargetRootNode(CallTarget callTarget) {
            super(callTarget);
        }

        private static Object[] copyAndDropArgument(Object[] arguments, int dropPosition, int dropLength) {
            Object[] newArguments = new Object[arguments.length - dropLength];
            System.arraycopy(arguments, 0, newArguments, 0, dropPosition);
            System.arraycopy(arguments, dropPosition + dropLength, newArguments, dropPosition, arguments.length - dropPosition - dropLength);
            return newArguments;
        }

        @Override
        protected Object doCall(VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            Object[] newArguments = copyAndDropArgument(arguments, JSArguments.RUNTIME_ARGUMENT_COUNT, 1);
            return callNode.call(newArguments);
        }

        @Override
        protected JavaScriptRootNode cloneUninitialized() {
            return new DropNewTargetRootNode(callTarget);
        }
    }
}
