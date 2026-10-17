package com.ragwiki.rag.search;

import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.dto.ChunkHit;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 拒答判定。
 *
 * <p>按**单路原始分**判定：RRF 融合分只反映名次，没有绝对量纲，拿它当阈值等于拿「排第几」当「像不像」。
 * 两路各自的最高原始分都低于阈值才拒答——BM25 与余弦相似度的量纲不同，混在一起比没有意义。
 *
 * <p>两路都为空表示索引里没有可召回文档，属基础设施状态，由检索服务按错误码拦截，不在这里判定。
 */
@Component
public class RefusalJudge {

    private final RagProperties properties;

    public RefusalJudge(RagProperties properties) {
        this.properties = properties;
    }

    public boolean shouldRefuse(List<ChunkHit> bm25Hits, List<ChunkHit> vectorHits) {
        return topScore(bm25Hits) < properties.refuse().bm25Threshold()
                && topScore(vectorHits) < properties.refuse().vectorThreshold();
    }

    /** 单路最高原始分。命中按名次排好序，取第一条即可 */
    public static double topScore(List<ChunkHit> hits) {
        return hits.isEmpty() ? 0 : hits.get(0).score();
    }
}
