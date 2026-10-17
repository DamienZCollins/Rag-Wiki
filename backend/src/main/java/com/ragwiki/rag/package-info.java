/**
 * RAG 知识库模块。
 * 职责：文档切分、索引同步、BM25 与向量双路召回、RRF 融合、引用溯源与拒答。
 * 对外协作：检索能力通过 api 包暴露给 agent，索引源按文件路径读取 wiki/pages。
 */
package com.ragwiki.rag;
