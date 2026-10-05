package com.ragwiki.common.model;

import java.util.List;

/**
 * 向量化客户端。索引与查询必须走同一个模型，否则语义空间对不上。
 */
public interface EmbeddingClient {

    /** 向量维度，必须与 ES 索引 dense_vector 的 dims 一致 */
    int dimensions();

    float[] embed(String text);

    /** 批量向量化，顺序与入参一致 */
    List<float[]> embedBatch(List<String> texts);
}
