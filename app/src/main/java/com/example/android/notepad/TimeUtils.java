package com.example.android.notepad;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 【扩展·新增文件】笔记时间戳格式化工具。
 *
 * <p>为什么单独抽一个类？
 * 时间戳在三个地方都要用到：列表条目、编辑器标题栏、搜索结果的“最后修改”提示。
 * 如果每处都写一遍 SimpleDateFormat，格式容易不一致，而且 SimpleDateFormat
 * 不是线程安全的，反复 new 也浪费。集中在这里便于统一修改格式。
 *
 * <p>显示规则（借鉴主流记事本应用的相对时间写法）：
 * <ul>
 *   <li>今天 —— “14:35”</li>
 *   <li>昨天 —— “昨天 14:35”</li>
 *   <li>今年内 —— “3月5日 14:35”</li>
 *   <li>更早 —— “2024年3月5日”</li>
 * </ul>
 * 这样既比裸毫秒可读，又比固定 "yyyy-MM-dd HH:mm" 更有信息量。
 */
public final class TimeUtils {

    private TimeUtils() {
    }

    /**
     * 把毫秒时间戳格式化成适合列表展示的字符串。
     *
     * @param millis System.currentTimeMillis() 形式的时间戳
     * @return 格式化后的时间文本；如果时间戳非法（<=0）返回空串
     */
    public static String formatFriendlyTime(long millis) {
        if (millis <= 0) {
            return "";
        }
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(millis);

        Calendar now = Calendar.getInstance();

        // 同一天 -> 只显示时分
        if (isSameDay(target, now)) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
        }

        // 昨天 -> “昨天 HH:mm”
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(target, yesterday)) {
            return "昨天 " + new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(millis));
        }

        // 同一年 -> “M月d日 HH:mm”
        if (target.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            return new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                    .format(new Date(millis));
        }

        // 跨年 -> “yyyy年M月d日”
        return new SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(new Date(millis));
    }

    /**
     * 完整格式，用于编辑器里展示“创建于 / 修改于”详情。
     */
    public static String formatFullTime(long millis) {
        if (millis <= 0) {
            return "未知";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(millis));
    }

    private static boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
