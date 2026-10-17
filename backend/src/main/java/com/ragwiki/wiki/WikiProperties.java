package com.ragwiki.wiki;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Wiki 编译配置，对应 config/application.yml 的 wiki.*
 */
@ConfigurationProperties(prefix = "wiki")
public record WikiProperties(

        /** 编译产物目录，相对仓库根 */
        @DefaultValue("wiki/pages") String pageDir,

        /** 矛盾检测时，按向量相似度筛候选条目对 */
        @DefaultValue("0.7") Double lintSimilarityThreshold,

        /** 矛盾检测最多送多少对候选给模型判定，避免提示词失控 */
        @DefaultValue("20") Integer lintMaxPairs) {
}
