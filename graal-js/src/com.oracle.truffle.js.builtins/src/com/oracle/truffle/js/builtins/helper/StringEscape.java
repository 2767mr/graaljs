/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.builtins.helper;

import java.util.*;

import com.oracle.truffle.api.CompilerDirectives.*;
import com.oracle.truffle.js.runtime.JSRuntime;

/**
 * String escape/unescape utility class. Used by B.2.1 escape() and B.2.2 unescape() methods as
 * defined in ECMAScript 5.1.
 *
 */
public class StringEscape {

    private static BitSet dontEncodeSet;

    private static synchronized void init() {
        if (dontEncodeSet == null) {
            dontEncodeSet = new BitSet(256);
            int i;
            for (i = 'a'; i <= 'z'; i++) {
                dontEncodeSet.set(i);
            }
            for (i = 'A'; i <= 'Z'; i++) {
                dontEncodeSet.set(i);
            }
            for (i = '0'; i <= '9'; i++) {
                dontEncodeSet.set(i);
            }
            dontEncodeSet.set('@');
            dontEncodeSet.set('*');
            dontEncodeSet.set('_');
            dontEncodeSet.set('+');
            dontEncodeSet.set('-');
            dontEncodeSet.set('.');
            dontEncodeSet.set('/');
        }
    }

    @TruffleBoundary
    public static String escape(String s) {
        boolean didEscape = false;
        StringBuffer out = new StringBuffer(s.length());

        init();
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (dontEncodeSet.get(c)) {
                out.append((char) c);
            } else {
                didEscape = true;
                out.append('%');
                if (c < 256) {
                    char ch = hexChar((c >> 4) & 0xF);
                    out.append(ch);
                    ch = hexChar(c & 0xF);
                    out.append(ch);
                } else {
                    out.append('u');
                    char ch = hexChar((c >> 12) & 0xF);
                    out.append(ch);
                    ch = hexChar((c >> 8) & 0xF);
                    out.append(ch);
                    ch = hexChar((c >> 4) & 0xF);
                    out.append(ch);
                    ch = hexChar(c & 0xF);
                    out.append(ch);
                }
            }
        }
        return didEscape ? out.toString() : s;
    }

    @TruffleBoundary
    public static String unescape(String string) {
        int len = string.length();
        StringBuilder builder = new StringBuilder();
        int k = 0;
        while (k < len) {
            char c = string.charAt(k);
            if (c == '%') {
                if (k <= (len - 6)) {
                    if (unescapeU0000(string, builder, k)) {
                        k += 6;
                        continue;
                    }
                }
                if (k <= (len - 3)) {
                    if (unescape00(string, builder, k)) {
                        k += 3;
                        continue;
                    }
                }
            }
            builder.append(c);
            k++;
        }
        return builder.toString();
    }

    private static boolean unescapeU0000(String string, StringBuilder builder, int k) {
        char c1 = string.charAt(k + 1);
        if (c1 == 'u') {
            char c2 = string.charAt(k + 2);
            char c3 = string.charAt(k + 3);
            char c4 = string.charAt(k + 4);
            char c5 = string.charAt(k + 5);
            if (JSRuntime.isHex(c2) && JSRuntime.isHex(c3) && JSRuntime.isHex(c4) && JSRuntime.isHex(c5)) {
                char newC = (char) (hexVal(c2) * 16 * 16 * 16 + hexVal(c3) * 16 * 16 + hexVal(c4) * 16 + hexVal(c5));
                builder.append(newC);
                return true;
            }
        }
        return false;
    }

    private static boolean unescape00(String string, StringBuilder builder, int k) {
        char c1 = string.charAt(k + 1);
        char c2 = string.charAt(k + 2);
        if (JSRuntime.isHex(c1) && JSRuntime.isHex(c2)) {
            char newC = (char) (hexVal(c1) * 16 + hexVal(c2));
            builder.append(newC);
            return true;
        }
        return false;
    }

    private static char hexChar(int value) {
        if (value < 10) {
            return (char) ('0' + value);
        } else {
            return (char) ('A' + value - 10);
        }
    }

    private static int hexVal(char c) {
        int value = JSRuntime.valueInHex(c);
        if (value < 0) {
            assert false : "not a hex character";
            return 0;
        }
        return value;
    }

}
