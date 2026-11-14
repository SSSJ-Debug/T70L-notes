package com.xylink.timewidget;

/**
 * 农历/干支算法 —— 从原厂 com.xylink.timewidget.Lunar 逐字节还原。
 * 覆盖 2020-2040 年（NYMD 表 21 年）。已验证 2026-11-02 = 丙午年农历九月廿四。
 */
public final class Lunar {
    static final int[] NYMD = {
        125, 212, 201, 122, 210, 129, 217, 206, 126, 213, 203, 123,
        211, 131, 219, 208, 128, 215, 204, 124, 212
    };
    static final int[] BITS = {
        3733, 3412, 5546, 2485, 2412, 5294, 5276, 6732, 7462, 6822, 2900,
        3434, 4826, 2397, 2394, 5274, 6731, 6730, 6820, 6996, 5812
    };
    static final int[] LEAP = {
        4, 0, 0, 2, 0, 6, 0, 0, 5, 0, 0, 3, 0, 11, 0, 0, 6, 0, 0, 5, 0
    };
    private static final String[] MONTH_NAME = {
        "", "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    };
    private static final String[] DAY_NAME = {
        "", "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    };
    private static final String[] GAN = { "甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸" };
    private static final String[] ZHI = { "子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥" };

    private Lunar() {}

    private static int daysFromCivil(int y, int m, int d) {
        y -= (m <= 2) ? 1 : 0;
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    public static String ganzhi(int year) {
        int g = year - 4;
        int gi = ((g % 10) + 10) % 10;
        int zi = ((g % 12) + 12) % 12;
        return GAN[gi] + ZHI[zi];
    }

    public static String text(int y, int m, int d) {
        int days = daysFromCivil(y, m, d);
        int idx = -1;
        for (int i = 0; i < NYMD.length; i++) {
            int ny = daysFromCivil(2020 + i, NYMD[i] / 100, NYMD[i] % 100);
            if (days >= ny) idx = i;
        }
        if (idx < 0 || idx >= BITS.length) return "";
        String yearStr = ganzhi(2020 + idx) + "年";
        int off = days - daysFromCivil(2020 + idx, NYMD[idx] / 100, NYMD[idx] % 100);
        int bits = BITS[idx];
        int leap = LEAP[idx];
        int mth = 0;
        int carry = 0;
        for (int mm = 0; mm < 12; mm++) {
            int len = (((bits >> (12 - mth)) & 1) == 1 ? 30 : 29) + carry;
            if (off < len) {
                return yearStr + "农历" + MONTH_NAME[mm + 1] + DAY_NAME[off - carry + 1];
            }
            mth++;
            if (leap == mm + 1) {
                int llen = (((bits >> (12 - mth)) & 1) == 1 ? 30 : 29) + len;
                if (off < llen) {
                    return yearStr + "农历闰" + MONTH_NAME[mm + 1] + DAY_NAME[off - len + 1];
                }
                mth++;
                carry = len;
            } else {
                carry = len;
            }
        }
        return "";
    }
}
