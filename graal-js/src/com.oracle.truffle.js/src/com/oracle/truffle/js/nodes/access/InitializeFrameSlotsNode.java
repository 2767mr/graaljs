/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.access;

import java.util.Objects;
import java.util.Set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.objects.Dead;
import com.oracle.truffle.js.runtime.objects.Undefined;

/*
 * Sets the initial value of frame slots of locals with a temporal dead zone.
 */
public class InitializeFrameSlotsNode extends JavaScriptNode {

    @Child private ScopeFrameNode scopeFrameNode;
    @CompilationFinal(dimensions = 1) private final int[] slots;

    protected InitializeFrameSlotsNode(ScopeFrameNode scopeFrameNode, int[] slots) {
        this.scopeFrameNode = Objects.requireNonNull(scopeFrameNode);
        this.slots = Objects.requireNonNull(slots);
    }

    @ExplodeLoop
    @Override
    public Object execute(VirtualFrame frame) {
        Frame scopeFrame = scopeFrameNode.executeFrame(frame);
        for (int slot : slots) {
            initializeSlot(scopeFrame, slot);
        }
        return Undefined.instance;
    }

    static void initializeSlot(Frame scopeFrame, int slot) {
        assert JSFrameUtil.hasTemporalDeadZone(scopeFrame.getFrameDescriptor(), slot) : slot;
        scopeFrame.setObject(slot, Dead.instance());
    }

    public static JavaScriptNode create(ScopeFrameNode scopeFrameNode, int[] slots) {
        if (slots.length == 1) {
            return new InitializeFrameSlotNode(scopeFrameNode, slots[0]);
        }
        return new InitializeFrameSlotsNode(scopeFrameNode, slots);
    }

    public static JavaScriptNode createRange(ScopeFrameNode scopeFrameNode, int start, int end) {
        return new InitializeFrameSlotRangeNode(scopeFrameNode, start, end);
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return create(scopeFrameNode, slots);
    }

    @Override
    public boolean isResultAlwaysOfType(Class<?> clazz) {
        return clazz == Undefined.class;
    }
}

class InitializeFrameSlotNode extends JavaScriptNode {

    @Child private ScopeFrameNode scopeFrameNode;
    private final int slot;

    protected InitializeFrameSlotNode(ScopeFrameNode scopeFrameNode, int slot) {
        this.slot = slot;
        this.scopeFrameNode = Objects.requireNonNull(scopeFrameNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Frame scopeFrame = scopeFrameNode.executeFrame(frame);
        InitializeFrameSlotsNode.initializeSlot(scopeFrame, slot);
        return Undefined.instance;
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return new InitializeFrameSlotNode(scopeFrameNode, slot);
    }

    @Override
    public boolean isResultAlwaysOfType(Class<?> clazz) {
        return clazz == Undefined.class;
    }
}

class InitializeFrameSlotRangeNode extends JavaScriptNode {

    private final int start;
    private final int end;
    @Child private ScopeFrameNode scopeFrameNode;

    protected InitializeFrameSlotRangeNode(ScopeFrameNode scopeFrameNode, int start, int end) {
        this.start = start;
        this.end = end;
        this.scopeFrameNode = Objects.requireNonNull(scopeFrameNode);
    }

    @ExplodeLoop
    @Override
    public Object execute(VirtualFrame frame) {
        Frame scopeFrame = scopeFrameNode.executeFrame(frame);
        for (int slot = start; slot < end; slot++) {
            InitializeFrameSlotsNode.initializeSlot(scopeFrame, slot);
        }
        return Undefined.instance;
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return new InitializeFrameSlotRangeNode(scopeFrameNode, start, end);
    }

    @Override
    public boolean isResultAlwaysOfType(Class<?> clazz) {
        return clazz == Undefined.class;
    }
}
