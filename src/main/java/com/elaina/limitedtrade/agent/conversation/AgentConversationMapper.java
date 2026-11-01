package com.elaina.limitedtrade.agent.conversation;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentConversationMapper {
    int deleteExpired(@Param("userId") long userId, @Param("now") LocalDateTime now);
    int countOwned(long userId);
    void insert(AgentConversation conversation);
    AgentConversation lockOwned(@Param("id") String id, @Param("userId") long userId);
    void claim(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);
    void release(@Param("id") String id, @Param("token") String token);
    void advance(@Param("id") String id, @Param("expiry") LocalDateTime expiry);
    List<String> findTurns(String id);
    void deleteTurns(String id);
    void insertTurn(@Param("id") String id, @Param("number") int number, @Param("json") String json);
}
