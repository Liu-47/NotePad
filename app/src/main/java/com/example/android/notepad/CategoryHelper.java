package com.example.android.notepad;

import android.content.Context;

/**
 * 【扩展·新增文件】笔记分类的“元数据”中心。
 *
 * <p>数据库里只存分类 id（0~4），界面需要的“名称 + 颜色 + 图标”全部在这里映射。
 * 这样做的好处：
 * <ol>
 *   <li>换主题/换配色时只改这个类，历史数据不用迁移；</li>
 *   <li>Activity 里不出现硬编码颜色值，代码更干净；</li>
 *   <li>以后要加“自定义分类”，只需要扩展这里的数组。</li>
 * </ol>
 *
 * <p>颜色全部写成 ARGB 常量而不是 colors.xml，是为了让分类色可以跟随主题
 * 动态调整（见 {@link #getLabelColor(Context, int)}）。
 */
public final class CategoryHelper {

    private CategoryHelper() {
    }

    /** 分类名称，下标就是数据库里存的分类 id */
    private static final String[] NAMES = new String[] {
            "默认", "工作", "生活", "学习", "重要"
    };

    /** 分类标签色（用于条目左侧色条、分类标签文字） */
    private static final int[] COLORS = new int[] {
            0xFF9E9E9E, // 默认 - 灰
            0xFF1E88E5, // 工作 - 蓝
            0xFF43A047, // 生活 - 绿
            0xFF8E24AA, // 学习 - 紫
            0xFFE53935  // 重要 - 红
    };

    /**
     * 取分类名称。
     *
     * @param category 数据库里的分类 id，越界时回退到“默认”
     */
    public static String getName(int category) {
        if (category < 0 || category >= NAMES.length) {
            return NAMES[NotePad.Notes.CATEGORY_DEFAULT];
        }
        return NAMES[category];
    }

    /** 取分类标签颜色 */
    public static int getColor(int category) {
        if (category < 0 || category >= COLORS.length) {
            return COLORS[NotePad.Notes.CATEGORY_DEFAULT];
        }
        return COLORS[category];
    }

    /**
     * 取用于显示的标签颜色。
     *
     * <p>“重要”分类在深色主题下红色偏暗，这里统一做一次亮度修正，
     * 保证任何主题下文字对比度都够。这是 UI 美化中比较容易被忽略的细节。
     */
    public static int getLabelColor(Context context, int category) {
        int color = getColor(category);
        if (ThemeHelper.isDarkTheme(context)) {
            // 深色主题下把颜色略微提亮，避免字看不清
            return blendWithWhite(color, 0.30f);
        }
        return color;
    }

    /** 供下拉列表使用的全部分类名称 */
    public static String[] getAllNames() {
        return NAMES.clone();
    }

    /** 把颜色向白色混合，ratio 越大越亮 */
    private static int blendWithWhite(int color, float ratio) {
        int r = (int) (((color >> 16) & 0xFF) + (255 - ((color >> 16) & 0xFF)) * ratio);
        int g = (int) (((color >> 8) & 0xFF) + (255 - ((color >> 8) & 0xFF)) * ratio);
        int b = (int) ((color & 0xFF) + (255 - (color & 0xFF)) * ratio);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
