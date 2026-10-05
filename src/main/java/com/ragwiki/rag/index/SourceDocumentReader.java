package com.ragwiki.rag.index;

import java.util.List;

/**
 * 索引源读取器。按 rag.index.source 选一个实现：
 * doc 读 corpus/policies，wiki 读 wiki/pages。
 */
public interface SourceDocumentReader {

    /** 索引源标识，与 rag.index.source 取值一致 */
    String source();

    List<SourceDocument> read();
}
