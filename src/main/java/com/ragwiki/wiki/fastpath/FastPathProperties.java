package com.ragwiki.wiki.fastpath;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 快通道配置，对应 config/application.yml 的 fastpath.*
 */
@ConfigurationProperties(prefix = "fastpath")
public record FastPathProperties(

        @DefaultValue("aftersale") String scene,

        /** 场景初始化结果的缓存时长 */
        @DefaultValue("300s") Duration cacheTtl,

        /** 命中阈值：置信度低于它的条目不判为命中 */
        @DefaultValue("0.8") Double minConfidence,

        /** 由 Wiki 条目派生的条目置信度 */
        @DefaultValue("0.9") Double derivedConfidence,

        /** 由慢通道流量沉淀的条目置信度。低于命中阈值，必须先绑定来源才有意义 */
        @DefaultValue("0.6") Double distilledConfidence,

        Distill distill) {

    public record Distill(
            /** 窗口内慢通道问过多少次才算高频 */
            @DefaultValue("3") Integer minHits,
            @DefaultValue("7") Integer windowDays) {
    }
}
