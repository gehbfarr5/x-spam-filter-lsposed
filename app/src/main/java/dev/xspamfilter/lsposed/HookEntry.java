package dev.xspamfilter.lsposed;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * P1-only LSPosed probe. Every callback is observational: it never changes arguments,
 * results, throwables, fields, or host collections.
 */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.twitter.android";
    private static final String TAG = "[XSF-PROBE]";
    private static final int TEXT_PREVIEW_CODE_POINTS = 50;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
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
                "com.twitter.model.json.timeline.urt.JsonTimelineEntry#r()",
                () -> hookJsonTimelineEntry(lpparam.classLoader));
        installProbe(
                "com.twitter.model.json.timeline.urt.JsonAddEntriesInstruction#r()",
                () -> hookJsonAddEntriesInstruction(lpparam.classLoader));
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

                logAfterResult(
                        "UrtTimelinePost.getEntryId() -> ",
                        param,
                        printable(param.getResult()));
            }
        };
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

    private static void logCallbackFailure(String name, Throwable throwable) {
        XposedBridge.log(TAG + " callback FAILED: " + name);
        logThrowable(throwable);
    }

    private static void logThrowable(Throwable throwable) {
        StringWriter stackTrace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stackTrace));
        for (String line : stackTrace.toString().split("\\r?\\n")) {
            XposedBridge.log(TAG + " " + line);
        }
    }

    @FunctionalInterface
    private interface ProbeInstaller {
        void install() throws Throwable;
    }
}
