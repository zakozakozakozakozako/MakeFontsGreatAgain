package com.mfga.xposed;

import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;

/**
 * legacy / modern 两套入口共用的核心逻辑。
 *
 * 思路：不管目标 App 是通过 XML font-family(如 res/font/inter.xml)、
 * Compose 的 Font(R.font.xxx)，还是硬编码 createFromAsset/createFromFile
 * 加载字体，最终在 Android 12+ 上都会落到 android.graphics.Typeface 的
 * 几个静态工厂方法 / Typeface.Builder#build()。
 *
 * 因此只 hook 这一层：不管原本要生成什么字体，都换成系统默认字体，
 * 但保留原字体计算出来的 style/weight/italic,这样粗体、斜体语义不丢。
 *
 * 注意：Typeface.create(...) 内部在部分 Android 版本上也可能间接
 * 走回 Builder，为避免无限递归，用 ThreadLocal 做重入保护。
 *
 */
public final class FontForceCore {

    private static final String TAG = "MFGA";

    private static final ThreadLocal<Boolean> IN_REPLACEMENT =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private FontForceCore() {
    }

    /** 是否正处于"生成替换字体"的过程中，用来防止 hook 自我递归。 */
    public static boolean isReplacing() {
        return Boolean.TRUE.equals(IN_REPLACEMENT.get());
    }

    /**
     * 给定原本要被创建出来的自定义字体(可能为 null)，
     * 返回一个样式相同、但字形来自系统默认字体的 Typeface。
     */
    public static Typeface systemReplacementFor(Typeface original) {
        IN_REPLACEMENT.set(Boolean.TRUE);
        try {
            int style = original != null ? original.getStyle() : Typeface.NORMAL;
            boolean italic = isItalicSafe(original);
            int weight = resolveIntendedWeight(original);

            if (weight > 0) {
                Typeface bucketed = bucketedFamilyReplacement(weight, italic, style);
                if (bucketed != null) {
                    return bucketed;
                }
            }
            return Typeface.create(Typeface.DEFAULT, style);
        } catch (Throwable t) {
            // 任何异常都回退到最基础的系统字体，绝不能让 App 崩溃
            return Typeface.DEFAULT;
        } finally {
            IN_REPLACEMENT.set(Boolean.FALSE);
        }
    }

    /**
     * 读取真实的 weight。getWeight() 是 API 28(P)才加入的公开方法，
     * 更早的系统上直接跳过，交给调用方走 style 兜底。
     */
    private static int resolveIntendedWeight(Typeface original) {
        if (original == null) {
            return -1;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                int weight = original.getWeight();
                if (weight > 0) {
                    return weight;
                }
            } catch (Throwable t) {
                Log.w(TAG, "getWeight failed", t);
            }
        }
        return -1;
    }

    private static boolean isItalicSafe(Typeface original) {
        if (original == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return original.isItalic();
            } catch (Throwable ignored) {
                // fall through to style-based check below
            }
        }
        return (original.getStyle() & Typeface.ITALIC) != 0;
    }

    /**
     * 把任意 weight 量化到系统里"确定存在静态字重文件、不会触发
     * 伪粗体合成"的几档字重，而不是直接把 weight 整数塞给
     * Typeface.create(family, weight, italic)。
     *
     * 档位对齐真实的常见设计字重，而不是随手拍的数字：
     *   [0, 450)   -> "sans-serif"        常规 (~400)
     *   [450, 550) -> "sans-serif-medium" 中等 (~500)
     *   [550, 850) -> Typeface.BOLD style 粗体 (~700)——用 style 而不是
     *                 命名 family，因为几乎所有系统字体都把"粗体"实现成
     *                 一个真实的静态字重文件、通过 style 位而不是 family
     *                 名字去选，兼容性比 "sans-serif-medium"/"-black" 这类
     *                 命名 family(不是所有 OEM 字体配置都声明)要好。
     *   [850, +∞)  -> "sans-serif-black"  特黑 (~900)
     *
     * 之前的版本把 700(最常见的 bold 请求)和 900(black)都落进了同一个
     * ">=650 -> sans-serif-black" 档，导致本该有明显粗细区别的两种文字
     * 被拍成了一样重，这正是"奇怪字重"没消失的直接原因。
     */
    private static Typeface bucketedFamilyReplacement(int weight, boolean italic, int style) {
        int wantStyle = italic ? Typeface.ITALIC : Typeface.NORMAL;

        if (weight >= 550 && weight < 850) {
            // 700 附近（最常见的 "bold" 请求）：用 BOLD style 而不是命名
            // family，走系统字体真实的粗体静态文件，不经过伪粗体合成。
            int boldStyle = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
            try {
                return Typeface.create(Typeface.DEFAULT, boldStyle);
            } catch (Throwable t) {
                Log.w(TAG, "create(DEFAULT, BOLD) failed", t);
            }
        } else {
            String familyName = weight >= 850 ? "sans-serif-black"
                    : weight >= 450 ? "sans-serif-medium"
                    : "sans-serif";
            try {
                Typeface bucketed = Typeface.create(familyName, wantStyle);
                if (bucketed != null) {
                    return bucketed;
                }
            } catch (Throwable t) {
                Log.w(TAG, "create(familyName=" + familyName + ") failed", t);
            }
        }
        // 命名字重在这台设备/这个 App 进程里不可用，退回最基础的
        // style 兜底，绝不使用 Typeface.create(family, weight, italic)
        // 的任意 weight 合成，避免伪粗体。
        try {
            return Typeface.create(Typeface.DEFAULT, style);
        } catch (Throwable t) {
            return Typeface.DEFAULT;
        }
    }
}
