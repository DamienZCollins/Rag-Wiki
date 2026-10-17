package com.ragwiki.wiki.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragwiki.wiki.schema.WikiPage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WikiPageMapper extends BaseMapper<WikiPage> {
}
