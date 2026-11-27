package com.elaina.limitedtrade.agent.conversation;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentConversationMapper {
    int deleteExpired(@Param("userId") long userId, @Param("now") LocalDateTime now);
    int countOwned(long userId);
    List<AgentConversationService.CreatedConversation> listActive(@Param("userId") long userId,
            @Param("now") LocalDateTime now, @Param("offset") long offset, @Param("limit") int limit);
    void deleteOwned(@Param("id") String id, @Param("userId") long userId);
    void insert(AgentConversation conversation);
    AgentConversation lockOwned(@Param("id") String id, @Param("userId") long userId);
    void claim(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);
    void release(@Param("id") String id, @Param("token") String token);
    void advance(@Param("id") String id, @Param("expiry") LocalDateTime expiry);
    List<String> findTurns(String id);
    void deleteTurns(String id);
    List<String> findVisibleTurns(String id);
    String findFirstVisibleTitle(String id);
    void insertVisibleTurn(@Param("id") String id, @Param("number") long number, @Param("json") String json,
                           @Param("title") String title);
    void trimVisibleTurns(String id);
    void deleteVisibleTurns(String id);
    void insertTurn(@Param("id") String id, @Param("number") int number, @Param("json") String json);
}
