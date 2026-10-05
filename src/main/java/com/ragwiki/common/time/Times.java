package com.ragwiki.common.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间转换。跨层传递统一用 Instant，落库与展示统一按 Asia/Shanghai。
 */
public final class Times {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Times() {
    }

    public static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZONE);
    }

    public static Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atZone(ZONE).toInstant();
    }

    public static String format(Instant instant) {
        return instant == null ? "" : DATE_TIME.format(toLocal(instant));
    }
}
