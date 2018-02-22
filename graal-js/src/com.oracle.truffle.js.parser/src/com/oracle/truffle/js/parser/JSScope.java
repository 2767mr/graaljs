/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Scope;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameUtil;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.instrumentation.InstrumentableFactory.WrapperNode;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.KeyInfo;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.MessageResolution;
import com.oracle.truffle.api.interop.Resolve;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.java.JavaInterop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.NodeVisitor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.access.JSWriteFrameSlotNode;
import com.oracle.truffle.js.nodes.access.ScopeFrameNode;
import com.oracle.truffle.js.nodes.arguments.AccessIndexedArgumentNode;
import com.oracle.truffle.js.nodes.control.AbstractBlockNode;
import com.oracle.truffle.js.nodes.function.BlockScopeNode;
import com.oracle.truffle.js.nodes.function.BlockScopeNode.FrameBlockScopeNode;
import com.oracle.truffle.js.nodes.function.FunctionBodyNode;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.objects.Dead;
import com.oracle.truffle.js.runtime.objects.Undefined;

public abstract class JSScope {

    protected final Node node;
    protected final MaterializedFrame mFrame;

    private static final String THIS_SLOT_ID = "<this>";
    private static final String THIS_NAME = "this";

    public JSScope(Node node, MaterializedFrame frame) {
        this.node = node;
        this.mFrame = frame;
    }

