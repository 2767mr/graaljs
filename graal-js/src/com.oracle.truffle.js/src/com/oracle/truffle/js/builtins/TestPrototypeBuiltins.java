package com.oracle.truffle.js.builtins;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.CreateIterResultObjectNode;
import com.oracle.truffle.js.nodes.access.CreateObjectNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.arguments.AccessIndexedArgumentNode;
import com.oracle.truffle.js.nodes.cast.JSToBooleanNode;
import com.oracle.truffle.js.nodes.function.InternalCallNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.SimpleArrayList;

public final class TestPrototypeBuiltins extends JSBuiltinsContainer.SwitchEnum<TestPrototypeBuiltins.TestPrototype> {

    public static final JSBuiltinsContainer BUILTINS = new TestPrototypeBuiltins();

    protected TestPrototypeBuiltins() {
        super(Strings.constant("Test.prototype"), TestPrototype.class);
    }

    public enum TestPrototype implements BuiltinEnum<TestPrototype> {
        test(1),
        next(0),
        toArray(0);

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
                return TestPrototypeBuiltinsFactory.TestBuiltinNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case next:
                return TestPrototypeBuiltinsFactory.TestNextBuiltinNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case toArray:
                return TestPrototypeBuiltinsFactory.TestToArrayBuiltinNodeGen.create(context, builtin, args().withThis().fixedArgs(0).createArgumentNodes(context));
        }
        return null;
    }

    private static final HiddenKey TARGET_ID = new HiddenKey("testtarget");

    public abstract static class TestBuiltin extends JSBuiltinNode {
        @Child private CreateObjectNode.CreateObjectWithPrototypeNode createObjectNode;
        @Child private PropertySetNode setTargetNode;

        private final JSDynamicObject prototype;

        protected TestBuiltin(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            this.prototype = createPrototype();

            createObjectNode = CreateObjectNode.createOrdinaryWithPrototype(context);
            setTargetNode = PropertySetNode.createSetHidden(TARGET_ID, context);
        }

        @Specialization
        public Object test(Object target) {
            JSDynamicObject result = createObjectNode.execute(this.prototype);
            setTargetNode.setValue(result, target);
            return result;
        }

        private JSDynamicObject createPrototype() {
            //Workaround for another issue: https://github.com/oracle/graaljs/issues/636
            JSObject prototype = JSObjectUtil.createOrdinaryPrototypeObject(getRealm(), getRealm().getObjectPrototype());
            JSObjectUtil.putFunctionsFromContainer(getRealm(), prototype, new TestPrototypeBuiltins());
            return prototype;
        }

    }

    public abstract static class TestNextBuiltin extends JSBuiltinNode {
        @Child private JSFunctionCallNode callNextNode;
        @Child private PropertyGetNode getTargetNode;
        @Child private PropertyGetNode getNextNode;
        @Child private PropertyGetNode getValueNode;
        @Child private PropertyGetNode getDoneNode;
        @Child private JSToBooleanNode toBooleanNode;
        @Child private CreateIterResultObjectNode createIterResultObjectNode;

        public TestNextBuiltin(JSContext context, JSBuiltin builtin) {
            super(context, builtin);

            callNextNode = JSFunctionCallNode.createCall();
            getTargetNode = PropertyGetNode.createGetHidden(TARGET_ID, context);
            getNextNode = PropertyGetNode.create(Strings.NEXT, false, context);
            getValueNode = PropertyGetNode.create(Strings.VALUE, false, context);
            getDoneNode = PropertyGetNode.create(Strings.DONE, false, context);
            toBooleanNode = JSToBooleanNode.create();
            createIterResultObjectNode = CreateIterResultObjectNode.create(context);
        }

        @Specialization
        public Object next(VirtualFrame frame, Object thisObj) {
            Object target = getTargetNode.getValue(thisObj);
            Object next = getNextNode.getValue(target);

            Object result = callNextNode.executeCall(JSArguments.createZeroArg(target, next));
            if (toBooleanNode.executeBoolean(getDoneNode.getValue(result))) {
                return createIterResultObjectNode.execute(frame, Undefined.instance, true);
            }

            try {
                return createIterResultObjectNode.execute(frame, getValueNode.getValueInt(result) + 1337, false);
            } catch (UnexpectedResultException e) {
                throw Errors.shouldNotReachHere();
            }
        }
    }

    public abstract static class TestToArrayBuiltin extends JSBuiltinNode {
        @Child private JSFunctionCallNode callNextNode;
        @Child private PropertyGetNode getTargetNode;
        @Child private PropertyGetNode getNextNode;
        @Child private PropertyGetNode getValueNode;
        @Child private PropertyGetNode getDoneNode;
        @Child private JSToBooleanNode toBooleanNode;

        protected TestToArrayBuiltin(JSContext context, JSBuiltin builtin) {
            super(context, builtin);

            callNextNode = JSFunctionCallNode.createCall();
            getTargetNode = PropertyGetNode.createGetHidden(TARGET_ID, context);
            getNextNode = PropertyGetNode.create(Strings.NEXT, false, context);
            getValueNode = PropertyGetNode.create(Strings.VALUE, false, context);
            getDoneNode = PropertyGetNode.create(Strings.DONE, false, context);
            toBooleanNode = JSToBooleanNode.create();
        }

        @Specialization
        public Object toArray(Object thisObj, @Cached("create()")BranchProfile growProfile) {
            Object target = getTargetNode.getValue(thisObj);
            Object next = getNextNode.getValue(target);

            SimpleArrayList<Object> elements = new SimpleArrayList<>(100000);

            while (true) {
                Object result = callNextNode.executeCall(JSArguments.createZeroArg(target, next));
                if (toBooleanNode.executeBoolean(getDoneNode.getValue(result))) {
                    return JSArray.createZeroBasedObjectArray(getContext(), getRealm(), elements.toArray());
                }

                try {
                    elements.add(getValueNode.getValueInt(result), growProfile);
                } catch (UnexpectedResultException e) {
                    throw Errors.shouldNotReachHere();
                }
            }
        }
    }
}
