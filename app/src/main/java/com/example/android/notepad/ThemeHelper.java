package com.example.android.notepad;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * 【扩展·新增文件】主题管理。
 *
 * <p>本项目的“UI 美化”按“轻改”要求实现：不引入自定义控件库、不做复杂皮肤，
 * 只提供 4 套配色让用户切换，并统一控制列表背景、条目背景、文字主色、
 * 强调色（顶栏/按钮）这几处关键颜色。
 *
 * <p>设计要点：
 * <ul>
 *   <li>主题偏好存在 SharedPreferences，退出应用后仍保留；</li>
 *   <li>所有颜色由一个 {@link Palette} 对象统一产出，避免 Activity 里散落色值；</li>
 *   <li>同时提供深色/浅色两套基底，保证夜间使用不刺眼。</li>
 * </ul>
 */
public final class ThemeHelper {

    /** SharedPreferences 文件名 */
    public static final String PREF_NAME = "notepad_settings";

    /** 主题键 */
    public static final String KEY_THEME = "pref_theme";

    /** 搜索时是否同时匹配正文 */
    public static final String KEY_SEARCH_CONTENT = "pref_search_content";

    /** 主题：浅色·蓝（默认） */
    public static final int THEME_LIGHT_BLUE = 0;
    /** 主题：浅色·纸黄 */
    public static final int THEME_LIGHT_PAPER = 1;
    /** 主题：深色 */
    public static final int THEME_DARK = 2;
    /** 主题：浅色·绿 */
    public static final int THEME_LIGHT_GREEN = 3;

    /** 主题名称，供设置对话框展示 */
    public static final String[] THEME_NAMES = new String[] {
            "浅色 · 蓝", "浅色 · 纸黄", "深色", "浅色 · 绿"
    };

    private ThemeHelper() {
    }

    /** 读取当前主题 id，未设置时返回默认主题 */
    public static int getTheme(Context context) {
        return prefs(context).getInt(KEY_THEME, THEME_LIGHT_BLUE);
    }

    /**
     * 保存主题。
     *
     * 【必须用 commit() 而不是 apply()】
     * 切换主题后紧接着就会调用 {@link android.app.Activity#recreate()}，
     * recreate() 会在这个进程里马上重建 Activity 并重新读一次 SharedPreferences。
     * apply() 是"先改内存、异步写盘"，本身能读到新值，但如果刚好遇到
     * recreate + 进程内 SharedPreferences 缓存刷新时序问题，就可能读到旧值，
     * 表现为"选完主题没反应"。主题是一个小整数，同步写盘的开销可以忽略，
     * 所以这里选 commit() 换取确定性。
     *
     * @return true 表示写入成功
     */
    public static boolean setTheme(Context context, int themeId) {
        return prefs(context).edit().putInt(KEY_THEME, themeId).commit();
    }

    /** 当前是否深色基底 */
    public static boolean isDarkTheme(Context context) {
        return getTheme(context) == THEME_DARK;
    }

    /** 读取“搜索时是否匹配正文”，默认 true */
    public static boolean isSearchContentEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SEARCH_CONTENT, true);
    }

    /** 保存“搜索时是否匹配正文” */
    public static void setSearchContentEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SEARCH_CONTENT, enabled).apply();
    }

    /** 取得当前主题的配色方案 */
    public static Palette getPalette(Context context) {
        return Palette.of(getTheme(context));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 一套主题的全部颜色。
     *
     * <p>用不可变对象而不是一堆静态常量，是为了让“取当前主题颜色”这件事
     * 只调用一次（getPalette），而不是每个控件都去读 SharedPreferences。
     */
    public static final class Palette {

        /** 页面/列表背景 */
        public final int background;
        /** 条目卡片背景 */
        public final int itemBackground;
        /** 主文字颜色（标题） */
        public final int textPrimary;
        /** 次要文字颜色（时间戳、摘要） */
        public final int textSecondary;
        /** 强调色（顶栏、按钮、选中态） */
        public final int accent;
        /** 强调色上的文字颜色 */
        public final int onAccent;
        /** 分割线颜色 */
        public final int divider;
        /** 搜索栏背景 */
        public final int searchBar;
        /**
         * 顶栏背景。
         *
         * <p>单独拆出来而不是直接用 accent：浅色主题下顶栏就想要那个较深的强调色，
         * 但深色主题的 accent 是"浅蓝"（Material 深色规范里强调色要够亮才看得清），
         * 拿它当整条顶栏底色会变成"浅蓝大色块压在纯黑页面上"，非常刺眼。
         */
        public final int topBar;
        /** 顶栏上的文字/图标颜色 */
        public final int onTopBar;

        private Palette(int background, int itemBackground, int textPrimary, int textSecondary,
                        int accent, int onAccent, int divider, int searchBar,
                        int topBar, int onTopBar) {
            this.background = background;
            this.itemBackground = itemBackground;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.accent = accent;
            this.onAccent = onAccent;
            this.divider = divider;
            this.searchBar = searchBar;
            this.topBar = topBar;
            this.onTopBar = onTopBar;
        }

        static Palette of(int themeId) {
            switch (themeId) {
                case THEME_LIGHT_PAPER:
                    // 纸黄主题：模拟纸质笔记本，降低蓝光刺激
                    return new Palette(
                            0xFFF7F1E3, // background
                            0xFFFFFBF2, // itemBackground
                            0xFF3E3A33, // textPrimary
                            0xFF8A8175, // textSecondary
                            0xFFB4791E, // accent
                            0xFFFFFFFF, // onAccent
                            0x22000000, // divider
                            0xFFFFFFFF, // searchBar
                            0xFF8A5A12, // topBar
                            0xFFFFFFFF  // onTopBar
                    );
                case THEME_DARK:
                    return new Palette(
                            0xFF121212,
                            0xFF1E1F22,
                            0xFFEDEDED,
                            0xFF9AA0A6,
                            0xFF8AB4F8,
                            0xFF10233F,
                            0x33FFFFFF,
                            0xFF26282C,
                            0xFF1A1B1E, // topBar：比页面略亮一点，形成层次
                            0xFFEDEDED  // onTopBar
                    );
                case THEME_LIGHT_GREEN:
                    return new Palette(
                            0xFFF1F8F4,
                            0xFFFFFFFF,
                            0xFF20312A,
                            0xFF6E8578,
                            0xFF2E7D5B,
                            0xFFFFFFFF,
                            0x1F000000,
                            0xFFFFFFFF,
                            0xFF23614A,
                            0xFFFFFFFF
                    );
                case THEME_LIGHT_BLUE:
                default:
                    return new Palette(
                            0xFFF2F4F8,
                            0xFFFFFFFF,
                            0xFF1F2933,
                            0xFF7B8794,
                            0xFF2F6FED,
                            0xFFFFFFFF,
                            0x1F000000,
                            0xFFFFFFFF,
                            0xFF2F6FED, // topBar
                            0xFFFFFFFF  // onTopBar
                    );
            }
        }

        /** 把次要文字色转换成带透明度的版本，用于摘要文字 */
        public int secondaryWithAlpha(int alpha) {
            return Color.argb(alpha, Color.red(textSecondary), Color.green(textSecondary),
                    Color.blue(textSecondary));
        }
    }
}
