package com.ragwiki.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragwiki.agent.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 原子获取下一个消息序号。
     * 用 COALESCE 兜住首条消息的情况，直接返回可用序号，无需先查后算。
     */
    @Select("SELECT COALESCE(MAX(seq), 0) + 1 FROM message WHERE conversation_id = #{conversationId}")
    int nextSeq(long conversationId);
}
