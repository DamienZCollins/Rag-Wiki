package com.ragwiki.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragwiki.agent.entity.AgentEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEvent> {

    /**
     * 原子获取下一个事件序号。
     * 用 COALESCE 兜住首条事件的情况，直接返回可用序号，无需先查后算。
     */
    @Select("SELECT COALESCE(MAX(seq), 0) + 1 FROM agent_event WHERE conversation_id = #{conversationId}")
    int nextSeq(long conversationId);
}
