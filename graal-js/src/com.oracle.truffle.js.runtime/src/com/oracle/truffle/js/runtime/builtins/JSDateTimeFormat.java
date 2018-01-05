/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime.builtins;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.LocationModifier;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.IntlUtil;

import com.ibm.icu.text.DateFormat;
import com.ibm.icu.text.DateTimePatternGenerator;
import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.TimeZone;

import java.text.AttributedCharacterIterator;

import java.util.Calendar;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class JSDateTimeFormat extends JSBuiltinObject implements JSConstructorFactory.Default.WithFunctions {

    public static final String CLASS_NAME = "DateTimeFormat";
    public static final String PROTOTYPE_NAME = "DateTimeFormat.prototype";

    private static final HiddenKey INTERNAL_STATE_ID = new HiddenKey("_internalState");
    private static final Property INTERNAL_STATE_PROPERTY;

    private static final JSDateTimeFormat INSTANCE = new JSDateTimeFormat();

    static {
        Shape.Allocator allocator = JSShape.makeAllocator(JSObject.LAYOUT);
        INTERNAL_STATE_PROPERTY = JSObjectUtil.makeHiddenProperty(INTERNAL_STATE_ID, allocator.locationForType(InternalState.class, EnumSet.of(LocationModifier.NonNull, LocationModifier.Final)));
    }

    private JSDateTimeFormat() {
    }

    public static boolean isJSDateTimeFormat(Object obj) {
        return JSObject.isDynamicObject(obj) && isJSDateTimeFormat((DynamicObject) obj);
    }

    public static boolean isJSDateTimeFormat(DynamicObject obj) {
        return isInstance(obj, INSTANCE);
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @Override
    public String getClassName(DynamicObject object) {
        return getClassName();
    }

    @Override
    public String getBuiltinToStringTag(DynamicObject object) {
        return "Object";
    }

    @Override
    public DynamicObject createPrototype(JSRealm realm, DynamicObject ctor) {
        JSContext ctx = realm.getContext();
        DynamicObject numberFormatPrototype = JSObject.create(realm, realm.getObjectPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putConstructorProperty(ctx, numberFormatPrototype, ctor);
        JSObjectUtil.putFunctionsFromContainer(realm, numberFormatPrototype, PROTOTYPE_NAME);
        JSObjectUtil.putConstantAccessorProperty(ctx, numberFormatPrototype, "format", createFormatFunctionGetter(realm, ctx), Undefined.instance, JSAttributes.configurableNotEnumerableNotWritable());
        return numberFormatPrototype;
    }

    public static Shape makeInitialShape(JSContext ctx, DynamicObject prototype) {
        assert JSShape.getProtoChildTree(prototype.getShape(), INSTANCE) == null;
        Shape initialShape = JSObjectUtil.getProtoChildShape(prototype, INSTANCE, ctx);
        initialShape = initialShape.addProperty(INTERNAL_STATE_PROPERTY);
        return initialShape;
    }

    public static JSConstructor createConstructor(JSRealm realm) {
        return INSTANCE.createConstructorAndPrototype(realm);
    }

    public static List<Object> supportedLocales(String[] locales) {
        return IntlUtil.supportedLocales(locales);
    }

    public static DynamicObject create(JSContext context) {
        InternalState state = new InternalState();
        DynamicObject result = JSObject.create(context, context.getDateTimeFormatFactory(), state);
        assert isJSDateTimeFormat(result);
        return result;
    }

    @TruffleBoundary
    public static void setupInternalDateTimeFormat(
                    InternalState state, String[] locales, DynamicObject options,
                    String weekdayOpt,
                    String eraOpt,
                    String yearOpt,
                    String monthOpt,
                    String dayOpt,
                    String hourOpt,
                    String hcOpt,
                    Boolean hour12Opt,
                    String minuteOpt,
                    String secondOpt,
                    String tzNameOpt) {
        String selectedTag = IntlUtil.selectedLocale(locales);
        Locale selectedLocale = selectedTag != null ? Locale.forLanguageTag(selectedTag) : Locale.getDefault();
        Locale strippedLocale = selectedLocale.stripExtensions();
        String skeleton = makeSkeleton(weekdayOpt, eraOpt, yearOpt, monthOpt, dayOpt, hourOpt, hcOpt, hour12Opt, minuteOpt, secondOpt, tzNameOpt);

        DateTimePatternGenerator patternGenerator = DateTimePatternGenerator.getInstance(strippedLocale);
        String bestPattern = patternGenerator.getBestPattern(skeleton);
        String baseSkeleton = patternGenerator.getBaseSkeleton(bestPattern);

        if (containsOneOf(baseSkeleton, "eEc")) {
            state.weekday = weekdayOpt;
        }

        if (baseSkeleton.contains("G")) {
            state.era = eraOpt;
        }

        if (containsOneOf(baseSkeleton, "YyUu")) {
            state.year = yearOpt;
        }

        if (containsOneOf(baseSkeleton, "ML")) {
            state.month = monthOpt;
        }

        if (containsOneOf(baseSkeleton, "dDFg")) {
            state.day = dayOpt;
        }

        if (containsOneOf(baseSkeleton, "hHKk")) {
            state.hour = hourOpt;
            state.hour12 = containsOneOf(baseSkeleton, "hK");
        }

        if (baseSkeleton.contains("m")) {
            state.minute = minuteOpt;
        }

        if (containsOneOf(baseSkeleton, "sSA")) {
            state.second = secondOpt;
        }

        state.initialized = true;

        state.dateFormat = new SimpleDateFormat(bestPattern, strippedLocale);
        state.locale = strippedLocale.toLanguageTag();

        if (tzNameOpt != null && !tzNameOpt.isEmpty()) {
            state.timeZoneName = tzNameOpt;
        }

        // https://tc39.github.io/ecma402/#sec-initializedatetimeformat (steps 15-18)
        Object tzVal = JSRuntime.getDataProperty(options, "timeZone");
        TimeZone tz;
        if (tzVal != Undefined.instance && tzVal != null) {
            String tzId = canonicalizeTimeZone(JSRuntime.toString(tzVal));
            if (tzId != null) {
                tz = TimeZone.getTimeZone(tzId);
                state.dateFormat.setTimeZone(tz);
            } else {
                throw Errors.createRangeError(String.format("Invalid time zone %s", tzVal));
            }
            state.timeZone = tzId;
        } else {
            tz = TimeZone.getDefault();
            state.dateFormat.setTimeZone(tz);
            state.timeZone = tz.getID();
        }

        Calendar calendar = Calendar.getInstance(strippedLocale);
        state.calendar = calendar.getCalendarType();

    }

    private static String weekdayOptToSkeleton(String weekdayOpt) {
        if (weekdayOpt == null) {
            return "";
        }
        switch (weekdayOpt) {
            case "narrow":
                return "eeeee";
            case "short":
                return "eee";
            case "long":
                return "eeee";
        }
        return "";
    }

    private static String eraOptToSkeleton(String eraOpt) {
        if (eraOpt == null) {
            return "";
        }
        switch (eraOpt) {
            case "narrow":
                return "GGGGG";
            case "short":
                return "GGG";
            case "long":
                return "GGGG";
        }
        return "";
    }

    private static String yearOptToSkeleton(String yearOpt) {
        if (yearOpt == null) {
            return "";
        }
        switch (yearOpt) {
            case "2-digit":
                return "yy";
            case "numeric":
                return "yyyy";
        }
        return "";
    }

    private static String monthOptToSkeleton(String monthOpt) {
        if (monthOpt == null) {
            return "";
        }
        switch (monthOpt) {
            case "2-digit":
                return "MM";
            case "numeric":
                return "M";
            case "narrow":
                return "MMMMM";
            case "short":
                return "MMM";
            case "long":
                return "MMMM";
        }
        return "";
    }

    private static String dayOptToSkeleton(String dayOpt) {
        if (dayOpt == null) {
            return "";
        }
        switch (dayOpt) {
            case "2-digit":
                return "dd";
            case "numeric":
                return "d";
        }
        return "";
    }

    private static String hourOptToSkeleton(String hourOpt, String hcOpt, Boolean hour12Opt) {
        if (hourOpt == null) {
            return "";
        }
        switch (hourOpt) {
            case "2-digit":
                if (hcOpt == null) {
                    if (hour12Opt != null) {
                        if (hour12Opt) {
                            return "KK";
                        } else {
                            return "HH";
                        }
                    } else {
                        return "jj";
                    }
                } else {
                    switch (hcOpt) {
                        case "h11":
                            return "KK";
                        case "h12":
                            return "hh";
                        case "h23":
                            return "HH";
                        case "h24":
                            return "kk";
                    }
                }
                break;
            case "numeric":
                if (hcOpt == null) {
                    if (hour12Opt != null) {
                        if (hour12Opt) {
                            return "K";
                        } else {
                            return "H";
                        }
                    } else {
                        return "j";
                    }
                } else {
                    switch (hcOpt) {
                        case "h11":
                            return "K";
                        case "h12":
                            return "h";
                        case "h23":
                            return "H";
                        case "h24":
                            return "k";
                    }
                }
        }
        return "";
    }

    private static String minuteOptToSkeleton(String minuteOpt) {
        if (minuteOpt == null) {
            return "";
        }
        switch (minuteOpt) {
            case "2-digit":
                return "mm";
            case "numeric":
                return "m";
        }
        return "";
    }

    private static String secondOptToSkeleton(String secondOpt) {
        if (secondOpt == null) {
            return "";
        }
        switch (secondOpt) {
            case "2-digit":
                return "ss";
            case "numeric":
                return "s";
        }
        return "";
    }

    private static String timeZoneNameOptToSkeleton(String timeZoneNameOpt) {
        if (timeZoneNameOpt == null) {
            return "";
        }
        switch (timeZoneNameOpt) {
            case "short":
                return "v";
            case "long":
                return "vv";
        }
        return "";
    }

    private static String makeSkeleton(String weekdayOpt, String eraOpt, String yearOpt, String monthOpt, String dayOpt, String hourOpt, String hcOpt, Boolean hour12Opt, String minuteOpt,
                    String secondOpt, String timeZoneNameOpt) {
        return weekdayOptToSkeleton(weekdayOpt) + eraOptToSkeleton(eraOpt) + yearOptToSkeleton(yearOpt) + monthOptToSkeleton(monthOpt) + dayOptToSkeleton(dayOpt) +
                        hourOptToSkeleton(hourOpt, hcOpt, hour12Opt) +
                        minuteOptToSkeleton(minuteOpt) + secondOptToSkeleton(secondOpt) + timeZoneNameOptToSkeleton(timeZoneNameOpt);
    }

    @TruffleBoundary
    // https://tc39.github.io/ecma402/#sec-canonicalizetimezonename
    private static String canonicalizeTimeZone(String tzId) {
        String ucTzId = IntlUtil.toUpperCase(tzId);
        String canTzId = TimeZone.getCanonicalID(ucTzId);
        if (canTzId == null) {
            return null;
        }
        if (canTzId.equals("Etc/UTC") || canTzId.equals("Etc/GMT")) {
            return "UTC";
        } else {
            return canTzId;
        }
    }

    private static boolean containsOneOf(String suspect, String containees) {
        for (int c : containees.getBytes()) {
            if (suspect.indexOf(c) > -1) {
                return true;
            }
        }
        return false;
    }

    public static DateFormat getDateFormatProperty(DynamicObject obj) {
        return getInternalState(obj).dateFormat;
    }

    @TruffleBoundary
    public static String format(DynamicObject numberFormatObj, Object n) {
        ensureIsDateTimeFormat(numberFormatObj);
        DateFormat dateFormat = getDateFormatProperty(numberFormatObj);
        Number x = (Undefined.instance == n) ? getDateNow() : JSRuntime.toNumber(n);
        throwRangeErrorIfOutOfValidRange(x);
        return dateFormat.format(x);
    }

    private static double getDateNow() {
        return System.currentTimeMillis();
    }

    private static void throwRangeErrorIfOutOfValidRange(Object n) throws JSException {
        if (n != null && (n.equals(Double.NaN) || n.equals(Double.POSITIVE_INFINITY) || n.equals(Double.NEGATIVE_INFINITY))) {
            throw Errors.createRangeError("Provided date is not in valid range.");
        }
    }

    static final Map<DateFormat.Field, String> fieldToType = new HashMap<>();

    static {
        fieldToType.put(DateFormat.Field.AM_PM, "dayPeriod");
        fieldToType.put(DateFormat.Field.ERA, "era");
        fieldToType.put(DateFormat.Field.YEAR, "year");
        fieldToType.put(DateFormat.Field.MONTH, "month");
        fieldToType.put(DateFormat.Field.DOW_LOCAL, "weekday");
        fieldToType.put(DateFormat.Field.DAY_OF_MONTH, "day");
        fieldToType.put(DateFormat.Field.HOUR0, "hour");
        fieldToType.put(DateFormat.Field.HOUR1, "hour");
        fieldToType.put(DateFormat.Field.HOUR_OF_DAY0, "hour");
        fieldToType.put(DateFormat.Field.HOUR_OF_DAY1, "hour");
        fieldToType.put(DateFormat.Field.MINUTE, "minute");
        fieldToType.put(DateFormat.Field.SECOND, "second");
        fieldToType.put(DateFormat.Field.TIME_ZONE, "timeZoneName");
    }

    @TruffleBoundary
    public static DynamicObject formatToParts(JSContext context, DynamicObject numberFormatObj, Object n) {

        ensureIsDateTimeFormat(numberFormatObj);
        DateFormat dateFormat = getDateFormatProperty(numberFormatObj);

        Number x = (Undefined.instance == n) ? getDateNow() : JSRuntime.toNumber(n);
        throwRangeErrorIfOutOfValidRange(x);

        List<Object> resultParts = new LinkedList<>();
        AttributedCharacterIterator fit = dateFormat.formatToCharacterIterator(x);
        String formatted = dateFormat.format(x);
        int i = fit.getBeginIndex();
        while (i < fit.getEndIndex()) {
            fit.setIndex(i);
            Map<AttributedCharacterIterator.Attribute, Object> attributes = fit.getAttributes();
            Set<AttributedCharacterIterator.Attribute> attKeySet = attributes.keySet();
            if (!attKeySet.isEmpty()) {
                for (AttributedCharacterIterator.Attribute a : attKeySet) {
                    if (a instanceof DateFormat.Field) {
                        String value = formatted.substring(fit.getRunStart(), fit.getRunLimit());
                        String type = fieldToType.get(a);
                        resultParts.add(makePart(context, type, value));
                        i = fit.getRunLimit();
                        break;
                    } else {
                        throw Errors.shouldNotReachHere();
                    }
                }
            } else {
                String value = formatted.substring(fit.getRunStart(), fit.getRunLimit());
                resultParts.add(makePart(context, "literal", value));
                i = fit.getRunLimit();
            }
        }
        return JSArray.createConstant(context, resultParts.toArray());
    }

    private static Object makePart(JSContext context, String type, String value) {
        DynamicObject p = JSUserObject.create(context);
        JSObject.set(p, "type", type);
        JSObject.set(p, "value", value);
        return p;
    }

    public static class InternalState {

        public boolean initialized = false;
        public DateFormat dateFormat;
        public Locale javaLocale;

        DynamicObject boundFormatFunction = null;

        public String locale;
        public String calendar;
        public String numberingSystem = "latn";

        public String weekday;
        public String era;
        public String year;
        public String month;
        public String day;
        public String hour;
        public String minute;
        public String second;

        public Boolean hour12;
        public String hourCycle;

        public String timeZoneName;
        public String timeZone;

        DynamicObject toResolvedOptionsObject(JSContext context) {
            DynamicObject result = JSUserObject.create(context);
            JSObjectUtil.defineDataProperty(result, "locale", locale, JSAttributes.getDefault());
            JSObjectUtil.defineDataProperty(result, "numberingSystem", numberingSystem, JSAttributes.getDefault());
            if (weekday != null) {
                JSObjectUtil.defineDataProperty(result, "weekday", weekday, JSAttributes.getDefault());
            }
            if (era != null) {
                JSObjectUtil.defineDataProperty(result, "era", era, JSAttributes.getDefault());
            }
            if (year != null) {
                JSObjectUtil.defineDataProperty(result, "year", year, JSAttributes.getDefault());
            }
            if (month != null) {
                JSObjectUtil.defineDataProperty(result, "month", month, JSAttributes.getDefault());
            }
            if (day != null) {
                JSObjectUtil.defineDataProperty(result, "day", day, JSAttributes.getDefault());
            }
            if (hour != null) {
                JSObjectUtil.defineDataProperty(result, "hour", hour, JSAttributes.getDefault());
            }
            if (hour12 != null) {
                JSObjectUtil.defineDataProperty(result, "hour12", hour12, JSAttributes.getDefault());
            }
            if (hourCycle != null) {
                JSObjectUtil.defineDataProperty(result, "hourCycle", hourCycle, JSAttributes.getDefault());
            }
            if (minute != null) {
                JSObjectUtil.defineDataProperty(result, "minute", minute, JSAttributes.getDefault());
            }
            if (second != null) {
                JSObjectUtil.defineDataProperty(result, "second", second, JSAttributes.getDefault());
            }
            if (calendar != null) {
                JSObjectUtil.defineDataProperty(result, "calendar", calendar, JSAttributes.getDefault());
            }
            if (timeZone != null) {
                JSObjectUtil.defineDataProperty(result, "timeZone", timeZone, JSAttributes.getDefault());
            }
            if (timeZoneName != null) {
                JSObjectUtil.defineDataProperty(result, "timeZoneName", timeZoneName, JSAttributes.getDefault());
            }
            return result;
        }
    }

    @TruffleBoundary
    public static DynamicObject resolvedOptions(JSContext context, DynamicObject numberFormatObj) {
        ensureIsDateTimeFormat(numberFormatObj);
        InternalState state = getInternalState(numberFormatObj);
        return state.toResolvedOptionsObject(context);
    }

    public static InternalState getInternalState(DynamicObject numberFormatObj) {
        return (InternalState) INTERNAL_STATE_PROPERTY.get(numberFormatObj, isJSDateTimeFormat(numberFormatObj));
    }

    private static CallTarget createGetFormatCallTarget(JSRealm realm, JSContext context) {
        return Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {

            @Override
            public Object execute(VirtualFrame frame) {

                Object[] frameArgs = frame.getArguments();
                Object numberFormatObj = JSArguments.getThisObject(frameArgs);

                if (isJSDateTimeFormat(numberFormatObj)) {

                    InternalState state = getInternalState((DynamicObject) numberFormatObj);

                    if (state == null || !state.initialized) {
                        throw Errors.createTypeError("Method format called on a non-object or on a wrong type of object (uninitialized DateTimeFormat?).");
                    }

                    if (state.boundFormatFunction == null) {
                        DynamicObject formatFn = createFormatFunction(realm, context);
                        DynamicObject boundFn = JSFunction.boundFunctionCreate(context, realm, formatFn, numberFormatObj, new Object[]{}, JSObject.getPrototype(formatFn));
                        state.boundFormatFunction = boundFn;
                    }

                    return state.boundFormatFunction;
                }
                throw Errors.createTypeError("expected DateTimeFormat object");
            }
        });
    }

    private static void ensureIsDateTimeFormat(Object obj) {
        if (!isJSDateTimeFormat(obj)) {
            throw Errors.createTypeError("DateTimeFormat method called on a non-object or on a wrong type of object (uninitialized DateTimeFormat?).");
        }
    }

    private static DynamicObject createFormatFunction(JSRealm realm, JSContext context) {
        DynamicObject result = JSFunction.create(realm, JSFunctionData.createCallOnly(context, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] arguments = frame.getArguments();
                DynamicObject thisObj = JSRuntime.toObject(context, JSArguments.getThisObject(arguments));
                Object n = JSArguments.getUserArgumentCount(arguments) > 0 ? JSArguments.getUserArgument(arguments, 0) : Undefined.instance;
                return format(thisObj, n);
            }
        }), 1, "format"));
        return result;
    }

    private static DynamicObject createFormatFunctionGetter(JSRealm realm, JSContext context) {
        CallTarget ct = createGetFormatCallTarget(realm, context);
        JSFunctionData fd = JSFunctionData.create(context, ct, ct, 0, "get format", false, false, false, true);
        DynamicObject compareFunction = JSFunction.create(realm, fd);
        return compareFunction;
    }
}
