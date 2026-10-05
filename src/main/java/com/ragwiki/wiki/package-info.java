/**
 * LLM Wiki 模块。
 * 职责：源文档编译、条目归并、双链生成、增量编译、失效检测、lint，以及快通道条目的派生与命中判定。
 * 对外协作：编译产物 wiki/pages/*.md 是 rag 的索引源契约；快通道能力通过 api 包暴露给 agent。
 */
package com.ragwiki.wiki;
