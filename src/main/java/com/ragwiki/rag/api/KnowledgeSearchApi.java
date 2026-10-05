package com.ragwiki.rag.api;

import java.util.List;

/**
 * 知识检索对外的接口。agent 模块只依赖这个包，不碰 rag 的内部实现。
 */
public interface KnowledgeSearchApi {

    KnowledgeSearchOutcome search(String question);

    /**
     * 检索结果。hit 为 false 表示两路分数都低于拒答阈值，调用方据此决定怎么回复。
     * citations 的顺序就是给模型和前端看的引用编号顺序。
     */
    record KnowledgeSearchOutcome(boolean hit, List<Citation> citations) {

        public static KnowledgeSearchOutcome miss() {
            return new KnowledgeSearchOutcome(false, List.of());
        }

        public record Citation(
                String chunkId,
                String docId,
                /** Wiki 条目标识，索引源是原始文档时为空 */
                String pageId,
                /** 全部来源 doc_id */
                java.util.List<String> sourceDocs,
                String title,
                String version,
                String headingPath,
                String content) {
        }
    }
}
