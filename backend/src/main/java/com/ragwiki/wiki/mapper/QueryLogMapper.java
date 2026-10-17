package com.ragwiki.wiki.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragwiki.wiki.schema.QueryLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QueryLogMapper extends BaseMapper<QueryLog> {
}
