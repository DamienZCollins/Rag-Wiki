package com.ragwiki.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragwiki.agent.entity.AgentEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEvent> {
}