    public static Iterable<Scope> createLocalScopes(Node node, MaterializedFrame frame) {
        final JSScope scope = createScope(node, frame);
        return new Iterable<Scope>() {
            @Override
            public Iterator<Scope> iterator() {
                return new Iterator<Scope>() {
                    private JSScope previousScope;
                    private JSScope nextScope = scope;

                    @Override
                    public boolean hasNext() {
                        if (nextScope == null) {
                            nextScope = previousScope.findParent();
                        }
                        return nextScope != null;
                    }

                    @Override
                    public Scope next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        Scope vscope = nextScope.toScope(frame);
                        previousScope = nextScope;
                        nextScope = null;
                        return vscope;
                    }
                };
            }
        };
    }

    public static Iterable<Scope> createGlobalScopes(JSRealm realm) {
        Scope globalLexicalScope = Scope.newBuilder("global", createVariablesMapObject(realm.getGlobalScope(), null)).build();
        Scope globalVarScope = Scope.newBuilder("global", realm.getGlobalObject()).build();
        return Arrays.asList(globalLexicalScope, globalVarScope);
    }

    protected final Scope toScope(MaterializedFrame frame) {
        return Scope.newBuilder(getName(), getVariables(frame)).node(getNode()).arguments(getArguments(frame)).build();
    }

    protected abstract String getName();

    protected abstract Node getNode();

    protected abstract Object getVariables(Frame frame);

    protected abstract Object getArguments(Frame frame);

    protected abstract JSScope findParent();

    private static JSScope createScope(Node node, MaterializedFrame frame) {
        if (frame != null) {
            if (ScopeFrameNode.isBlockScopeFrame(frame)) {
                for (Node n = node; n != null; n = n.getParent()) {
                    if (n instanceof FrameBlockScopeNode) {
                        FrameBlockScopeNode blockScopeNode = (FrameBlockScopeNode) n;
                        if (frame.getFrameDescriptor() == blockScopeNode.getFrameDescriptor()) {
                            return new JSBlockScope(blockScopeNode, frame);
                        }
                    }
                }
            }
        } else {
            for (Node n = node; n != null; n = n.getParent()) {
                if (n instanceof FrameBlockScopeNode) {
                    return new JSBlockScope((FrameBlockScopeNode) n, frame);
                }
            }
        }
        return new JSFunctionScope(node, frame);
    }

    protected static Object createVariablesMapObject(MaterializedFrame frame, Object[] args) {
        FrameDescriptor fDesc = frame.getFrameDescriptor();
        Map<String, Variable> slotMap = new HashMap<>();
        for (FrameSlot slot : fDesc.getSlots()) {
            if (slot.getIdentifier().equals(THIS_SLOT_ID)) {
                slotMap.put(THIS_NAME, new FrameSlotVariable(slot));
                continue;
            }
            if (JSFrameUtil.isInternal(slot)) {
                continue;
            }
            if (isUnsetFrameSlot(frame, slot)) {
                continue;
            }
            String name = slot.getIdentifier().toString();
            slotMap.put(name, new FrameSlotVariable(slot));
        }
        return new VariablesMapObject(slotMap, args, frame);
    }

    private static boolean isUnsetFrameSlot(MaterializedFrame frame, FrameSlot slot) {
        if (frame.isObject(slot)) {
            Object value = FrameUtil.getObjectSafe(frame, slot);
            if (value == null || value == Dead.instance() || value instanceof Frame) {
                return true;
            }
        }
        return false;
    }

    protected Node findParentScopeNode() {
        Node parent = node;
        // Find the current scope root:
        while (!(parent == null || parent instanceof BlockScopeNode || parent instanceof RootNode)) {
            parent = parent.getParent();
        }
        if (parent != null) {
            // Find the next scope root:
            parent = parent.getParent();
            while (!(parent == null || parent instanceof BlockScopeNode || parent instanceof RootNode)) {
                parent = parent.getParent();
            }
        }
        return parent;
    }

    abstract static class Variable {
        public abstract Object get(Frame frame, Object[] args);

        public abstract void set(Frame frame, Object[] args, Object value);

        public abstract boolean isWritable();
    }

    static final class FrameSlotVariable extends Variable {
        private final FrameSlot slot;
        private final boolean writable;

        FrameSlotVariable(FrameSlot slot) {
            this.slot = slot;
            this.writable = !JSFrameUtil.isConst(slot) && !JSFrameUtil.isInternal(slot);
        }

        @Override
        public Object get(Frame frame, Object[] args) {
            assert frame.getFrameDescriptor() == slot.getFrameDescriptor();
            return frame.getValue(slot);
        }

        @Override
        public void set(Frame frame, Object[] args, Object value) {
            assert frame.getFrameDescriptor() == slot.getFrameDescriptor();
            if (frame.isInt(slot) && value instanceof Integer) {
                frame.setInt(slot, (int) value);
            } else if (frame.isDouble(slot) && value instanceof Double) {
                frame.setDouble(slot, (double) value);
            } else if (frame.isBoolean(slot) && value instanceof Boolean) {
                frame.setBoolean(slot, (boolean) value);
            } else {
                frame.setObject(slot, value);
            }
        }

        @Override
        public boolean isWritable() {
            return writable;
        }
    }

    static final class ArgumentVariable extends Variable {
        private final int index;

        ArgumentVariable(int index) {
            this.index = index;
        }

        @Override
        public Object get(Frame frame, Object[] args) {
            if (index >= args.length) {
                return Undefined.instance;
            }
            return args[index];
        }

        @Override
        public void set(Frame frame, Object[] args, Object value) {
        }

        @Override
        public boolean isWritable() {
            return false;
        }
    }

    public static class JSBlockScope extends JSScope {

        private final FrameBlockScopeNode blockScopeNode;

        protected JSBlockScope(FrameBlockScopeNode blockScopeNode, MaterializedFrame frame) {
            super(blockScopeNode, frame);
            this.blockScopeNode = blockScopeNode;
            assert frame == null || frame.getFrameDescriptor() == blockScopeNode.getFrameDescriptor();
        }

        @Override
        protected String getName() {
            String locationStr = "";
            if (node.getSourceSection() != null) {
                locationStr = " at " + node.getSourceSection().getSource().getName() + ":" + node.getSourceSection().getStartLine();
            }
            return "JS block scope" + locationStr;
        }

        @Override
        protected Node getNode() {
            return blockScopeNode;
        }

        @Override
        protected Object getVariables(Frame frame) {
            MaterializedFrame f = mFrame != null ? mFrame : frame.materialize();
            assert f.getFrameDescriptor() == blockScopeNode.getFrameDescriptor();
            return createVariablesMapObject(f, null);
        }

        @Override
        protected Object getArguments(Frame frame) {
            return null; // Block scope does not have a concept of arguments
        }

        @Override
        protected JSScope findParent() {
            if (mFrame == null) {
                return null;
            }
            Node parent = findParentScopeNode();
            if (parent == null) {
                return null;
            }
            Frame parentFrame = (Frame) FrameUtil.getObjectSafe(mFrame, ScopeFrameNode.PARENT_SCOPE_SLOT);
            return JSScope.createScope(parent, parentFrame.materialize());
        }
    }

    public static class JSFunctionScope extends JSScope {

        private final RootNode rootNode;

        protected JSFunctionScope(Node node, MaterializedFrame frame) {
            super(node, frame);
            this.rootNode = findRootNode(node);
            assert frame == null || rootNode == null || frame.getFrameDescriptor() == rootNode.getFrameDescriptor();
        }

        private static RootNode findRootNode(Node node) {
            Node n = node;
            while (!(n instanceof RootNode) && (n != null)) {
                n = n.getParent();
            }
            return (RootNode) n;
        }

        @Override
        protected String getName() {
            if (rootNode == null) {
                return "unknown";
            }
            return rootNode.getName();
        }

        @Override
        protected Node getNode() {
            return rootNode;
        }

        @Override
        protected Object getVariables(Frame frame) {
            if (mFrame == null) {
                return new VariablesMapObject(Collections.emptyMap(), null, null);
            }
            return createVariablesMapObject(mFrame, null);
        }

        @Override
        protected Object getArguments(Frame frame) {
            if (rootNode == null || mFrame == null) {
                return null;
            }
            return new VariablesMapObject(collectArgs(rootNode), mFrame.getArguments(), mFrame);
        }

        private static Map<String, ? extends Variable> collectArgs(Node block) {
            Map<String, Variable> args = new LinkedHashMap<>(4);
            NodeUtil.forEachChild(block, new NodeVisitor() {

                private JSWriteFrameSlotNode wn; // The current write node containing a slot

                @Override
                public boolean visit(Node node) {
                    if (node instanceof JSWriteFrameSlotNode) {
                        wn = (JSWriteFrameSlotNode) node;
                        boolean all = NodeUtil.forEachChild(node, this);
                        wn = null;
                        return all;
                    } else if (wn != null && (node instanceof AccessIndexedArgumentNode)) {
                        FrameSlot slot = wn.getFrameSlot();
                        if (!JSFrameUtil.isInternal(slot)) {
                            String name = Objects.toString(slot.getIdentifier());
                            int argIndex = JSArguments.RUNTIME_ARGUMENT_COUNT + ((AccessIndexedArgumentNode) node).getIndex();
                            assert !args.containsKey(name) : name + " argument exists already.";
                            args.put(name, new ArgumentVariable(argIndex));
                        }
                        return true;
                    } else if (!(node instanceof JavaScriptBaseNode) || (node instanceof AbstractBlockNode) || (node instanceof FunctionBodyNode) || (node instanceof WrapperNode)) {
                        // Visit children of blocks or unknown nodes
                        return NodeUtil.forEachChild(node, this);
                    } else {
                        // Visit a next sibling
                        return true;
                    }
                }
            });
            return args;
        }

        @Override
        protected JSScope findParent() {
            if (mFrame == null) {
                return null;
            }
            assert findParentScopeNode() == null;
            // TODO find captured scope in parent function, if any
            return null;
        }
    }

    static Object getInteropValue(Object value) {
        if (JSRuntime.isLazyString(value)) {
            return value.toString();
        } else {
            return JavaInterop.asTruffleValue(value);
        }
    }

    static final class VariablesMapObject implements TruffleObject {

        final Map<String, ? extends Variable> slots;
        final Object[] args;
        final Frame frame;

        private VariablesMapObject(Map<String, ? extends Variable> slots, Object[] args, Frame frame) {
            this.slots = slots;
            this.args = args;
            this.frame = frame;
        }

        @Override
        public ForeignAccess getForeignAccess() {
            return VariablesMapMessageResolutionForeign.ACCESS;
        }

        public static boolean isInstance(TruffleObject obj) {
            return obj instanceof VariablesMapObject;
        }

        @MessageResolution(receiverType = VariablesMapObject.class)
        static final class VariablesMapMessageResolution {

            @Resolve(message = "KEYS")
            abstract static class VarsMapKeysNode extends Node {

                @TruffleBoundary
                public Object access(VariablesMapObject varMap) {
                    return new VariableNamesObject(varMap.slots.keySet());
                }
            }

            @Resolve(message = "KEY_INFO")
            abstract static class VarsMapKeyInfoNode extends Node {

                @TruffleBoundary
                public Object access(VariablesMapObject varMap, Object key) {
                    Variable slot = varMap.slots.get(key);
                    if (slot == null) {
                        return 0;
                    }

                    return KeyInfo.newBuilder().setReadable(true).setWritable(slot.isWritable()).setInvocable(false).setInternal(false).build();
                }
            }

            @Resolve(message = "READ")
            abstract static class VarsMapReadNode extends Node {

                @TruffleBoundary
                public Object access(VariablesMapObject varMap, String name) {
                    if (varMap.frame == null) {
                        throw UnsupportedMessageException.raise(Message.READ);
                    }
                    Variable slot = varMap.slots.get(name);
                    if (slot == null) {
                        throw UnknownIdentifierException.raise(name);
                    } else {
                        Object value = slot.get(varMap.frame, varMap.args);
                        return getInteropValue(value);
                    }
                }
            }

            @Resolve(message = "WRITE")
            abstract static class VarsMapWriteNode extends Node {

                @TruffleBoundary
                public Object access(VariablesMapObject varMap, String name, Object value) {
                    if (varMap.frame == null) {
                        throw UnsupportedMessageException.raise(Message.WRITE);
                    }
                    Variable slot = varMap.slots.get(name);
                    if (slot == null) {
                        throw UnknownIdentifierException.raise(name);
                    } else if (slot.isWritable()) {
                        slot.set(varMap.frame, varMap.args, value);
                        return value;
                    } else {
                        throw UnsupportedMessageException.raise(Message.WRITE);
                    }
                }
            }
        }
    }

    static final class VariableNamesObject implements TruffleObject {

        final List<String> names;

        private VariableNamesObject(Set<String> names) {
            this.names = new ArrayList<>(names.size());
            this.names.addAll(names);
        }

        @Override
        public ForeignAccess getForeignAccess() {
            return VariableNamesMessageResolutionForeign.ACCESS;
        }

        public static boolean isInstance(TruffleObject obj) {
            return obj instanceof VariableNamesObject;
        }

        @MessageResolution(receiverType = VariableNamesObject.class)
        static final class VariableNamesMessageResolution {

            @Resolve(message = "HAS_SIZE")
            abstract static class VarNamesHasSizeNode extends Node {

                @SuppressWarnings("unused")
                public Object access(VariableNamesObject varNames) {
                    return true;
                }
            }

            @Resolve(message = "GET_SIZE")
            abstract static class VarNamesGetSizeNode extends Node {

                public Object access(VariableNamesObject varNames) {
                    return varNames.names.size();
                }
            }

            @Resolve(message = "READ")
            abstract static class VarNamesReadNode extends Node {

                @TruffleBoundary
                public Object access(VariableNamesObject varNames, int index) {
                    try {
                        return varNames.names.get(index);
                    } catch (IndexOutOfBoundsException ioob) {
                        throw UnknownIdentifierException.raise(Integer.toString(index));
                    }
                }
            }
        }
    }
}
