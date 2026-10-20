package com.ragwiki.common.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间转换统一按 Asia/Shanghai，跨层用 Instant，落库与展示用本地时间。
 */
class TimesTest {

    @Test
    void convertsInstantToShanghaiWallTime() {
        // UTC 2025-09-01 02:00 是北京时间当天 10:00
        Instant instant = Instant.parse("2025-09-01T02:00:00Z");

        assertThat(Times.toLocal(instant)).isEqualTo(LocalDateTime.of(2025, 9, 1, 10, 0));
        assertThat(Times.format(instant)).isEqualTo("2025-09-01 10:00");
    }

    @Test
    void convertsLocalBackToInstant() {
        LocalDateTime local = LocalDateTime.of(2025, 9, 1, 10, 0);

        assertThat(Times.toInstant(local)).isEqualTo(Instant.parse("2025-09-01T02:00:00Z"));
    }

    @Test
    void nullsStayNullAndFormatReturnsEmpty() {
        assertThat(Times.toLocal(null)).isNull();
        assertThat(Times.toInstant(null)).isNull();
        assertThat(Times.format(null)).isEmpty();
    }
}
