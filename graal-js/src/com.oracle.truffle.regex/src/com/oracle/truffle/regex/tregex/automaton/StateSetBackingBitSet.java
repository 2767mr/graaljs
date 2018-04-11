/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.regex.tregex.automaton;

import com.oracle.truffle.regex.util.CompilationFinalBitSet;

import java.util.PrimitiveIterator;

public class StateSetBackingBitSet implements StateSetBackingSet {

    private CompilationFinalBitSet bitSet;

    public StateSetBackingBitSet() {
    }

    private StateSetBackingBitSet(StateSetBackingBitSet copy) {
        bitSet = copy.bitSet == null ? null : copy.bitSet.copy();
    }

    @Override
    public StateSetBackingSet copy() {
        return new StateSetBackingBitSet(this);
    }

    @Override
    public void create(int stateIndexSize) {
        assert bitSet == null;
        bitSet = new CompilationFinalBitSet(stateIndexSize);
    }

    @Override
    public boolean isActive() {
        return bitSet != null;
    }

    @Override
    public boolean contains(short id) {
        return bitSet.get(id);
    }

    @Override
    public boolean add(short id) {
        if (bitSet.get(id)) {
            return false;
        }
        bitSet.set(id);
        return true;
    }

    @Override
    public void addBatch(short id) {
        bitSet.set(id);
    }

    @Override
    public void addBatchFinish() {
    }

    @Override
    public void replace(short oldId, short newId) {
        bitSet.clear(oldId);
        bitSet.set(newId);
    }

    @Override
    public boolean remove(short id) {
        if (bitSet.get(id)) {
            bitSet.clear(id);
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        bitSet.clear();
    }

    @Override
    public boolean isDisjoint(StateSetBackingSet other) {
        return bitSet.isDisjoint(((StateSetBackingBitSet) other).bitSet);
    }

    @Override
    public PrimitiveIterator.OfInt iterator() {
        return bitSet.iterator();
    }

    @Override
    public int hashCode() {
        return bitSet == null ? 0 : bitSet.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof StateSetBackingBitSet && bitSet.equals(((StateSetBackingBitSet) obj).bitSet);
    }
}
