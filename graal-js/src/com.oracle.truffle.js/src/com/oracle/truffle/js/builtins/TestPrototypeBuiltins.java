package com.oracle.truffle.js.builtins;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.CreateObjectNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.arguments.AccessIndexedArgumentNode;
import com.oracle.truffle.js.nodes.function.InternalCallNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.Undefined;

public final class TestPrototypeBuiltins extends JSBuiltinsContainer.SwitchEnum<TestPrototypeBuiltins.TestPrototype> {

    public static final JSBuiltinsContainer BUILTINS = new TestPrototypeBuiltins();

    protected TestPrototypeBuiltins() {
        super(JSArray.PROTOTYPE_NAME, TestPrototype.class);
    }

    public enum TestPrototype implements BuiltinEnum<TestPrototype> {
        test(2),
        next(1);

        private final int length;

        TestPrototype(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public int getECMAScriptVersion() {
            return JSConfig.StagingECMAScriptVersion;
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, TestPrototype builtinEnum) {
        switch (builtinEnum) {
            case test:
                return TestPrototypeBuiltinsFactory.TestBuiltinNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case next:
                return TestPrototypeBuiltinsFactory.TestNextBuiltinNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
        }
        return null;
    }

    private static final HiddenKey TARGET_ID = new HiddenKey("testtarget");
    private static final HiddenKey FUNC_ID = new HiddenKey("testfunc");
    private static final HiddenKey CALLBACK_ID = new HiddenKey("testcallback");

    public abstract static class TestBuiltin extends JSBuiltinNode {
        static final class InnerRootNode extends JavaScriptRootNode {
            @Child private JSFunctionCallNode callMapperNode;
            @Child private JSFunctionCallNode callTargetNode;
            @Child private PropertyGetNode getMapperNode;
            @Child private PropertyGetNode getTargetNode;
            @Child private PropertyGetNode getFuncNode;
            @Child private JavaScriptNode argNode;

            private InnerRootNode(JSContext context) {
                super();
                callMapperNode = JSFunctionCallNode.createCall();
                callTargetNode = JSFunctionCallNode.createCall();
                getMapperNode = PropertyGetNode.createGetHidden(FUNC_ID, context);
                getTargetNode = PropertyGetNode.createGetHidden(TARGET_ID, context);
                getFuncNode = PropertyGetNode.create(Strings.constant("next"), false, context);
                argNode = AccessIndexedArgumentNode.create(0);
            }

            @Override
            public Object execute(VirtualFrame frame) {
                Object thisObj = JSFrameUtil.getThisObj(frame);

                Object target = getTargetNode.getValue(thisObj);
                Object func = getFuncNode.getValue(target);
                Object result = callTargetNode.executeCall(JSArguments.createOneArg(target, func, argNode.execute(frame)));

                Object mapper = getMapperNode.getValue(thisObj);
                return callMapperNode.executeCall(JSArguments.createOneArg(Undefined.instance, mapper, result));
            }

            public static InnerRootNode create(JSContext context) {
                return new InnerRootNode(context);
            }
        }

        @Child private CreateObjectNode.CreateObjectWithPrototypeNode createObjectNode;
        @Child private PropertySetNode setFuncNode;
        @Child private PropertySetNode setTargetNode;
        @Child private PropertySetNode setCallbackNode;
        @Child private InnerRootNode innerRootNode;

        private final JSDynamicObject prototype;

        protected TestBuiltin(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            this.prototype = createPrototype();

            createObjectNode = CreateObjectNode.createOrdinaryWithPrototype(context);
            setFuncNode = PropertySetNode.createSetHidden(FUNC_ID, context);
            setTargetNode = PropertySetNode.createSetHidden(TARGET_ID, context);
            setCallbackNode = PropertySetNode.createSetHidden(CALLBACK_ID, context);
            innerRootNode = InnerRootNode.create(context);
        }

        @Specialization
        public Object test(Object thisObj, Object func) {
            JSDynamicObject result = createObjectNode.execute(this.prototype);
            setTargetNode.setValue(result, thisObj);
            setFuncNode.setValue(result, func);
            setCallbackNode.setValue(result, innerRootNode.getCallTarget());
            return result;
        }

        private JSDynamicObject createPrototype() {
            return getRealm().getArrayPrototype();

            // //This fixes it but creates a new prototype every time
            //JSObject prototype = JSObjectUtil.createOrdinaryPrototypeObject(getRealm(), getRealm().getObjectPrototype());
            //JSObjectUtil.putFunctionsFromContainer(getRealm(), prototype, new TestPrototypeBuiltins());
            //return prototype;
        }

    }

    public abstract static class TestNextBuiltin extends JSBuiltinNode {
        @Child private InternalCallNode callNode;
        @Child private PropertyGetNode getCallBackNode;

        public TestNextBuiltin(JSContext context, JSBuiltin builtin) {
            super(context, builtin);

            callNode = InternalCallNode.create();
            getCallBackNode = PropertyGetNode.createGetHidden(CALLBACK_ID, context);
        }

        @Specialization
        public Object next(VirtualFrame frame, Object thisObj, Object arg) {
            //Inlining the call here manually does resolve the issue but would not allow for multiple InnerRootNode implementations
            return callNode.execute((CallTarget) getCallBackNode.getValue(thisObj), frame.getArguments());
        }
    }
}
