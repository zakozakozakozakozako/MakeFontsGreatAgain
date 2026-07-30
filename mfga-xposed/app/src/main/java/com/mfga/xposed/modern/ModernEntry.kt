package com.mfga.xposed.modern

import android.graphics.Typeface
import android.util.Log
import com.mfga.xposed.FontForceCore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

private const val TAG = "MFGA"
private const val FIREFOX_PACKAGE = "org.mozilla.firefox"
private const val GECKO_CONFIG_PATH =
    "/data/data/org.mozilla.firefox/files/mfga-geckoview-config.yaml"

class ModernEntry : XposedModule() {

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        log(Log.INFO, TAG, "MFGA v1.5 (modern) attach: " + param.packageName)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)

        val cl = param.classLoader

        // 单字体文件路径：createFromAsset / createFromFile 内部走 Typeface.Builder#build()
        runCatching {
            val builderClass = Class.forName("android.graphics.Typeface\$Builder", false, cl)
            hookAndReplace(builderClass.getDeclaredMethod("build"))
        }.onFailure { log(Log.WARN, TAG, "hook Typeface.Builder#build failed: $it") }

        // 多字重 font-family 路径（比如 res/font/inter.xml 这种声明了 regular/medium
        runCatching {
            val fallbackBuilderClass =
                Class.forName("android.graphics.Typeface\$CustomFallbackBuilder", false, cl)
            hookAndReplace(fallbackBuilderClass.getDeclaredMethod("build"))
        }.onFailure { log(Log.WARN, TAG, "hook Typeface.CustomFallbackBuilder#build failed: $it") }

        // 兜底静态工厂方法
        hookStaticFactory(cl, "createFromAsset")
        hookStaticFactory(cl, "createFromFile")

        // Typeface hook 只管壳子 UI，网页正文走 Gecko 自己的排版管线，
        // 需要另开一条路：通过 GeckoRuntimeSettings.Builder#configFilePath
        // 强制 GeckoView 读取一份我们自己写的 prefs 文件。
        if (param.packageName == FIREFOX_PACKAGE) {
            hookGeckoFontPrefs(cl)
        }
    }

    private fun hookGeckoFontPrefs(cl: ClassLoader) {
        runCatching {
            val builderClass =
                Class.forName("org.mozilla.geckoview.GeckoRuntimeSettings\$Builder", false, cl)
            val configFilePathMethod =
                builderClass.getDeclaredMethod("configFilePath", String::class.java)
            val buildMethod = builderClass.getDeclaredMethod("build")

            deoptimize(buildMethod)
            hook(buildMethod).intercept { chain ->
                runCatching { writeGeckoConfigYaml() }
                    .onFailure { log(Log.WARN, TAG, "write geckoview config failed: $it") }
                runCatching {
                    configFilePathMethod.isAccessible = true
                    configFilePathMethod.invoke(chain.thisObject, GECKO_CONFIG_PATH)
                }.onFailure { log(Log.WARN, TAG, "configFilePath invoke failed: $it") }
                chain.proceed()
            }
            log(Log.INFO, TAG, "gecko font prefs hook installed, config = $GECKO_CONFIG_PATH")
        }.onFailure { log(Log.WARN, TAG, "hook GeckoRuntimeSettings.Builder#build failed: $it") }
    }

    /**
     * GeckoView 默认只在 release build 被设为 Android "debug app" 时才会读取这份
     * config 文件；我们改成直接在 Builder#build() 前主动调用
     * configFilePath(...)，就能绕开这个限制，不需要 adb / root 去 set-debug-app。
     *
     * browser.display.use_document_fonts = 0 让 Gecko 忽略网页自己指定的
     * font-family/@font-face，强制回落到下面这些 font.name.* 指定的系统字体。
     * 按 Gecko 的 font.language.group 分组，覆盖不全就加对应的 key。
     */
    private fun writeGeckoConfigYaml() {
        val yaml = """
            prefs:
              browser.display.use_document_fonts: 0
              font.default.zh-cn: "sans-serif"
              font.name.serif.zh-cn: "Roboto"
              font.name.sans-serif.zh-cn: "Roboto"
              font.name.monospace.zh-cn: "monospace"
              font.default.zh-tw: "sans-serif"
              font.name.serif.zh-tw: "Roboto"
              font.name.sans-serif.zh-tw: "Roboto"
              font.default.zh-hk: "sans-serif"
              font.name.serif.zh-hk: "Roboto"
              font.name.sans-serif.zh-hk: "Roboto"
              font.default.x-western: "sans-serif"
              font.name.serif.x-western: "Roboto"
              font.name.sans-serif.x-western: "Roboto"
              font.name.monospace.x-western: "monospace"
        """.trimIndent()
        java.io.File(GECKO_CONFIG_PATH).writeText(yaml)
    }

    private fun hookStaticFactory(cl: ClassLoader, methodName: String) {
        runCatching {
            val typefaceClass = Class.forName("android.graphics.Typeface", false, cl)
            for (m in typefaceClass.declaredMethods) {
                if (m.name != methodName) continue
                hookAndReplace(m)
            }
        }.onFailure { log(Log.WARN, TAG, "hook Typeface.$methodName failed: $it") }
    }

    /** 统一的 hook 逻辑：deoptimize 绕过内联 + 把结果换成系统字体（保留原本 style/weight）。 */
    private fun hookAndReplace(m: java.lang.reflect.Executable) {
        deoptimize(m)
        hook(m).intercept { chain ->
            if (FontForceCore.isReplacing()) {
                return@intercept chain.proceed()
            }
            val original = chain.proceed() as? Typeface
            FontForceCore.systemReplacementFor(original)
        }
    }
}
