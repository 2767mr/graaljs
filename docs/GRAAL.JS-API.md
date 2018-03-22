# Graal.js API

Graal.js is a JavaScript (ECMAScript) language execution runtime.
This documents explains the public API it provides to  user applications written in JavaScript.

* [ECMAScript language compliance](#ecmascript-language-compliance)
* [Compatibility extensions](#compatibility-extensions)
* [Graal.js extensions](#graal.js-extensions)
* [Nashorn extensions](#nashorn-extensions)

## ECMAScript language compliance

Graal.js implements JavaScript as prescribed in the ECMAScript (ECMA-262) specification.
By default, Graal.js is compatible with the 2017 edition of ECMAScript (sometimes referred to as "version 8" or "ES8"), see [http://www.ecma-international.org/ecma-262/8.0/](http://www.ecma-international.org/ecma-262/8.0/).
Older versions, as well as some features of the most recent version, can be enabled with special flags on startup.
For informations on the flags, see the *--help* message of the executable.

Graal.js provides the following function objects in the global scope as specified by ECMAScript:

- Array
- ArrayBuffer
- Atomics (flag required)
- Boolean
- DataView
- Date
- Error
- Function
- Intl (flag required)
- JSON
- Map
- Math
- Number
- Object
- Promise
- Proxy
- Reflect
- RegExp
- Set
- SharedArrayBuffer (flag required)
- SIMD (flag required)
- String
- Symbol
- TypedArray
- WeakMap
- WeakSet

Several of these function objects and some of their members are only available when a certain version of the spec is selected for execution.
For a list of methods provided, inspect the ECMAScript specification.
Extensions to the specification are specified below.

### Internationalization API (ECMA-402)

Internationalization API implementation (see [https://tc39.github.io/ecma402](https://tc39.github.io/ecma402)) can be activated using the following flag: `--js.intl-402=true`.
If you run in native mode (default option), you also need to specify path to your ICU data directory using the following flag: `--native.Dcom.ibm.icu.impl.ICUBinary.dataPath=$GRAAL_VM_DIR/jre/languages/js/icu4j/icudt`,
where `$GRAAL_VM_DIR` refers to your GraalVM installation directory.
If you run in JVM mode (a jvm flag is used), you do not need to specify where your ICU data are located, although you can do it with `--jvm.Dcom.ibm.icu.impl.ICUBinary.dataPath=$GRAAL_VM_DIR/jre/languages/js/icu4j/icudt`.

Once you activate Internationalization API, you can use the following built-ins:

- Intl.NumberFormat
- Intl.DateTimeFormat
- Intl.Collator
- Intl.PluralRules

Functionality of a few other built-ins is then also updated according to the specification linked above.

## Compatibility extensions

The following objects and methods are available in Graal.js for compatibility with other JavaScript execution engines such as Rhino.
Note that the behaviour of such methods might not strictly match the semantics of those methods in all existing engines.

### Global methods

#### `exit(status)` or `quit(status)`

Exists the engine and returns the specified status code.

#### `load(source, args)`

Loads (parses and executes) the specified JavaScript source code.

Source can be of type:

* `java.lang.URL`: the URL is queried for the source code to execute.
* `java.io.File`: the File is read for the source code to execute.
* a JavaScript object: the object is queried for a `name` and a `source` property.
* all other types: the source is converted to a String.

The value of `arguments` is provided to the loaded code upon execution.

#### `print(...arg)` and `console.log(...arg)`

Prints the arguments on the console.
Provides a best-effort human readable output.

Note that `console.log` behaves differently when Graal.js executed in Node.js mode (i.e., the `node` executable is started instead of `js`).
While normally, `console.log` is just an alias for `print`, on Node.js Node's own implementation is executed.

#### `read(file)` or `readFully(file)`

This function reads the content of `file`.
The result is returned as String.

The argument `file` can be of type:

* `java.io.file`: the file is used directly.
* all other types: `file` is converted to a String and interpreted as file name.

#### `readbuffer(file)`

This function reads the content of `file` similar to the `read` function.
The result is returned as a JavaScript `ArrayBuffer` object.

#### `readLine(prompt)` or `readline(prompt)`

This function reads one line of input from the input stream.
It returns a String as result.

An optional `prompt` value can be provided, that is print to the output stream.
`prompt` is ignored when its value is `undefined`.

### Object

#### `Object.prototype.__defineGetter__(prop, func)`

Defines the `prop` property of `this` to be the getter function `func`.
This functionality is deprecated in most JavaScript engines.
In recent ECMAScript versions, getters and setters are natively supported by the language.

#### `Object.prototype.__defineSetter__(prop, func)`

Defines the `prop` property of `this` to be the setter function `func`.
This functionality is deprecated in most JavaScript engines.
In recent ECMAScript versions, getters and setters are natively supported by the language.

#### `Object.prototype.__lookupGetter__(prop)`

Reads the `prop` property of `this`, that is expected to be a getter function.
This functionality is deprecated in most JavaScript engines.
In recent ECMAScript versions, getters and setters are natively supported by the language.

#### `Object.prototype.__lookupSetter__(prop)`

Reads the `prop` property of `this`, that is expected to be a setter function.
This functionality is deprecated in most JavaScript engines.
In recent ECMAScript versions, getters and setters are natively supported by the language.

## Graal.js extensions

### Graal

The `Graal` object is provided as property of the global object.
It provides Graal-specific information.
The existence of the property can be used to identify whether the Graal.js engine is the current language engine.

    if (Graal) {
        print(Graal.versionJS);
        print(Graal.versionGraalVM);
        print(Graal.isGraalRuntime);
    }

The Graal object is available in Graal.js by default, unless deactivated by an option (`truffle.js.GraalBuiltin=false`).

#### `Graal.versionJS`

Provides the version number of Graal.js.

#### `Graal.versionGraalVM`

Provides the version of the GraalVM, if the current engine is executed on a GraalVM.

#### `Graal.isGraalRuntime`

Provides whether Graal.js is executed on a Graal-enabled runtime.
If `true`, hot code is compiled by the Graal compiler, resulting in high peak performance.
If `false`, Graal.js will not be compiled by the Graal compiler, typically resulting in lower performance.

### Java

The `Java` object is only available when the engine is started in JVM mode. 

#### `Java.type(className)`

The `type` function loads the specified Java class and provides it as an object.
Fields of this object can be read directly from it, and new instances can be created with the JavaScript ```new``` keyword.

    var BigDec = Java.type('java.math.BigDecimal');
    var bd = new BigDec("0.1");
    console.log(bd.add(bd).toString());

#### `Java.from(javaData)`

The `from` function creates a shallow copy of the Java datastructure (Array, List) as a JavaScript array.
In many cases, this is not necessary, you can typically use the Java datastructure directly from JavaScript.

#### `Java.to(jsData, toType)`

The `to` function converts the argument to a Java dataype.
When no `toType` is provided, `Object[]` is assumed.

    var jsArr = ["a","b","c"]
    var strArrType = Java.type("java.lang.String[]")
    var javaArr = Java.to(jsArr, strArrType)
    assertEquals('class [Ljava.lang.String;', String(javaArr.class));

#### `Java.isJavaObject(obj)`

The `isJavaObject` method returns whether `obj` is an object of the Java language.
It returns `false` for native JavaScript objects, as well as for objects of other polyglot languages.

#### `Java.isType(obj)`

The `isType` method returns whether `obj` is an object of the Java language, representing a Java `Class` instance.
It returns `false` for all other arguments.

#### `Java.typeName(obj)`

The `typeName` method returns the Java `Class` name of `obj`.
`obj` is expected to represent a Java `Class` instance, i.e., `isType(obj)` should return true; otherwise, `undefined` is returned.

### Polyglot

The functions of the `Polyglot` object allow to interact with values from other polyglot languages.

#### `Polyglot.export(key, value)`

Exports the JavaScript `value` under the name `key` (a string) to the polyglot bindings.

    function helloWorld() { print("Hello, JavaScript world"); };
    Polyglot.export("helloJSWorld", helloWorld);

If the polyglot bindings already had a value identified by `key`, it is overwritten with the new value.
Throws a `TypeError` if `key` is not a string or missing.
The `value` may be any valid Polyglot value.

#### `Polyglot.import(key)`

Imports the value identified by `key` (a string) from the polyglot bindings and returns it.

    var rubyHelloWorld = Polyglot.import("helloRubyWorld");
    rubyHelloWorld();

If no language has exported a value identified by `key`, `null` is returned.
Throws a `TypeError` if `key` is not a string or missing.

#### `Polyglot.eval(languageId, sourceCode)`

Parses and evaluates the `sourceCode` with the interpreter identified by `languageId`.
The value of `sourceCode` is expected to be a String (or convertable to one).
Returns the evaluation result, depending on the `sourceCode` and/or the semantics of the language evaluated.

    var rArray = Polyglot.eval('R', 'runif(1000)');

Exceptions can occur when an invalid `languageId` is passed, when the `sourceCode` cannot be evaluated by the language, or when the executed program throws one.

#### `Polyglot.evalFile(languageId, sourceFileName)`

Parses the file `sourceFileName` with the interpreter identified by `languageId`.
The value of `sourceFileName` is expected to be a String (or convertable to one), representing a file reachable by the current path.
Returns an executable object, typically a function.

    var rFunc = Polyglot.evalFile('R', 'myExample.r');
    var result = rFunc();

Exceptions can occur when an invalid `languageId` is passed, when the file identified by `sourceFileName` cannot be found, or when the language throws an exception during parsing (parse time errors, e.g. syntax errors).
Exceptions thrown by the evaluated program are only thrown once the resulting function is evaluated.

### Debug

requires starting the engine with the `debug` flag.

`Debug` is a Graal.js specific function object that provides functionality for debugging JavaScript code and the Graal.js compiler.
This API might change without notice, do not use for production purposes!

## Nashorn extensions

These function objects and functions are available to provide a compatibility layer with OpenJDK's Nashorn JavaScript engine.
A flag needs to be provided on startup for those to be available, see *--help*.

### Java

In Nashorn compatibility mode, additional methods are available on the `Java` object: 

- extend
- super
- isJavaMethod
- isJavaFunction
- isScriptFunction
- isScriptObject
- synchronized
- asJSONCompatible

See reference and examples at [Nashorn extensions](https://wiki.openjdk.java.net/display/Nashorn/Nashorn+extensions).

### JavaImporter

`JavaImporter` can be used to import packages explicitly, without polluting the global scope.
See reference at [Nashorn extensions](https://wiki.openjdk.java.net/display/Nashorn/Nashorn+extensions). 

### JSAdapter

`JSAdapter` is Nashorn's variant of a Proxy object.
See reference at [Nashorn extensions](https://wiki.openjdk.java.net/display/Nashorn/Nashorn+extensions).

As Graal.js supports ECMAScript's *Proxy* type.
It is strongly suggested to use *Proxy* in user applications.

### Global functions

#### `printErr(...arg)`

The method `printErr` behaves identical to `print`.
The only difference is, that the error stream is used to print to, instead of the default output stream.

#### `loadWithNewGlobal(source, arguments)`

This method behaves similar to `load` function.
Relevant difference is that the code is evaluated in a new global scope (`Realm`, as defined by ECMAScript).

Source can be of type:

* `java.lang.URL`: the URL is queried for the source code to execute.
* a JavaScript object: the object is queried for a `name` and a `source` property.
* all other types: the source is converted to a String.

The value of `arguments` is provided to the loaded code upon execution.

