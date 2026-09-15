package com.mfga.xposed.legacy;

import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;

import com.mfga.xposed.FontForceCore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 旧版 Xposed API 入口
 * 通过 assets/xposed_init 声明。
 */
public class LegacyEntry implements IXposedHookLoadPackage {

    private static final String TAG = "MFGA";
    // 每个 hook 点最多打这么多条命中日志，防止字体方法被高频调用时把
    // Xposed 日志刷爆（比如列表页滚动时 Typeface 相关方法可能被调用
    // 几十上百次）。
    private static final int MAX_HIT_LOGS_PER_METHOD = 20;

    private static final java.util.Set<String> TARGET_PACKAGES = new java.util.HashSet<>(
            java.util.Arrays.asList("com.github.android", "com.twitter.android", "org.telegram.messenger", "xyz.nextalone.nagram", "com.zhiliaoapp.musically", "com.google.android.youtube"));

    private final ConcurrentHashMap<String, AtomicInteger> hitCounters = new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }
        Log.i(TAG, "MFGA v1.6 (legacy) attach: " + lpparam.packageName);

        ClassLoader cl = lpparam.classLoader;

        // 单字体文件路径：Android 26+ 上 createFromAsset / createFromFile 内部
        // 最终都会走到 Typeface.Builder#build()
        try {
            XposedHelpers.findAndHookMethod(
                    "android.graphics.Typeface$Builder", cl,
                    "build", makeHook("Typeface.Builder#build"));
        } catch (Throwable t) {
            Log.w(TAG, "hook Typeface.Builder#build failed", t);
        }

        // 多字重 font-family 路径（比如 res/font/inter.xml 这种声明了多个字重/斜体
        // 变体的 family）：Android 29+ 上系统实际走的是
        // Typeface.CustomFallbackBuilder#build()，不经过上面的 Typeface.Builder。
        try {
            XposedHelpers.findAndHookMethod(
                    "android.graphics.Typeface$CustomFallbackBuilder", cl,
                    "build", makeHook("Typeface.CustomFallbackBuilder#build"));
        } catch (Throwable t) {
            Log.w(TAG, "hook Typeface.CustomFallbackBuilder#build failed", t);
        }

        // 兜底：部分老代码路径可能不经过 Builder，直接补几个静态工厂方法
        try {
            XposedHelpers.findAndHookMethod(
                    Typeface.class, "createFromAsset",
                    android.content.res.AssetManager.class, String.class,
                    makeHook("Typeface.createFromAsset"));
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                    Typeface.class, "createFromFile",
                    java.io.File.class, makeHook("Typeface.createFromFile(File)"));
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                    Typeface.class, "createFromFile",
                    String.class, makeHook("Typeface.createFromFile(String)"));
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedHelpers.findAndHookMethod(
                        Typeface.class, "create",
                        Typeface.class, int.class, boolean.class,
                        makeHook("Typeface.create(Typeface,int,boolean)"));
            } catch (Throwable t) {
                Log.w(TAG, "hook Typeface.create(Typeface,int,boolean) failed", t);
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                    Typeface.class, "create",
                    Typeface.class, int.class,
                    makeHook("Typeface.create(Typeface,int)"));
        } catch (Throwable t) {
            Log.w(TAG, "hook Typeface.create(Typeface,int) failed", t);
        }

        Log.i(TAG, "MFGA v1.6 hook installation finished for " + lpparam.packageName);
    }

    /** 给每个 hook 点单独建一个 XC_MethodHook，带命中日志（限速）+ 调用栈。 */
    private XC_MethodHook makeHook(String label) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (FontForceCore.isReplacing()) {
                    // 防止我们自己生成替换字体时又被同一个 hook 拦截，造成死循环
                    return;
                }
                Object result = param.getResult();
                Typeface original = (result instanceof Typeface) ? (Typeface) result : null;
                Typeface replacement = FontForceCore.systemReplacementFor(original);
                param.setResult(replacement);
                logHit(label, original, replacement);
            }
        };
    }

    private void logHit(String label, Typeface original, Typeface replacement) {
        AtomicInteger counter = hitCounters.computeIfAbsent(label, k -> new AtomicInteger(0));
        int n = counter.incrementAndGet();
        if (n > MAX_HIT_LOGS_PER_METHOD) {
            if (n == MAX_HIT_LOGS_PER_METHOD + 1) {
                Log.i(TAG, "[" + label + "] 已达 " + MAX_HIT_LOGS_PER_METHOD + " 条命中日志上限，后续不再打印");
            }
            return;
        }
        int weight = -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && original != null) {
            try {
                weight = original.getWeight();
            } catch (Throwable ignored) {
            }
        }
        int style = original != null ? original.getStyle() : -1;
        StringBuilder caller = new StringBuilder();
        int shown = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cn = frame.getClassName();
            if (cn.startsWith("com.mfga.xposed")
                    || cn.startsWith("android.graphics.Typeface")
                    || cn.startsWith("de.robv.android.xposed")
                    || cn.startsWith("java.lang.Thread")) {
                continue;
            }
            if (shown > 0) caller.append(" <- ");
            caller.append(cn).append(".").append(frame.getMethodName());
            shown++;
            if (shown >= 4) break;
        }
        Log.i(TAG, "[" + label + "] hit #" + n + " weight=" + weight + " style=" + style
                + " -> replaced=" + (replacement != null) + "; caller: " + caller);
    }
}
