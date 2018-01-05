/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

var assert = require('assert');
var module = require('./_unit');

describe('Object', function () {
    describe('Get by name', function () {
        it('should return simple properties by name', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_GetByName(o, "a"), 42);
            assert.strictEqual(module.Object_GetByName(o, "b"), 100);
        });
        it('should return protoype properties by name', function () {
            var oParent = {a: 42, b: 100};
            var oChild = Object.create(oParent);
            assert.strictEqual(module.Object_GetByName(oChild, "a"), 42);
            assert.strictEqual(module.Object_GetByName(oChild, "b"), 100);
        });
        it('should return value when given a non-string key', function () {
            var o = {123: 456};
            assert.strictEqual(module.Object_GetByName(o, 123), 456);
        });
    });
    describe('Get by index', function () {
        it('should return simple properties by index', function () {
            var o = {};
            o[0] = 1000;
            o[1] = 1001;
            assert.strictEqual(module.Object_GetByIndex(o, 0), 1000);
            assert.strictEqual(module.Object_GetByIndex(o, 1), 1001);
        });
    });
    describe('Combine indexed and named properties', function () {
        it('should return simple properties by index and name', function () {
            var o = {a: 42, b: 43};
            o[0] = 1000;
            o[1] = 1001;
            assert.strictEqual(module.Object_GetByIndex(o, 0), 1000);
            assert.strictEqual(module.Object_GetByIndex(o, 1), 1001);
            assert.strictEqual(module.Object_GetByName(o, "a"), 42);
            assert.strictEqual(module.Object_GetByName(o, "b"), 43);
        });
    });
    describe('Set by name', function () {
        it('should set simple properties by name', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_SetByName(o, "c", 200), true);
            assert.strictEqual(module.Object_SetByName(o, "d", 300), true);
            assert.strictEqual(o.c, 200);
            assert.strictEqual(o.d, 300);
        });
        it('should set a property when given a non-string key', function () {
            var o = {};
            assert.strictEqual(module.Object_SetByName(o, 654, 321), true);
            assert.strictEqual(o[654], 321);
        });
    });
    describe('Set by index', function () {
        it('should set simple properties by name', function () {
            var o = [0, 1, 2, 3, 4, 5];
            assert.strictEqual(module.Object_SetByIndex(o, 0, 66), true);
            assert.strictEqual(module.Object_SetByIndex(o, 5, 67), true);
            assert.strictEqual(o[0], 66);
            assert.strictEqual(o[1], 1);
            assert.strictEqual(o[2], 2);
            assert.strictEqual(o[3], 3);
            assert.strictEqual(o[4], 4);
            assert.strictEqual(o[5], 67);
        });
    });
    describe('GetOwnPropertyNames', function () {
        it('should return an empty array for {}', function () {
            var names = module.Object_GetOwnPropertyNames({});
            assert.strictEqual(names.length, 0);
        });
        it('should not return Symbol properties', function () {
            var o = {};
            o[Symbol('foo')] = 'bar';
            var names = module.Object_GetOwnPropertyNames(o);
            assert.strictEqual(names.length, 0);
        });
        it('should return ["a", "b"] for {a:undefined, b:null}', function () {
            var names = module.Object_GetOwnPropertyNames({a: undefined, b: null});
            assert.strictEqual(names.length, 2);
            assert.notStrictEqual(names.indexOf("a"), -1);
            assert.notStrictEqual(names.indexOf("b"), -1);
        });
        it('should return [1, 2] for {1:false, 2:0}', function () {
            var names = module.Object_GetOwnPropertyNames({1: false, 2: 0});
            assert.strictEqual(names.length, 2);
            assert.strictEqual(names.indexOf("1"), -1);
            assert.strictEqual(names.indexOf("2"), -1);
            assert.notStrictEqual(names.indexOf(1), -1);
            assert.notStrictEqual(names.indexOf(2), -1);
        });
        it('should not return the prototype\'s properties', function () {
            var oProto = {a: 123};
            var oChild = Object.create(oProto);
            oProto.b = 42;
            oChild.c = 666;
            var names = module.Object_GetOwnPropertyNames(oChild);
            assert.strictEqual(names.length, 1);
            assert.strictEqual(names[0], "c");
        });
        it('should not return non-enumerable properties', function () {
            var o = {};
            Object.defineProperty(o, 'x', {enumerable: false});
            var names = module.Object_GetOwnPropertyNames(o);
            assert.strictEqual(names.length, 0);
        });
    });
    describe('GetPropertyNames', function () {
        it('should return an empty array for {}', function () {
            var names = module.Object_GetPropertyNames({});
            assert.strictEqual(names.length, 0);
        });
        it('should not return Symbol properties', function () {
            var o = {};
            o[Symbol('foo')] = 'bar';
            var names = module.Object_GetPropertyNames(o);
            assert.strictEqual(names.length, 0);
        });
        it('should return ["a", "b"] for {a:undefined, b:null}', function () {
            var names = module.Object_GetPropertyNames({a: undefined, b: null});
            assert.strictEqual(names.length, 2);
            assert.notStrictEqual(names.indexOf("a"), -1);
            assert.notStrictEqual(names.indexOf("b"), -1);
        });
        it('should return [1, 2] for {1:false, 2:0}', function () {
            var names = module.Object_GetPropertyNames({1: false, 2: 0});
            assert.strictEqual(names.length, 2);
            assert.strictEqual(names.indexOf("1"), -1);
            assert.strictEqual(names.indexOf("2"), -1);
            assert.notStrictEqual(names.indexOf(1), -1);
            assert.notStrictEqual(names.indexOf(2), -1);
        });
        it('should return also the prototype\'s properties', function () {
            var oProto = {a: 123};
            var oChild = Object.create(oProto);
            oProto.b = 42;
            oChild.c = 666;
            var names = module.Object_GetPropertyNames(oChild);
            assert.strictEqual(names.length, 3);
            assert.strictEqual(names.indexOf("a") >= 0, true);
            assert.strictEqual(names.indexOf("b") >= 0, true);
            assert.strictEqual(names.indexOf("c") >= 0, true);
        });
    });
    describe('Has by name', function () {
        it('querying simple properties by name', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_HasByName(o, "a"), true);
            assert.strictEqual(module.Object_HasByName(o, "b"), true);
            assert.strictEqual(module.Object_HasByName(o, "c"), false);
        });
        it('should accept non-string keys', function () {
            var o = {111: 222};
            assert.strictEqual(module.Object_HasByName(o, 111), true);
            assert.strictEqual(module.Object_HasByName(o, 222), false);
        });
    });
    describe('HasOwnProperty', function () {
        it('should return true for an existing own property', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_HasOwnProperty(o, "a"), true);
            assert.strictEqual(module.Object_HasOwnProperty(o, "b"), true);
        });
        it('should return false for a non-existing property', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_HasOwnProperty(o, "c"), false);
            assert.strictEqual(module.Object_HasOwnProperty(o, "d"), false);
        });
        it('should return false for an existing prototype property', function () {
            var oParent = {a: 123};
            var oChild = Object.create(oParent);
            oParent.b = 42;
            oChild.c = 666;
            assert.strictEqual(module.Object_HasOwnProperty(oChild, "a"), false);
            assert.strictEqual(module.Object_HasOwnProperty(oChild, "b"), false);
            assert.strictEqual(module.Object_HasOwnProperty(oChild, "c"), true);
            assert.strictEqual(module.Object_HasOwnProperty(oParent, "a"), true);
            assert.strictEqual(module.Object_HasOwnProperty(oParent, "b"), true);
            assert.strictEqual(module.Object_HasOwnProperty(oParent, "c"), false);
        });
    });
    describe('HasRealNamedProperty', function () {
        it('should return true for an existing own property', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_HasRealNamedProperty(o, "a"), true);
            assert.strictEqual(module.Object_HasRealNamedProperty(o, "b"), true);
        });
        it('should return false for a non-existing property', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_HasRealNamedProperty(o, "c"), false);
            assert.strictEqual(module.Object_HasRealNamedProperty(o, "d"), false);
        });
        it('should return false for an existing prototype property', function () {
            var oParent = {a: 123};
            var oChild = Object.create(oParent);
            oParent.b = 42;
            oChild.c = 666;
            assert.strictEqual(module.Object_HasRealNamedProperty(oChild, "a"), false);
            assert.strictEqual(module.Object_HasRealNamedProperty(oChild, "b"), false);
            assert.strictEqual(module.Object_HasRealNamedProperty(oChild, "c"), true);
            assert.strictEqual(module.Object_HasRealNamedProperty(oParent, "a"), true);
            assert.strictEqual(module.Object_HasRealNamedProperty(oParent, "b"), true);
            assert.strictEqual(module.Object_HasRealNamedProperty(oParent, "c"), false);
        });
    });
    describe('HasRealIndexedProperty', function () {
        it('should report correct indexed properties', function () {
            var o = {a: 42, b: 100};
            o[20] = "test";
            assert.strictEqual(module.Object_HasRealIndexedProperty(o, 0), false);
            assert.strictEqual(module.Object_HasRealIndexedProperty(o, 19), false);
            assert.strictEqual(module.Object_HasRealIndexedProperty(o, 20), true);
            assert.strictEqual(module.Object_HasRealIndexedProperty(o, 21), false);
        });
    });
    describe('Delete by name', function () {
        it('deleting simple properties by name', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(o.hasOwnProperty("a"), true);
            assert.strictEqual(o.hasOwnProperty("b"), true);
            assert.strictEqual(module.Object_DeleteByName(o, "a"), true);
            assert.strictEqual(module.Object_DeleteByName(o, "b"), true);
            assert.strictEqual(module.Object_DeleteByName(o, "c"), true); //that's what Node 0.10 reports
            assert.strictEqual(o.hasOwnProperty("a"), false);
            assert.strictEqual(o.hasOwnProperty("b"), false);
        });
        it('should delete a property given a non-string key', function () {
            var o = {111: 222};
            assert.strictEqual(o.hasOwnProperty(111), true);
            assert.strictEqual(module.Object_DeleteByName(o, 111), true);
            assert.strictEqual(o.hasOwnProperty(111), false);
        });
    });
    describe('InternalFieldCount', function () {
        it('simple object without internal fields', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_InternalFieldCount(o), 0);
        });
    });
    describe('GetConstructorName', function () {
        it('should return the constructor name', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_GetConstructorName(o), "Object");
            assert.strictEqual(module.Object_GetConstructorName(Array.prototype), "Array");
        });
    });
    describe('GetPrototype', function () {
        it('should return the prototype', function () {
            var oParent = {a: 42, b: 100};
            var oChild = Object.create(oParent);
            assert.strictEqual(module.Object_GetPrototype(oChild), oParent);
        });
        it('should return the prototype of prototype of array', function () {
            var arr = [1, 2, 3];

            var proto1 = module.Object_GetPrototype(arr);
            var proto2 = module.Object_GetPrototype(proto1);

            assert.strictEqual(proto1, Array.prototype);
            assert.strictEqual(proto2, Object.prototype);
        });
        it('should return null for no prototype', function () {
            var proto = module.Object_GetPrototype(Object.prototype);
            assert.strictEqual(proto, null);
        });
    });
    describe('ForceSet', function () {
        it('should force-set simple properties by name', function () {
            var called = false;
            var o = {a: 42, b: 100, set c(x) {
                    called = true;
                }, get c() {
                    return 666;
                }};
            assert.strictEqual(module.Object_ForceSet(o, "c", 200), true);

            assert.strictEqual(o.c, 200);
            assert.strictEqual(called, false);
        });
        it('should force-set a property given a non-string key', function () {
            var o = {123: 321};
            assert.strictEqual(module.Object_ForceSet(o, 123, 456), true);
            assert.strictEqual(o[123], 456);
        });
    });
    describe('CreationContext', function () {
        it('should be the current context for simple objects', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_CreationContextIsCurrent(o), true);
        });
        it('should be the newly created (and entered) context', function () {
            assert.strictEqual(module.Object_CreationContextNewContext(), true);
        });
    });
    describe('Clone', function () {
        it('should be able to clone a simple object', function () {
            var o1 = {a: 42, b: 100};
            var o2 = {c: "test", d: 3.1415, child: o1};

            var clone1 = module.Object_Clone(o2);
            assert.strictEqual(clone1 instanceof Object, true);
            assert.strictEqual(typeof clone1, "object");
            assert.strictEqual(clone1 !== o2, true);
            assert.strictEqual(clone1.hasOwnProperty("c"), true);
            assert.strictEqual(clone1.hasOwnProperty("d"), true);
            assert.strictEqual(clone1.hasOwnProperty("child"), true);
            assert.strictEqual(clone1.hasOwnProperty("a"), false);
            assert.strictEqual(clone1.hasOwnProperty("b"), false);

            assert.strictEqual(clone1.c, "test");
            assert.strictEqual(clone1.d, 3.1415);
            assert.strictEqual(clone1.child.a, 42);

            assert.strictEqual(clone1.child === o2.child, true); //it's a shallow copy
        });
    });
    describe('SetAccessor', function () {
        it('should create simple getter', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(module.Object_SetAccessor(o, "myAccess"), true);
            var gotValue = o.myAccess;
            assert.strictEqual(gotValue, "accessor getter called: myAccess");
        });
        it('should create simple setter', function () {
            var o = {a: 42, b: 100};
            assert.strictEqual(o.mySetValue, undefined);
            assert.strictEqual(module.Object_SetAccessor(o, "myAccess"), true);
            o.myAccess = 1000;
            assert.strictEqual(o.mySetValue, 1000);
            assert.strictEqual(o.hasOwnProperty("mySetValue"), true);
        });
    });
});
