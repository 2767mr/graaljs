/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

"use strict";

(function(){

const ITERATED_OBJECT_ID = Internal.GetHiddenKey("IteratedObject");
const ITERATOR_NEXT_INDEX_ID = Internal.GetHiddenKey("IteratorNextIndex");
const ARRAY_ITERATION_KIND_ID = Internal.HiddenKey("ArrayIterationKind");

const ITERATION_KIND_KEY = 1 << 0;
const ITERATION_KIND_VALUE = 1 << 1;
const ITERATION_KIND_KEY_PLUS_VALUE = ITERATION_KIND_KEY | ITERATION_KIND_VALUE;

var TypedArray = Internal.TypedArray; // == Object.getPrototypeOf(Int8Array)

function getIteratorPrototype() {
  return this;
}
Internal.CreateMethodProperty(Internal.GetIteratorPrototype(), Symbol.iterator, getIteratorPrototype);

Internal.SetFunctionName(getIteratorPrototype, "[Symbol.iterator]");

// Array Iterator
var ArrayIterator = Internal.MakeConstructor(function ArrayIterator() {});
ArrayIterator.prototype = Object.create(Internal.GetIteratorPrototype());

function CreateArrayIterator(array, kind) {
  // Assert: Type(array) is Object.
  var iterator = new ArrayIterator();
  iterator[ITERATED_OBJECT_ID] = array;
  iterator[ITERATOR_NEXT_INDEX_ID] = 0;
  iterator[ARRAY_ITERATION_KIND_ID] = kind;
  return iterator;
}

function CreateIterResultObject(value, done) {
  return { value, done };
}

function IsArrayIterator(iterator) {
  // Returns true if iterator has all of the internal slots of an Array Iterator Instance (ES6 22.1.5.3).
  if (Internal.HasHiddenKey(iterator, ARRAY_ITERATION_KIND_ID)) {
    // If one of the Array Iterator internal slots is present, the others must be as well.
    Internal.Assert(Internal.HasHiddenKey(iterator, ITERATED_OBJECT_ID));
    Internal.Assert(Internal.HasHiddenKey(iterator, ITERATOR_NEXT_INDEX_ID));
    return true;
  }
  return false;
}

function ArrayIteratorNext() {
  var iterator = this;
  if (!IsArrayIterator(iterator)) {
    throw new TypeError("not an Array Iterator");
  }
  var array = iterator[ITERATED_OBJECT_ID];
  if (array === undefined) {
    return CreateIterResultObject(undefined, true);
  }
  var index = iterator[ITERATOR_NEXT_INDEX_ID];
  var itemKind = iterator[ARRAY_ITERATION_KIND_ID];
  var length;
  if (Internal.IsArrayBufferView(array)) {
    if (Internal.HasDetachedBuffer(array)) {
      throw new TypeError("Cannot perform Array Iterator.prototype.next on a detached ArrayBuffer");
    }
    length = array.length; //should be a.[[ArrayLength]]
  } else {
    length = Internal.ToLength(array.length);
  }
  if (index >= length) {
    iterator[ITERATED_OBJECT_ID] = undefined;
    return CreateIterResultObject(undefined, true);
  }
  iterator[ITERATOR_NEXT_INDEX_ID] = index + 1;
  if (itemKind === ITERATION_KIND_KEY) {
    return CreateIterResultObject(index, false);
  }
  var elementValue = array[index];
  var result;
  if (itemKind === ITERATION_KIND_VALUE) {
    result = elementValue;
  } else {
    Internal.Assert(itemKind === ITERATION_KIND_KEY_PLUS_VALUE);
    result = [index, elementValue];
  }
  return CreateIterResultObject(result, false);
}

Internal.CreateMethodProperty(ArrayIterator.prototype, "next", ArrayIteratorNext);
Internal.SetFunctionName(ArrayIteratorNext, "next");
Internal.ObjectDefineProperty(ArrayIterator.prototype, Symbol.toStringTag, {value: "Array Iterator", writable: false, enumerable: false, configurable: true});

var arrayKeys = function keys() {
  return CreateArrayIterator(Internal.ToObject(this), ITERATION_KIND_KEY);
}
var arrayValues = function values() {
  return CreateArrayIterator(Internal.ToObject(this), ITERATION_KIND_VALUE);
}
var arrayEntries = function entries() {
  return CreateArrayIterator(Internal.ToObject(this), ITERATION_KIND_KEY_PLUS_VALUE);
}

Internal.CreateMethodProperty(Array.prototype, "keys", arrayKeys);
Internal.CreateMethodProperty(Array.prototype, "values", arrayValues);
Internal.CreateMethodProperty(Array.prototype, Symbol.iterator, arrayValues);
Internal.CreateMethodProperty(Array.prototype, "entries", arrayEntries);

function ValidateTypedArray(obj) {
  if (!Internal.IsValidTypedArray(obj)) {
    throw new TypeError("not an valid TypedArray");
  }
  return Internal.ToObject(obj);
}

var typedArrayKeys = function keys() {
  return CreateArrayIterator(ValidateTypedArray(this), ITERATION_KIND_KEY);
}
var typedArrayValues = function values() {
  return CreateArrayIterator(ValidateTypedArray(this), ITERATION_KIND_VALUE);
}
var typedArrayEntries = function entries() {
  return CreateArrayIterator(ValidateTypedArray(this), ITERATION_KIND_KEY_PLUS_VALUE);
}

Internal.CreateMethodProperty(TypedArray.prototype, "keys", typedArrayKeys);
Internal.CreateMethodProperty(TypedArray.prototype, "values", typedArrayValues);
Internal.CreateMethodProperty(TypedArray.prototype, Symbol.iterator, typedArrayValues);
Internal.CreateMethodProperty(TypedArray.prototype, "entries", typedArrayEntries);


// Set Iterator
const SET_ITERATION_KIND_ID = Internal.HiddenKey("SetIterationKind");

var SetIterator = Internal.MakeConstructor(function SetIterator() {});
SetIterator.prototype = Object.create(Internal.GetIteratorPrototype());

function CreateSetIterator(set, kind) {
  Internal.RequireObject(set);
  if (!Internal.IsSet(set)) {
    throw new TypeError("not a Set");
  }
  var iterator = new SetIterator();
  iterator[ITERATED_OBJECT_ID] = set;
  iterator[ITERATOR_NEXT_INDEX_ID] = Internal.GetMapCursor(set);
  iterator[SET_ITERATION_KIND_ID] = kind;
  return iterator;
}

function IsSetIterator(iterator) {
  // Returns true if iterator has all of the internal slots of a Set Iterator Instance (ES6 23.2.5.3).
  if (Internal.HasHiddenKey(iterator, SET_ITERATION_KIND_ID)) {
    // If one of the Set Iterator internal slots is present, the others must be as well.
    Internal.Assert(Internal.HasHiddenKey(iterator, ITERATED_OBJECT_ID));
    Internal.Assert(Internal.HasHiddenKey(iterator, ITERATOR_NEXT_INDEX_ID));
    return true;
  }
  return false;
}

function SetIteratorNext() {
  var iterator = this;
  if (!IsSetIterator(iterator)) {
    throw new TypeError("not a Set Iterator");
  }
  var set = iterator[ITERATED_OBJECT_ID];
  if (set === undefined) {
    return CreateIterResultObject(undefined, true);
  }
  var index = iterator[ITERATOR_NEXT_INDEX_ID];
  var itemKind = iterator[SET_ITERATION_KIND_ID];
  if (!Internal.AdvanceMapCursor(set, index)) {
    iterator[ITERATED_OBJECT_ID] = undefined;
    return CreateIterResultObject(undefined, true);
  }

  var elementValue = Internal.GetKeyFromMapCursor(set, index);
  var result;
  if (itemKind === ITERATION_KIND_VALUE) {
    result = elementValue;
  } else {
    Internal.Assert(itemKind === ITERATION_KIND_KEY_PLUS_VALUE);
    result = [elementValue, elementValue];
  }
  return CreateIterResultObject(result, false);
}

Internal.CreateMethodProperty(SetIterator.prototype, "next", SetIteratorNext);
Internal.SetFunctionName(SetIteratorNext, "next");
Internal.ObjectDefineProperty(SetIterator.prototype, Symbol.toStringTag, {value: "Set Iterator", writable: false, enumerable: false, configurable: true});

var setValues = function values() {
  return CreateSetIterator(this, ITERATION_KIND_VALUE);
}
var setEntries = function entries() {
  return CreateSetIterator(this, ITERATION_KIND_KEY_PLUS_VALUE);
}

// The initial value of the keys property is the same function object as the initial value of the values property.
Internal.CreateMethodProperty(Set.prototype, "keys", setValues);
Internal.CreateMethodProperty(Set.prototype, "values", setValues);
Internal.CreateMethodProperty(Set.prototype, Symbol.iterator, setValues);
Internal.CreateMethodProperty(Set.prototype, "entries", setEntries);


// Map Iterator
const MAP_ITERATION_KIND_ID = Internal.HiddenKey("MapIterationKind");

var MapIterator = Internal.MakeConstructor(function MapIterator() {});
MapIterator.prototype = Object.create(Internal.GetIteratorPrototype());

function CreateMapIterator(map, kind) {
  Internal.RequireObject(map);
  if (!Internal.IsMap(map)) {
    throw new TypeError("not a Map");
  }
  var iterator = new MapIterator();
  iterator[ITERATED_OBJECT_ID] = map;
  iterator[ITERATOR_NEXT_INDEX_ID] = Internal.GetMapCursor(map);
  iterator[MAP_ITERATION_KIND_ID] = kind;
  return iterator;
}

function IsMapIterator(iterator) {
  // Returns true if iterator has all of the internal slots of a Map Iterator Instance (ES6 23.1.5.3).
  if (Internal.HasHiddenKey(iterator, MAP_ITERATION_KIND_ID)) {
    // If one of the Map Iterator internal slots is present, the others must be as well.
    Internal.Assert(Internal.HasHiddenKey(iterator, ITERATED_OBJECT_ID));
    Internal.Assert(Internal.HasHiddenKey(iterator, ITERATOR_NEXT_INDEX_ID));
    return true;
  }
  return false;
}

function MapIteratorNext() {
  var iterator = this;
  if (!IsMapIterator(iterator)) {
    throw new TypeError("not a Map Iterator");
  }
  var map = iterator[ITERATED_OBJECT_ID];
  if (map === undefined) {
    return CreateIterResultObject(undefined, true);
  }
  var index = iterator[ITERATOR_NEXT_INDEX_ID];
  var itemKind = iterator[MAP_ITERATION_KIND_ID];
  if (!Internal.AdvanceMapCursor(map, index)) {
    iterator[ITERATED_OBJECT_ID] = undefined;
    return CreateIterResultObject(undefined, true);
  }

  var elementKey = Internal.GetKeyFromMapCursor(map, index);
  var elementValue = Internal.GetValueFromMapCursor(map, index);

  if (itemKind === ITERATION_KIND_KEY) {
    return CreateIterResultObject(elementKey, false);
  } else if (itemKind === ITERATION_KIND_VALUE) {
    return CreateIterResultObject(elementValue, false);
  } else {
    Internal.Assert(itemKind === ITERATION_KIND_KEY_PLUS_VALUE);
    return CreateIterResultObject([elementKey, elementValue], false);
  }
}

Internal.CreateMethodProperty(MapIterator.prototype, "next", MapIteratorNext);
Internal.SetFunctionName(MapIteratorNext, "next");
Internal.ObjectDefineProperty(MapIterator.prototype, Symbol.toStringTag, {value: "Map Iterator", writable: false, enumerable: false, configurable: true});

var mapKeys = function keys() {
  return CreateMapIterator(this, ITERATION_KIND_KEY);
}
var mapValues = function values() {
  return CreateMapIterator(this, ITERATION_KIND_VALUE);
}
var mapEntries = function entries() {
  return CreateMapIterator(this, ITERATION_KIND_KEY_PLUS_VALUE);
}

Internal.CreateMethodProperty(Map.prototype, "keys", mapKeys);
Internal.CreateMethodProperty(Map.prototype, "values", mapValues);
Internal.CreateMethodProperty(Map.prototype, Symbol.iterator, mapEntries);
Internal.CreateMethodProperty(Map.prototype, "entries", mapEntries);


// String Iterator
const ITERATED_STRING_ID = Internal.HiddenKey("IteratedString");
const STRING_ITERATOR_NEXT_INDEX_ID = Internal.HiddenKey("StringIteratorNextIndex");
const charCodeAt = String.prototype.charCodeAt;
const fromCharCode = String.fromCharCode;

var StringIterator = Internal.MakeConstructor(function StringIterator() {});
StringIterator.prototype = Object.create(Internal.GetIteratorPrototype());

function CreateStringIterator(string) {
  Internal.Assert(typeof string === 'string');
  var iterator = new StringIterator();
  iterator[ITERATED_STRING_ID] = string;
  iterator[STRING_ITERATOR_NEXT_INDEX_ID] = 0;
  return iterator;
}

function IsStringIterator(iterator) {
  // Returns true if iterator has all of the internal slots of a String Iterator Instance (ES6 21.1.5.3).
  if (Internal.HasHiddenKey(iterator, ITERATED_STRING_ID)) {
    // If one of the String Iterator internal slots is present, the other must be as well.
    Internal.Assert(Internal.HasHiddenKey(iterator, STRING_ITERATOR_NEXT_INDEX_ID));
    return true;
  }
  return false;
}

function StringIteratorNext() {
  var iterator = this;
  if (!IsStringIterator(iterator)) {
    throw new TypeError("not a String Iterator");
  }
  var string = iterator[ITERATED_STRING_ID];
  if (string === undefined) {
    return CreateIterResultObject(undefined, true);
  }
  var position = iterator[STRING_ITERATOR_NEXT_INDEX_ID];
  var length = string.length;
  if (position >= length) {
    iterator[ITERATED_STRING_ID] = undefined;
    return CreateIterResultObject(undefined, true);
  }
  var first = Internal.CallFunction(charCodeAt, string, position);
  var resultString;
  if (first < 0xD800 || first > 0xDBFF || position + 1 == length) {
    resultString = fromCharCode(first);
  } else {
    var second = Internal.CallFunction(charCodeAt, string, position + 1);
    if (second < 0xDC00 || second > 0xDFFF) {
      resultString = fromCharCode(first);
    } else {
      resultString = fromCharCode(first) + fromCharCode(second);
    }
  }
  iterator[STRING_ITERATOR_NEXT_INDEX_ID] = position + resultString.length;
  return CreateIterResultObject(resultString, false);
}

Internal.CreateMethodProperty(StringIterator.prototype, "next", StringIteratorNext);
Internal.SetFunctionName(StringIteratorNext, "next");
Internal.ObjectDefineProperty(StringIterator.prototype, Symbol.toStringTag, {value: "String Iterator", writable: false, enumerable: false, configurable: true});

function StringPrototypeIterator() {
  Internal.RequireObjectCoercible(this);
  return CreateStringIterator(Internal.ToString(this));
}

Internal.CreateMethodProperty(String.prototype, Symbol.iterator, StringPrototypeIterator);
Internal.SetFunctionName(StringPrototypeIterator, "[Symbol.iterator]");

})();
