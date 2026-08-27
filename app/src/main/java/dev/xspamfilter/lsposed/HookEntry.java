package dev.xspamfilter.lsposed;

import android.content.res.AssetManager;
import android.content.res.XModuleResources;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * The P1 observational probes are retained alongside the P2 main-feed list filter.
 */
public final class HookEntry implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "dev.xspamfilter.lsposed";
    private static final String TARGET_PACKAGE = "com.twitter.android";
    private static final String TAG = "[XSF-PROBE]";
    private static final String FILTER_TAG = "[XSF-FILTER]";
    private static final String KEYWORDS_ASSET = "keywords_zpvip.txt";
    private static final int TEXT_PREVIEW_CODE_POINTS = 50;
    private static final Object KEYWORDS_LOCK = new Object();
    private static final AtomicBoolean ENTRY_ID_STACK_TRACE_DUMPED = new AtomicBoolean(false);
    private static volatile String modulePath;
    private static volatile List<String> normalizedKeywords;
    // JADX renders the real DEX type com.x.models.h1 as PostDisplayType via Kotlin metadata.
    private static final String[] URT_TIMELINE_POST_PRIMARY_CONSTRUCTOR_PARAMETER_TYPES = {
            "com.x.models.PostResult",
            "long",
            "java.lang.String",
            "com.x.models.SocialContext",
            "com.x.models.TimelinePromotedMetadata",
            "com.x.models.PrerollMetadata",
            "com.x.models.ClientEventInfo",
            "com.x.models.h1",
            "com.x.models.HostingModuleMetadata",
            "java.util.List",
            "java.lang.String",
            "com.x.models.timelines.items.TimelinePostFacepile"
    };
    private static final String[] URT_TIMELINE_POST_BRIDGE_CONSTRUCTOR_PARAMETER_TYPES = {
            "com.x.models.PostResult",
            "long",
            "java.lang.String",
            "com.x.models.SocialContext",
            "com.x.models.TimelinePromotedMetadata",
            "com.x.models.PrerollMetadata",
            "com.x.models.ClientEventInfo",
            "com.x.models.h1",
            "com.x.models.HostingModuleMetadata",
            "java.util.List",
            "java.lang.String",
            "com.x.models.timelines.items.TimelinePostFacepile",
            "int",
            "kotlin.jvm.internal.DefaultConstructorMarker"
    };
    private static final String[] MAIN_FEED_LAMBDA_CONSTRUCTOR_PARAMETER_TYPES = {
            "androidx.compose.runtime.internal.m",
            "com.x.urt.paging.f",
            "kotlinx.collections.immutable.c",
            "com.x.urt.paging.f",
            "androidx.compose.runtime.internal.m",
            "androidx.compose.foundation.lazy.w0",
            "kotlin.jvm.functions.Function2",
            "kotlin.jvm.functions.Function1",
            "kotlin.jvm.functions.Function3",
            "kotlin.jvm.functions.Function1",
            "kotlin.jvm.functions.Function2",
            "com.x.performance.g"
    };

    @Override
    public void initZygote(StartupParam startupParam) {
        if (startupParam == null
                || startupParam.modulePath == null
                || startupParam.modulePath.isEmpty()) {
            throw new IllegalStateException(
                    MODULE_PACKAGE + " did not receive a usable modulePath from Xposed");
        }

        String currentModulePath = modulePath;
        if (currentModulePath != null && !currentModulePath.equals(startupParam.modulePath)) {
            throw new IllegalStateException(
                    "Xposed modulePath changed from "
                            + currentModulePath
                            + " to "
                            + startupParam.modulePath);
        }
        modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + " loading into " + lpparam.packageName);

        installProbe(
                "com.x.models.PostResult#getText()",
                () -> hookPostResultGetText(lpparam.classLoader));
        installProbe(
                "com.x.models.timelines.items.UrtTimelinePost getters",
                () -> hookUrtTimelinePostGetters(lpparam.classLoader));
        installProbe(
                "com.x.models.timelines.items.UrtTimelinePost public constructor",
                () -> hookUrtTimelinePostConstructor(lpparam.classLoader, false));
        installProbe(
                "com.x.models.timelines.items.UrtTimelinePost default-parameter bridge constructor",
                () -> hookUrtTimelinePostConstructor(lpparam.classLoader, true));
        installProbe(
                "com.x.models.timelines.items.UrtTimelinePost$$serializer#deserialize(Decoder)",
                () -> hookUrtTimelinePostSerializerDeserialize(lpparam.classLoader));
        installProbe(
                "com.twitter.model.json.timeline.urt.JsonTimelineEntry#r()",
                () -> hookJsonTimelineEntry(lpparam.classLoader));
        installProbe(
                "com.twitter.model.json.timeline.urt.JsonAddEntriesInstruction#r()",
                () -> hookJsonAddEntriesInstruction(lpparam.classLoader));
        installRequiredFilter(
                "com.x.urt.ui.n0 main-feed immutable List constructor argument",
                () -> hookMainFeedListFilter(lpparam.classLoader));
    }

    private static void hookMainFeedListFilter(ClassLoader classLoader) throws Throwable {
        List<String> keywords = getOrLoadNormalizedKeywords();
        Class<?> mainFeedLambdaClass = null;

        try {
            mainFeedLambdaClass = XposedHelpers.findClass("com.x.urt.ui.n0", classLoader);
            Class<?>[] parameterTypes = resolveConstructorParameterTypes(
                    MAIN_FEED_LAMBDA_CONSTRUCTOR_PARAMETER_TYPES,
                    classLoader);
            Class<?> immutableListInterfaceClass = parameterTypes[2];
            Class<?> timelinePostClass = XposedHelpers.findClass(
                    "com.x.models.timelines.items.UrtTimelinePost",
                    classLoader);
            Method getTextMethod = requirePublicNoArgMethod(
                    timelinePostClass,
                    "getText",
                    String.class);
            Constructor<?> constructor = mainFeedLambdaClass.getDeclaredConstructor(parameterTypes);

            XposedBridge.hookMethod(
                    constructor,
                    createMainFeedListFilterCallback(
                            classLoader,
                            immutableListInterfaceClass,
                            timelinePostClass,
                            getTextMethod,
                            keywords));
            XposedBridge.log(
                    FILTER_TAG + " hook installed exact constructor: "
                            + mainFeedLambdaClass.getName()
                            + formatParameterTypeNames(
                            MAIN_FEED_LAMBDA_CONSTRUCTOR_PARAMETER_TYPES));
        } catch (Throwable throwable) {
            XposedBridge.log(
                    FILTER_TAG + " FAILED to install exact com.x.urt.ui.n0 constructor hook");
            XposedBridge.log(
                    FILTER_TAG + " expected parameter types="
                            + formatParameterTypeNames(
                            MAIN_FEED_LAMBDA_CONSTRUCTOR_PARAMETER_TYPES));
            if (mainFeedLambdaClass != null) {
                logDeclaredConstructors(FILTER_TAG, mainFeedLambdaClass);
            }
            throw throwable;
        }
    }

    private static XC_MethodHook createMainFeedListFilterCallback(
            ClassLoader classLoader,
            Class<?> immutableListInterfaceClass,
            Class<?> timelinePostClass,
            Method getTextMethod,
            List<String> keywords) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args == null || param.args.length != 12) {
                        throw new IllegalStateException(
                                "com.x.urt.ui.n0 constructor expected 12 arguments but received "
                                        + (param.args == null ? "<null>" : param.args.length));
                    }

                    Object originalObject = param.args[2];
                    if (originalObject == null) {
                        throw new IllegalStateException(
                                "com.x.urt.ui.n0 constructor argument 2 was null");
                    }
                    if (!immutableListInterfaceClass.isInstance(originalObject)) {
                        throw new IllegalStateException(
                                "com.x.urt.ui.n0 constructor argument 2 expected "
                                        + immutableListInterfaceClass.getName()
                                        + " but was "
                                        + originalObject.getClass().getName());
                    }
                    if (!(originalObject instanceof List<?>)) {
                        throw new IllegalStateException(
                                "com.x.urt.ui.n0 constructor argument 2 implements "
                                        + immutableListInterfaceClass.getName()
                                        + " but not java.util.List");
                    }

                    List<?> originalList = (List<?>) originalObject;
                    ArrayList<Object> filteredList = new ArrayList<>(originalList.size());
                    int removed = 0;
                    for (int i = 0; i < originalList.size(); i++) {
                        Object item = originalList.get(i);
                        if (!timelinePostClass.isInstance(item)) {
                            filteredList.add(item);
                            continue;
                        }

                        Object textResult = XposedBridge.invokeOriginalMethod(
                                getTextMethod,
                                item,
                                new Object[0]);
                        if (textResult != null && !(textResult instanceof String)) {
                            throw new IllegalStateException(
                                    "UrtTimelinePost.getText() returned "
                                            + textResult.getClass().getName()
                                            + " instead of java.lang.String");
                        }

                        String normalizedText = normalizeForKeywordMatch((String) textResult);
                        if (containsKeyword(normalizedText, keywords)) {
                            removed++;
                        } else {
                            filteredList.add(item);
                        }
                    }

                    Object filteredProxy = createImmutableListProxy(
                            classLoader,
                            immutableListInterfaceClass,
                            originalObject,
                            filteredList);
                    if (!immutableListInterfaceClass.isInstance(filteredProxy)) {
                        throw new IllegalStateException(
                                "Proxy did not implement "
                                        + immutableListInterfaceClass.getName());
                    }

                    param.args[2] = filteredProxy;
                    XposedBridge.log(
                            FILTER_TAG + " main-feed list filtered: original="
                                    + originalList.size()
                                    + " kept="
                                    + filteredList.size()
                                    + " removed="
                                    + removed);
                } catch (Throwable throwable) {
                    XposedBridge.log(
                            FILTER_TAG + " callback FAILED before com.x.urt.ui.n0 constructor; "
                                    + "refusing to continue with the unfiltered list");
                    logThrowable(FILTER_TAG, throwable);
                    param.setThrowable(
                            new IllegalStateException(
                                    "Main-feed keyword filtering failed",
                                    throwable));
                }
            }
        };
    }

    private static Object createImmutableListProxy(
            ClassLoader classLoader,
            Class<?> immutableListInterfaceClass,
            Object originalObject,
            List<?> filteredList) {
        return Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{immutableListInterfaceClass},
                new FilteredImmutableListHandler(
                        classLoader,
                        immutableListInterfaceClass,
                        originalObject,
                        filteredList));
    }

    private static List<String> getOrLoadNormalizedKeywords() throws Throwable {
        List<String> currentKeywords = normalizedKeywords;
        if (currentKeywords != null) {
            return currentKeywords;
        }

        synchronized (KEYWORDS_LOCK) {
            currentKeywords = normalizedKeywords;
            if (currentKeywords != null) {
                return currentKeywords;
            }

            String currentModulePath = modulePath;
            if (currentModulePath == null || currentModulePath.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot load " + KEYWORDS_ASSET + ": Xposed modulePath is unavailable");
            }

            XModuleResources moduleResources = XModuleResources.createInstance(
                    currentModulePath,
                    null);
            AssetManager assetManager = moduleResources.getAssets();
            Set<String> uniqueKeywords = new LinkedHashSet<>();
            int nonEmptyLines = 0;

            try (InputStream inputStream = assetManager.open(KEYWORDS_ASSET);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }

                    nonEmptyLines++;
                    String normalized = normalizeForKeywordMatch(trimmed);
                    if (normalized.isEmpty()) {
                        throw new IllegalStateException(
                                KEYWORDS_ASSET
                                        + " line "
                                        + lineNumber
                                        + " becomes empty after normalization");
                    }
                    uniqueKeywords.add(normalized);
                }
            }

            if (uniqueKeywords.isEmpty()) {
                throw new IllegalStateException(
                        KEYWORDS_ASSET + " did not contain any usable keywords");
            }

            currentKeywords = Collections.unmodifiableList(
                    new ArrayList<>(uniqueKeywords));
            normalizedKeywords = currentKeywords;
            XposedBridge.log(
                    FILTER_TAG + " loaded "
                            + currentKeywords.size()
                            + " normalized keywords from "
                            + KEYWORDS_ASSET
                            + " (non-empty lines="
                            + nonEmptyLines
                            + ")");
            return currentKeywords;
        }
    }

    private static String normalizeForKeywordMatch(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String lowerCase = value.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lowerCase.length());
        for (int offset = 0; offset < lowerCase.length(); ) {
            int codePoint = lowerCase.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    private static boolean containsKeyword(String normalizedText, List<String> keywords) {
        for (String keyword : keywords) {
            if (normalizedText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static final class FilteredImmutableListHandler implements InvocationHandler {
        private final ClassLoader classLoader;
        private final Class<?> immutableListInterfaceClass;
        private final Object originalObject;
        private final List<?> filteredList;

        private FilteredImmutableListHandler(
                ClassLoader classLoader,
                Class<?> immutableListInterfaceClass,
                Object originalObject,
                List<?> filteredList) {
            this.classLoader = classLoader;
            this.immutableListInterfaceClass = immutableListInterfaceClass;
            this.originalObject = originalObject;
            this.filteredList = filteredList;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            int parameterCount = method.getParameterTypes().length;

            if ("equals".equals(methodName) && parameterCount == 1) {
                return filteredList.equals(args[0]);
            }
            if ("hashCode".equals(methodName) && parameterCount == 0) {
                return filteredList.hashCode();
            }
            if ("toString".equals(methodName) && parameterCount == 0) {
                return filteredList.toString();
            }

            if (isCovariantImmutableSubList(method, immutableListInterfaceClass)) {
                int fromIndex = (Integer) args[0];
                int toIndex = (Integer) args[1];
                Object originalSubList = invokeDelegate(method, originalObject, args);
                if (originalSubList == null
                        || !immutableListInterfaceClass.isInstance(originalSubList)
                        || !(originalSubList instanceof List<?>)) {
                    throw new IllegalStateException(
                            "Original immutable subList returned "
                                    + (originalSubList == null
                                    ? "<null>"
                                    : originalSubList.getClass().getName()));
                }
                return createImmutableListProxy(
                        classLoader,
                        immutableListInterfaceClass,
                        originalSubList,
                        filteredList.subList(fromIndex, toIndex));
            }

            if (isStandardReadOnlyListMethod(method)) {
                return invokeDelegate(method, filteredList, args);
            }

            XposedBridge.log(
                    FILTER_TAG + " encountered unexpected immutable-list method; "
                            + "forwarding to original unfiltered object: "
                            + method);
            return invokeDelegate(method, originalObject, args);
        }
    }

    private static boolean isCovariantImmutableSubList(
            Method method,
            Class<?> immutableListInterfaceClass) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return "subList".equals(method.getName())
                && parameterTypes.length == 2
                && parameterTypes[0] == int.class
                && parameterTypes[1] == int.class
                && method.getReturnType() == immutableListInterfaceClass;
    }

    private static boolean isStandardReadOnlyListMethod(Method method) {
        Class<?> declaringClass = method.getDeclaringClass();
        if (declaringClass != List.class
                && declaringClass != Collection.class
                && declaringClass != Iterable.class) {
            return false;
        }

        switch (method.getName()) {
            case "contains":
            case "containsAll":
            case "forEach":
            case "get":
            case "getFirst":
            case "getLast":
            case "indexOf":
            case "isEmpty":
            case "iterator":
            case "lastIndexOf":
            case "listIterator":
            case "parallelStream":
            case "reversed":
            case "size":
            case "spliterator":
            case "stream":
            case "subList":
            case "toArray":
                return true;
            default:
                return false;
        }
    }

    private static Object invokeDelegate(
            Method method,
            Object receiver,
            Object[] args) throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw cause == null ? exception : cause;
        }
    }

    private static void hookPostResultGetText(ClassLoader classLoader) {
        Class<?> postResultClass = XposedHelpers.findClass(
                "com.x.models.PostResult",
                classLoader);

        XposedHelpers.findAndHookMethod(
                postResultClass,
                "getText",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        logAfterResult(
                                "PostResult.getText() -> ",
                                param,
                                preview(param.getResult()));
                    }
                });
    }

    /**
     * The current APK has three long constructor forms. Its two stable, public, no-arg
     * getters provide the same probe evidence without hard-coding obfuscated parameter types.
     */
    private static void hookUrtTimelinePostGetters(ClassLoader classLoader) {
        Class<?> timelinePostClass = XposedHelpers.findClass(
                "com.x.models.timelines.items.UrtTimelinePost",
                classLoader);

        boolean foundGetText = false;
        boolean foundGetEntryId = false;
        int matched = 0;
        int installed = 0;

        for (Method method : timelinePostClass.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.getParameterTypes().length != 0) {
                continue;
            }

            String methodName = method.getName();
            if (!"getText".equals(methodName) && !"getEntryId".equals(methodName)) {
                continue;
            }

            matched++;
            foundGetText |= "getText".equals(methodName);
            foundGetEntryId |= "getEntryId".equals(methodName);

            try {
                XposedHelpers.findAndHookMethod(
                        timelinePostClass,
                        methodName,
                        createUrtTimelinePostCallback(methodName));
                installed++;
                XposedBridge.log(TAG + " hook installed: " + method);
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + " FAILED to install UrtTimelinePost method hook: " + method);
                logThrowable(throwable);
            }
        }

        if (!foundGetText || !foundGetEntryId || installed != matched) {
            throw new IllegalStateException(
                    "UrtTimelinePost getter hooks incomplete: foundGetText=" + foundGetText
                            + ", foundGetEntryId=" + foundGetEntryId
                            + ", matched=" + matched
                            + ", installed=" + installed);
        }
    }

    private static XC_MethodHook createUrtTimelinePostCallback(String methodName) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if ("getText".equals(methodName)) {
                    try {
                        Object entryId = XposedHelpers.callMethod(param.thisObject, "getEntryId");
                        logAfterResult(
                                "UrtTimelinePost.getText() -> ",
                                param,
                                preview(param.getResult()) + " entryId=" + printable(entryId));
                    } catch (Throwable throwable) {
                        logCallbackFailure("UrtTimelinePost.getText() / getEntryId()", throwable);
                    }
                    return;
                }

                if (ENTRY_ID_STACK_TRACE_DUMPED.compareAndSet(false, true)) {
                    StringWriter stackTrace = new StringWriter();
                    new Throwable("XSF-PROBE stacktrace capture")
                            .printStackTrace(new PrintWriter(stackTrace));
                    for (String line : stackTrace.toString().split("\\r?\\n")) {
                        XposedBridge.log(TAG + " [STACKTRACE] " + line);
                    }
                }

                logAfterResult(
                        "UrtTimelinePost.getEntryId() -> ",
                        param,
                        printable(param.getResult()));
            }
        };
    }

    private static void hookUrtTimelinePostConstructor(
            ClassLoader classLoader,
            boolean bridge) throws Throwable {
        Class<?> timelinePostClass = XposedHelpers.findClass(
                "com.x.models.timelines.items.UrtTimelinePost",
                classLoader);
        String constructorLabel = bridge
                ? "UrtTimelinePost.<init:bridge>()"
                : "UrtTimelinePost.<init>()";
        String[] expectedParameterTypeNames = bridge
                ? URT_TIMELINE_POST_BRIDGE_CONSTRUCTOR_PARAMETER_TYPES
                : URT_TIMELINE_POST_PRIMARY_CONSTRUCTOR_PARAMETER_TYPES;

        try {
            Class<?>[] parameterTypes = resolveConstructorParameterTypes(
                    expectedParameterTypeNames,
                    classLoader);
            Method getTextMethod = requirePublicNoArgMethod(
                    timelinePostClass,
                    "getText",
                    String.class);
            Method getEntryIdMethod = requirePublicNoArgMethod(
                    timelinePostClass,
                    "getEntryId",
                    String.class);

            Object[] parameterTypesAndCallback = new Object[parameterTypes.length + 1];
            System.arraycopy(
                    parameterTypes,
                    0,
                    parameterTypesAndCallback,
                    0,
                    parameterTypes.length);
            parameterTypesAndCallback[parameterTypes.length] =
                    createUrtTimelinePostConstructorCallback(
                            timelinePostClass,
                            getTextMethod,
                            getEntryIdMethod,
                            constructorLabel + " -> ");

            XposedHelpers.findAndHookConstructor(
                    timelinePostClass,
                    parameterTypesAndCallback);
            XposedBridge.log(
                    TAG + " hook installed exact constructor: "
                            + timelinePostClass.getName()
                            + formatParameterTypeNames(expectedParameterTypeNames));
        } catch (Throwable throwable) {
            logConstructorInstallFailure(
                    timelinePostClass,
                    constructorLabel,
                    expectedParameterTypeNames);
            throw throwable;
        }
    }

    private static Class<?>[] resolveConstructorParameterTypes(
            String[] parameterTypeNames,
            ClassLoader classLoader) {
        Class<?>[] parameterTypes = new Class<?>[parameterTypeNames.length];
        for (int i = 0; i < parameterTypeNames.length; i++) {
            String parameterTypeName = parameterTypeNames[i];
            if ("long".equals(parameterTypeName)) {
                parameterTypes[i] = long.class;
            } else if ("int".equals(parameterTypeName)) {
                parameterTypes[i] = int.class;
            } else {
                parameterTypes[i] = XposedHelpers.findClass(parameterTypeName, classLoader);
            }
        }
        return parameterTypes;
    }

    private static XC_MethodHook createUrtTimelinePostConstructorCallback(
            Class<?> timelinePostClass,
            Method getTextMethod,
            Method getEntryIdMethod,
            String event) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) {
                    logAfterResult(event, param, "");
                    return;
                }

                try {
                    Object timelinePost = param.thisObject;
                    if (timelinePost == null) {
                        throw new IllegalStateException(
                                "constructor completed without a thisObject");
                    }
                    if (!timelinePostClass.isInstance(timelinePost)) {
                        throw new IllegalStateException(
                                "constructor thisObject expected "
                                        + timelinePostClass.getName()
                                        + " but was "
                                        + timelinePost.getClass().getName());
                    }

                    Object text = XposedBridge.invokeOriginalMethod(
                            getTextMethod,
                            timelinePost,
                            new Object[0]);
                    Object entryId = XposedBridge.invokeOriginalMethod(
                            getEntryIdMethod,
                            timelinePost,
                            new Object[0]);
                    logAfterResult(
                            event,
                            param,
                            preview(text) + " entryId=" + printable(entryId));
                } catch (Throwable throwable) {
                    logCallbackFailure(event + "result getters", throwable);
                }
            }
        };
    }

    private static void logConstructorInstallFailure(
            Class<?> timelinePostClass,
            String constructorLabel,
            String[] expectedParameterTypeNames) {
        XposedBridge.log(TAG + " FAILED to install constructor hook: " + constructorLabel);
        XposedBridge.log(
                TAG + " expected parameter types="
                        + formatParameterTypeNames(expectedParameterTypeNames));

        logDeclaredConstructors(TAG, timelinePostClass);
    }

    private static void logDeclaredConstructors(String tag, Class<?> targetClass) {
        try {
            Constructor<?>[] candidates = targetClass.getDeclaredConstructors();
            if (candidates.length == 0) {
                XposedBridge.log(tag + " actual declared constructor candidates=<none>");
                return;
            }
            for (Constructor<?> candidate : candidates) {
                XposedBridge.log(tag + " actual declared constructor candidate: " + candidate);
            }
        } catch (Throwable throwable) {
            XposedBridge.log(tag + " FAILED to enumerate declared constructor candidates");
            logThrowable(tag, throwable);
        }
    }

    private static String formatParameterTypeNames(String[] parameterTypeNames) {
        StringBuilder result = new StringBuilder("(");
        for (int i = 0; i < parameterTypeNames.length; i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append(parameterTypeNames[i]);
        }
        return result.append(')').toString();
    }

    private static void hookUrtTimelinePostSerializerDeserialize(ClassLoader classLoader) {
        Class<?> serializerClass = XposedHelpers.findClass(
                "com.x.models.timelines.items.UrtTimelinePost$$serializer",
                classLoader);
        Class<?> timelinePostClass = XposedHelpers.findClass(
                "com.x.models.timelines.items.UrtTimelinePost",
                classLoader);
        Class<?> decoderClass = XposedHelpers.findClass(
                "kotlinx.serialization.encoding.Decoder",
                classLoader);

        Method deserializeMethod = null;
        StringBuilder candidates = new StringBuilder();
        for (Method method : serializerClass.getDeclaredMethods()) {
            if (!"deserialize".equals(method.getName())) {
                continue;
            }

            if (candidates.length() > 0) {
                candidates.append("; ");
            }
            candidates.append(method);

            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean isConcreteTypedImplementation = Modifier.isPublic(method.getModifiers())
                    && !method.isBridge()
                    && parameterTypes.length == 1
                    && parameterTypes[0] == decoderClass
                    && method.getReturnType() == timelinePostClass;
            if (!isConcreteTypedImplementation) {
                continue;
            }

            if (deserializeMethod != null) {
                throw new IllegalStateException(
                        "Multiple concrete UrtTimelinePost deserialize methods found: "
                                + candidates);
            }
            deserializeMethod = method;
        }

        if (deserializeMethod == null) {
            throw new IllegalStateException(
                    "No concrete deserialize(Decoder): UrtTimelinePost method found on "
                            + serializerClass.getName()
                            + "; deserialize candidates="
                            + (candidates.length() == 0 ? "<none>" : candidates));
        }

        Method getTextMethod = requirePublicNoArgMethod(
                timelinePostClass,
                "getText",
                String.class);
        Method getEntryIdMethod = requirePublicNoArgMethod(
                timelinePostClass,
                "getEntryId",
                String.class);

        XposedBridge.hookMethod(
                deserializeMethod,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) {
                            logAfterResult("serializer.deserialize() -> ", param, "");
                            return;
                        }

                        try {
                            Object timelinePost = param.getResult();
                            if (timelinePost == null) {
                                logAfterResult(
                                        "serializer.deserialize() -> ",
                                        param,
                                        "<null> entryId=<null>");
                                return;
                            }
                            if (!timelinePostClass.isInstance(timelinePost)) {
                                throw new IllegalStateException(
                                        "deserialize result expected "
                                                + timelinePostClass.getName()
                                                + " but was "
                                                + timelinePost.getClass().getName());
                            }

                            Object text = XposedBridge.invokeOriginalMethod(
                                    getTextMethod,
                                    timelinePost,
                                    new Object[0]);
                            Object entryId = XposedBridge.invokeOriginalMethod(
                                    getEntryIdMethod,
                                    timelinePost,
                                    new Object[0]);
                            logAfterResult(
                                    "serializer.deserialize() -> ",
                                    param,
                                    preview(text) + " entryId=" + printable(entryId));
                        } catch (Throwable throwable) {
                            logCallbackFailure(
                                    "serializer.deserialize() result getters",
                                    throwable);
                        }
                    }
                });

        XposedBridge.log(TAG + " hook installed exact method: " + deserializeMethod);
    }

    private static Method requirePublicNoArgMethod(
            Class<?> declaringClass,
            String methodName,
            Class<?> returnType) {
        Method match = null;
        for (Method method : declaringClass.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())
                    || !Modifier.isPublic(method.getModifiers())
                    || method.getParameterTypes().length != 0
                    || method.getReturnType() != returnType) {
                continue;
            }

            if (match != null) {
                throw new IllegalStateException(
                        "Multiple matching methods found for "
                                + declaringClass.getName()
                                + "#"
                                + methodName
                                + "(): "
                                + match
                                + "; "
                                + method);
            }
            match = method;
        }

        if (match == null) {
            throw new IllegalStateException(
                    "Required method not found: "
                            + declaringClass.getName()
                            + "#"
                            + methodName
                            + "(): "
                            + returnType.getName());
        }
        return match;
    }

    private static void hookJsonTimelineEntry(ClassLoader classLoader) {
        Class<?> timelineEntryClass = XposedHelpers.findClass(
                "com.twitter.model.json.timeline.urt.JsonTimelineEntry",
                classLoader);

        XposedHelpers.findAndHookMethod(
                timelineEntryClass,
                "r",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object entryId = XposedHelpers.getObjectField(param.thisObject, "a");
                            logAfterResult(
                                    "JsonTimelineEntry.r() -> entryId=",
                                    param,
                                    printable(entryId));
                        } catch (Throwable throwable) {
                            logCallbackFailure("JsonTimelineEntry.r() field a (entryId)", throwable);
                        }
                    }
                });
    }

    private static void hookJsonAddEntriesInstruction(ClassLoader classLoader) {
        Class<?> instructionClass = XposedHelpers.findClass(
                "com.twitter.model.json.timeline.urt.JsonAddEntriesInstruction",
                classLoader);

        XposedHelpers.findAndHookMethod(
                instructionClass,
                "r",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object entries = XposedHelpers.getObjectField(param.thisObject, "a");
                            String count;
                            if (entries == null) {
                                count = "<null>";
                            } else if (entries instanceof Collection<?>) {
                                count = Integer.toString(((Collection<?>) entries).size());
                            } else {
                                throw new IllegalStateException(
                                        "field a expected Collection but was "
                                                + entries.getClass().getName());
                            }

                            logAfterResult(
                                    "JsonAddEntriesInstruction.r() -> entries=",
                                    param,
                                    count);
                        } catch (Throwable throwable) {
                            logCallbackFailure(
                                    "JsonAddEntriesInstruction.r() field a (entries)",
                                    throwable);
                        }
                    }
                });
    }

    private static void logAfterResult(
            String event,
            XC_MethodHook.MethodHookParam param,
            String value) {
        if (param.hasThrowable()) {
            XposedBridge.log(
                    TAG + " " + event + "<threw " + param.getThrowable() + ">"
                            + " method=" + param.method
                            + " ts=" + System.currentTimeMillis());
            return;
        }

        XposedBridge.log(
                TAG + " " + event + value
                        + " method=" + param.method
                        + " ts=" + System.currentTimeMillis());
    }

    private static String preview(Object value) {
        if (value == null) {
            return "<null>";
        }

        String text = String.valueOf(value);
        int codePointCount = text.codePointCount(0, text.length());
        int previewCount = Math.min(TEXT_PREVIEW_CODE_POINTS, codePointCount);
        int endIndex = text.offsetByCodePoints(0, previewCount);
        return text.substring(0, endIndex).replace('\n', ' ').replace('\r', ' ');
    }

    private static String printable(Object value) {
        return value == null ? "<null>" : String.valueOf(value);
    }

    private static void installProbe(String name, ProbeInstaller installer) {
        try {
            installer.install();
            XposedBridge.log(TAG + " hook installed: " + name);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " FAILED to install hook: " + name);
            logThrowable(throwable);
        }
    }

    private static void installRequiredFilter(
            String name,
            ProbeInstaller installer) throws Throwable {
        try {
            installer.install();
            XposedBridge.log(FILTER_TAG + " hook installed: " + name);
        } catch (Throwable throwable) {
            XposedBridge.log(FILTER_TAG + " FAILED to install required hook: " + name);
            logThrowable(FILTER_TAG, throwable);
            throw throwable;
        }
    }

    private static void logCallbackFailure(String name, Throwable throwable) {
        XposedBridge.log(TAG + " callback FAILED: " + name);
        logThrowable(throwable);
    }

    private static void logThrowable(Throwable throwable) {
        logThrowable(TAG, throwable);
    }

    private static void logThrowable(String tag, Throwable throwable) {
        StringWriter stackTrace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stackTrace));
        for (String line : stackTrace.toString().split("\\r?\\n")) {
            XposedBridge.log(tag + " " + line);
        }
    }

    @FunctionalInterface
    private interface ProbeInstaller {
        void install() throws Throwable;
    }
}
