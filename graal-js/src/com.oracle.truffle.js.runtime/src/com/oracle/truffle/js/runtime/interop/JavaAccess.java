/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime.interop;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.Objects;

import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSTruffleOptions;

/**
 * Java interop access check utility methods, mostly taken from Nashorn.
 */
public final class JavaAccess {
    private JavaAccess() {
    }

    static {
        assert JSTruffleOptions.NashornJavaInterop;
    }

    private static final AccessControlContext NO_PERMISSIONS_CONTEXT = createNoPermissionsContext();
    /**
     * Permission to use Java reflection/jsr292 from script code.
     */
    private static final String PERMISSION_JAVA_REFLECTION = "truffle.js.JavaReflection";

    private static AccessControlContext createNoPermissionsContext() {
        return new AccessControlContext(new ProtectionDomain[]{new ProtectionDomain(null, new Permissions())});
    }

    private static void checkPackageAccessInner(final SecurityManager sm, final String pkgName) {
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            @Override
            public Void run() {
                sm.checkPackageAccess(pkgName);
                return null;
            }
        }, NO_PERMISSIONS_CONTEXT);
    }

    /**
     * Checks that the given package can be accessed from no permissions context.
     *
     * @param sm current security manager instance
     * @param fullName fully qualified package name
     * @throw SecurityException if not accessible
     */
    public static void checkPackageAccess(final SecurityManager sm, final String fullName) {
        Objects.requireNonNull(sm);
        final int index = fullName.lastIndexOf('.');
        if (index != -1) {
            final String pkgName = fullName.substring(0, index);
            checkPackageAccessInner(sm, pkgName);
        }
    }

    /**
     * Returns true if the class is either not public, or it resides in a package with restricted
     * access.
     *
     * @param clazz the class to test
     * @return true if the class is either not public, or it resides in a package with restricted
     *         access.
     */
    public static boolean isRestrictedClass(final Class<?> clazz) {
        if (!Modifier.isPublic(clazz.getModifiers())) {
            // Non-public classes are always restricted
            return true;
        }
        final SecurityManager sm = System.getSecurityManager();
        if (sm == null) {
            // No further restrictions if we don't have a security manager
            return false;
        }
        final String name = clazz.getName();
        final int i = name.lastIndexOf('.');
        if (i == -1) {
            // Classes in default package are never restricted
            return false;
        }
        final String pkgName = name.substring(0, i);
        // Do a package access check from within an access control context with no permissions
        try {
            checkPackageAccessInner(sm, pkgName);
        } catch (final SecurityException e) {
            return true;
        }
        return false;
    }

    public static boolean isReflectionClass(final Class<?> type) {
        // Class or ClassLoader subclasses
        if (type == Class.class || ClassLoader.class.isAssignableFrom(type)) {
            return true;
        }

        // package name check
        final String name = type.getName();
        return name.startsWith("java.lang.reflect.") || name.startsWith("java.lang.invoke.") || name.startsWith("java.beans.");
    }

    public static void checkReflectionAccess(final Class<?> clazz, final boolean isStatic, final boolean classFilterPresent) {
        if (classFilterPresent && isReflectiveCheckNeeded(clazz, isStatic)) {
            throw Errors.createTypeError("Java reflection not supported when class filter is present");
        }

        final SecurityManager sm = System.getSecurityManager();
        if (sm != null && isReflectiveCheckNeeded(clazz, isStatic)) {
            checkReflectionPermission(sm);
        }
    }

    public static boolean isReflectiveCheckNeeded(final Class<?> type, final boolean isStatic) {
        // special handling for Proxy subclasses
        if (Proxy.class.isAssignableFrom(type)) {
            if (Proxy.isProxyClass(type)) {
                // real Proxy class - filter only static access
                return isStatic;
            }

            // fake Proxy subclass - filter it always!
            return true;
        }

        // check for any other reflective Class
        return isReflectionClass(type);
    }

    private static void checkReflectionPermission(final SecurityManager sm) {
        sm.checkPermission(new RuntimePermission(PERMISSION_JAVA_REFLECTION));
    }

    /**
     * Checks that the given Class can be accessed from no permissions context.
     *
     * @param clazz Class object
     * @throws SecurityException if not accessible
     */
    public static void checkPackageAccess(final Class<?> clazz) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            Class<?> bottomClazz = clazz;
            while (bottomClazz.isArray()) {
                bottomClazz = bottomClazz.getComponentType();
            }
            checkPackageAccess(sm, bottomClazz.getName());
        }
    }

    /**
     * Checks that the given Class can be accessed from no permissions context.
     *
     * @param clazz Class object
     * @return true if package is accessible, false otherwise
     */
    private static boolean isAccessiblePackage(final Class<?> clazz) {
        try {
            checkPackageAccess(clazz);
            return true;
        } catch (final SecurityException se) {
            return false;
        }
    }

    /**
     * Checks that the given Class is public and it can be accessed from no permissions context.
     *
     * @param clazz Class object to check
     * @return true if Class is accessible, false otherwise
     */
    public static boolean isAccessibleClass(final Class<?> clazz) {
        return Modifier.isPublic(clazz.getModifiers()) && isAccessiblePackage(clazz);
    }
}
