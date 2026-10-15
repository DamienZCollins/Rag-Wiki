package com.ragwiki.rag.api;

import java.util.List;

/**
 * 检索探针：不经模型，直接把一个问题打进检索链路，拿到召回片段与两路原始分。
 *
 * <p>评测的召回层与排序层归因用它，而不是看模型有没有调工具：
 * 模型调没调工具是生成层的行为，拿它判召回会把两层的问题混在一起。
 *
 * <p>走的是与在线问答同一条检索路径（都经别名），所以对照实验切换索引源时，
 * 探针与生成测到的是同一个索引。
 */
public interface KnowledgeProbeApi {

    ProbeResult probe(String question);

    /** 一次检索的完整结果，含拒答判定与两路原始分 */
    record ProbeResult(
            boolean refused,
            double topBm25Score,
            double topVectorScore,
            /** 候选窗口大小，也就是融合后参与排序的片段总数 */
            int candidateWindow,
            /** 生效位次上限，超过它就不进生成层。召回与排序的归因边界 */
            int topK,
            /** 按 RRF 融合排序的片段，按位次从前往后，含窗口内全部而不只是 topK */
            List<ProbeChunk> chunks) {

        /**
         * 期望来源是否出现在前 k 位。
         *
         * <p>一个片段算命中，当且仅当：片段的 title 等于期望条目标题，
         * 或片段的 docId / sourceDocs 与期望文档有交集。
         * 前一条对 Wiki 索引精确，后一条对原始文档索引成立，
         * 两种索引源共用同一套期望标注，才谈得上对比。
         */
        public boolean hitWithin(List<String> titles, List<String> docIds, int k) {
            int limit = Math.min(k, chunks.size());
            for (int i = 0; i < limit; i++) {
                if (matches(chunks.get(i), titles, docIds)) {
                    return true;
                }
            }
            return false;
        }

        /** 期望来源的最优名次，从 1 开始；未召回到返回 0 */
        public int bestRank(List<String> titles, List<String> docIds) {
            for (int i = 0; i < chunks.size(); i++) {
                if (matches(chunks.get(i), titles, docIds)) {
                    return i + 1;
                }
            }
            return 0;
        }

        private static boolean matches(ProbeChunk chunk, List<String> titles, List<String> docIds) {
            if (titles.contains(chunk.title())) {
                return true;
            }
            if (docIds.contains(chunk.docId())) {
                return true;
            }
            for (String source : chunk.sourceDocs()) {
                if (docIds.contains(source)) {
                    return true;
                }
            }
            return false;
        }
    }

    record ProbeChunk(
            String chunkId,
            String docId,
            /** Wiki 条目标识，索引源是原始文档时为空 */
            String pageId,
            List<String> sourceDocs,
            String title,
            String headingPath,
            String content,
            Double bm25Score,
            Double vectorScore,
            double rrfScore) {
    }
}
