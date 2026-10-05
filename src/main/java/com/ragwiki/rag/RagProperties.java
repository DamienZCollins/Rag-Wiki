package com.ragwiki.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 检索配置，对应 config/application.yml 的 rag.*
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Index index,
        Chunk chunk,
        Rrf rrf,
        @DefaultValue("50") Integer candidateWindow,
        @DefaultValue("8") Integer topK,
        Refuse refuse) {

    public record Index(
            @DefaultValue("knowledge_chunks") String alias,
            @DefaultValue("doc") String source,
            @DefaultValue("v1") String version,
            @DefaultValue("cjk") String analyzer) {

        /** 物理索引名。改索引源或改 mapping 时换一个名字，靠改别名指向切换，不动代码 */
        public String physicalIndexName() {
            return "knowledge_" + source + "_" + version;
        }
    }

    /**
     * 切分参数。min-chars 没有做：标题路径变化就断片，短小节本身就是合法片段，
     * 再设一个最小长度只会把相邻小节糊在一起，反而让引用对不准。
     */
    public record Chunk(
            @DefaultValue("600") Integer maxChars,
            @DefaultValue("80") Integer overlapChars) {
    }

    public record Rrf(@DefaultValue("60") Integer k) {
    }

    /** 拒答阈值，按单路原始分判定，都是在语料上实测出来的 */
    public record Refuse(
            @DefaultValue("6.0") Double bm25Threshold,
            @DefaultValue("0.6") Double vectorThreshold) {
    }
}
