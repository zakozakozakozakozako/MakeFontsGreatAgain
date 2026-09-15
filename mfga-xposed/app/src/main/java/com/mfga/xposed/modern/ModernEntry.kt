package com.mfga.xposed.modern

import android.graphics.Typeface
import android.os.Build
import android.util.Log
import com.mfga.xposed.FontForceCore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "MFGA"

// 每个 hook 点最多打这么多条命中日志，防止字体方法被高频调用时把
// LSPosed 日志刷爆（尤其是列表页滚动时，Typeface 相关方法可能每秒
// 被调用几十上百次）。
private const val MAX_HIT_LOGS_PER_METHOD = 20

class ModernEntry : XposedModule() {

    private val hitCounters = ConcurrentHashMap<String, AtomicInteger>()

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        log(Log.INFO, TAG, "MFGA v1.7 (modern) attach: " + param.packageName)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)

        val cl = param.classLoader

        // 单字体文件路径：createFromAsset / createFromFile 内部走 Typeface.Builder#build()
        runCatching {
            val builderClass = Class.forName("android.graphics.Typeface\$Builder", false, cl)
            hookAndReplace("Typeface.Builder#build", builderClass.getDeclaredMethod("build"))
        }.onFailure { log(Log.WARN, TAG, "hook Typeface.Builder#build failed: $it") }

        // 多字重 font-family 路径（比如 res/font/inter.xml 这种声明了 regular/medium
        // 等多个字重变体的 family）：Android 29+ 上系统实际走的是
        // Typeface.CustomFallbackBuilder#build()。
        runCatching {
            val fallbackBuilderClass =
                Class.forName("android.graphics.Typeface\$CustomFallbackBuilder", false, cl)
            hookAndReplace(
                "Typeface.CustomFallbackBuilder#build",
                fallbackBuilderClass.getDeclaredMethod("build")
            )
        }.onFailure { log(Log.WARN, TAG, "hook Typeface.CustomFallbackBuilder#build failed: $it") }

        // 兜底静态工厂方法
        hookStaticFactory(cl, "createFromAsset")
        hookStaticFactory(cl, "createFromFile")
        hookCreateWithWeight(cl)   // Typeface.create(Typeface, int weight, boolean italic) — API 28+
        hookCreateWithStyle(cl)    // Typeface.create(Typeface, int style)

        log(Log.INFO, TAG, "MFGA v1.7 hook installation finished for " + param.packageName)
    }

    private fun hookStaticFactory(cl: ClassLoader, methodName: String) {
        runCatching {
            val typefaceClass = Class.forName("android.graphics.Typeface", false, cl)
            for (m in typefaceClass.declaredMethods) {
                if (m.name != methodName) continue
                hookAndReplace("Typeface.$methodName", m)
            }
        }.onFailure { log(Log.WARN, TAG, "hook Typeface.$methodName failed: $it") }
    }

    private fun hookCreateWithWeight(cl: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Typeface.create(Typeface, int, boolean) 是 API 28 (P) 才加入的，
            // 更老的系统上这个重载根本不存在，findMethod 会直接失败，跳过。
            return
        }
        runCatching {
            val typefaceClass = Class.forName("android.graphics.Typeface", false, cl)
            val m = typefaceClass.getDeclaredMethod(
                "create", typefaceClass, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            )
            hookAndReplace("Typeface.create(Typeface,int,boolean)", m)
        }.onFailure { log(Log.WARN, TAG, "hook Typeface.create(Typeface,int,boolean) failed: $it") }
    }

    private fun hookCreateWithStyle(cl: ClassLoader) {
        runCatching {
            val typefaceClass = Class.forName("android.graphics.Typeface", false, cl)
            val m = typefaceClass.getDeclaredMethod(
                "create", typefaceClass, Int::class.javaPrimitiveType
            )
            hookAndReplace("Typeface.create(Typeface,int)", m)
        }.onFailure { log(Log.WARN, TAG, "hook Typeface.create(Typeface,int) failed: $it") }
    }

    /** 统一的 hook 逻辑：deoptimize 绕过内联 + 把结果换成系统字体（保留原本 style/weight）。 */
    private fun hookAndReplace(label: String, m: java.lang.reflect.Executable) {
        deoptimize(m)
        hook(m).intercept { chain ->
            if (FontForceCore.isReplacing()) {
                return@intercept chain.proceed()
            }
            val original = chain.proceed() as? Typeface
            val replacement = FontForceCore.systemReplacementFor(original)
            logHit(label, original, replacement)
            replacement
        }
    }

    private fun logHit(label: String, original: Typeface?, replacement: Typeface?) {
        val counter = hitCounters.computeIfAbsent(label) { AtomicInteger(0) }
        val n = counter.incrementAndGet()
        if (n > MAX_HIT_LOGS_PER_METHOD) {
            if (n == MAX_HIT_LOGS_PER_METHOD + 1) {
                log(Log.INFO, TAG, "[$label] 已达 $MAX_HIT_LOGS_PER_METHOD 条命中日志上限，后续不再打印")
            }
            return
        }
        val weight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && original != null) {
            runCatching { original.weight }.getOrDefault(-1)
        } else -1
        val style = original?.style ?: -1
        // 只保留调用栈里 app 自己 / TikTok 的帧，跳过 Xposed 桥接和 Android 框架
        // 自身的帧，方便直接定位是哪个类在请求字体。
        val callerFrames = Thread.currentThread().stackTrace
            .drop(1)
            .filterNot {
                it.className.startsWith("com.mfga.xposed") ||
                    it.className.startsWith("android.graphics.Typeface") ||
                    it.className.startsWith("io.github.libxposed") ||
                    it.className.startsWith("java.lang.Thread")
            }
            .take(4)
            .joinToString(" <- ") { "${it.className}.${it.methodName}" }
        log(
            Log.INFO, TAG,
            "[$label] hit #$n weight=$weight style=$style -> replaced=${replacement != null}; caller: $callerFrames"
        )
    }
}
