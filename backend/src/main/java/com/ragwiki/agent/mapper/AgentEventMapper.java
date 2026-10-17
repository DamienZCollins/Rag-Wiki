package com.ragwiki.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragwiki.agent.entity.AgentEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEvent> {

    /**
     * 取下一个可用的事件序号：当前最大值 + 1，COALESCE 兜住首条。
     * 这里不加锁，同一会话的并发回合由 SessionTurnLock 挡在入口。
     */
    @Select("SELECT COALESCE(MAX(seq), 0) + 1 FROM agent_event WHERE conversation_id = #{conversationId}")
    int nextSeq(long conversationId);
}
