/*
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.regex.tregex.parser.ast;

import com.oracle.truffle.regex.tregex.util.DebugUtil;

/**
 * An assertion that succeeds when encountered at the beginning or at the end of the string we are
 * searching in.
 * <p>
 * Corresponds to the <strong>^</strong> and <strong>$</strong> right-hand sides of the
 * <em>Assertion</em> goal symbol in the ECMAScript RegExp syntax.
 * <p>
 * {@link PositionAssertion} nodes are also used for state sets of NFA initial states, which is why
 * they can have a next-pointer ({@link #getNext()}), see
 * {@link RegexAST#getNFAAnchoredInitialState(int)}.
 */
public class PositionAssertion extends Term {

    /**
     * The position assertions supported by ECMAScript RegExps.
     */
    public enum Type {
        /**
         * The <strong>^</strong> assertion, which matches at the beginning of the string.
         */
        CARET,
        /**
         * The <strong>$</strong> assertion, which matches at the end of the string.
         */
        DOLLAR
    }

    /**
     * Indicates which position assertion this node represents.
     */
    public final Type type;
    private RegexASTNode next;

    /**
     * Creates a {@link PositionAssertion} node of the given kind.
     * 
     * @param type the kind of position assertion to create
     * @see PositionAssertion.Type
     */
    PositionAssertion(Type type) {
        this.type = type;
    }

    private PositionAssertion(PositionAssertion copy) {
        super(copy);
        type = copy.type;
    }

    @Override
    public PositionAssertion copy(RegexAST ast) {
        return ast.register(new PositionAssertion(this));
    }

    public RegexASTNode getNext() {
        return next;
    }

    public void setNext(RegexASTNode next) {
        this.next = next;
    }

    @Override
    public String toString() {
        switch (type) {
            case CARET:
                return "^";
            case DOLLAR:
                return "$";
        }
        throw new IllegalStateException();
    }

    @Override
    public DebugUtil.Table toTable() {
        return toTable("PositionAssertion " + toString());
    }
}
