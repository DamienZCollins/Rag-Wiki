package com.ragwiki.rag.index;

import com.ragwiki.rag.RagProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 按 rag.index.source 装配索引源读取器。
 * doc 读原始政策文档，wiki 读 Wiki 页面；两者写的是不同的物理索引。
 */
@Configuration
public class IndexSourceConfig {

    @Bean
    public SourceDocumentReader sourceDocumentReader(
            RagProperties properties,
            @Value("${ragwiki.corpus.policy-dir:corpus/policies}") String policyDir,
            @Value("${wiki.page-dir:wiki/pages}") String pageDir) {

        String source = properties.index().source();
        return switch (source) {
            case CorpusPolicyReader.SOURCE -> new CorpusPolicyReader(Path.of(policyDir));
            case WikiPageReader.SOURCE -> new WikiPageReader(Path.of(pageDir));
            default -> throw new IllegalStateException(
                    "未知的 rag.index.source：%s，只能是 doc 或 wiki".formatted(source));
        };
    }
}
