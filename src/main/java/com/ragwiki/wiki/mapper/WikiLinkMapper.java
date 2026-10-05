package com.ragwiki.wiki.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragwiki.wiki.schema.WikiLink;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WikiLinkMapper extends BaseMapper<WikiLink> {
}
