package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class AgentSseService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AgentSseService.class);
    private final AgentConversationService conversations;
    private final AgentProperties properties;
    private final ObjectMapper json;
    private final Semaphore capacity = new Semaphore(8);
    private final ExecutorService executor = new ThreadPoolExecutor(0, 8, 60, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofVirtual().name("agent-sse-", 0).factory());

    public AgentSseService(AgentConversationService conversations, AgentProperties properties, ObjectMapper json) {
        this.conversations = conversations;
        this.properties = properties;
        this.json = json;
    }

    public SseEmitter open(long userId, String id, String message) {
        if (!capacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "流式请求已达并发上限");
        AgentConversationService.PreparedTurn prepared;
        try { prepared = conversations.prepare(userId, id, message); }
        catch (RuntimeException error) { capacity.release(); throw error; }
        var emitter = new SseEmitter(properties.requestTimeout().plusSeconds(5).toMillis());
        var stream = new AgentStream(event -> send(emitter, event.type(), Map.of("round", event.round(), "text", event.text())));
        emitter.onCompletion(stream::cancel);
        emitter.onError(error -> stream.cancel());
        emitter.onTimeout(() -> {
            stream.cancel();
            terminalError(emitter, 504, "流式请求超时");
        });
        try {
            executor.execute(() -> execute(prepared, emitter, stream));
        } catch (RejectedExecutionException error) {
            capacity.release();
            conversations.release(prepared);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "流式执行器暂不可用");
        }
        return emitter;
    }

    private void execute(AgentConversationService.PreparedTurn prepared, SseEmitter emitter, AgentStream stream) {
        boolean entered = false;
        try {
            stream.check();
            send(emitter, "start", Map.of());
            entered = true;
            var result = conversations.stream(prepared, stream);
            stream.check();
            send(emitter, "done", result);
            emitter.complete();
        } catch (RuntimeException error) {
            if (!entered) {
                try { conversations.release(prepared); }
                catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            }
            if (!stream.cancelled()) {
                int code = error instanceof ResponseStatusException status ? status.getStatusCode().value() : 500;
                String message = error instanceof ResponseStatusException status ? status.getReason() : "流式请求执行失败";
                terminalError(emitter, code, message);
            }
            log.atInfo().addKeyValue("event", "agent_stream_end").addKeyValue("cancelled", stream.cancelled())
                    .addKeyValue("errorType", error.getClass().getSimpleName()).log("流式请求结束");
        } finally {
            stream.cancel();
            capacity.release();
        }
    }

    private void send(SseEmitter emitter, String name, Object data) {
        String encoded;
        try { encoded = json.writeValueAsString(data); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("SSE事件序列化失败", error);
        }
        try {
            // 先编码JSON，保证正文换行不会变成新的SSE字段。
            emitter.send(SseEmitter.event().name(name).data(encoded, MediaType.APPLICATION_JSON));
        } catch (IOException error) {
            var failure = new CancellationException("流式连接写入失败");
            failure.initCause(error);
            throw failure;
        }
    }

    private void terminalError(SseEmitter emitter, int code, String message) {
        try { send(emitter, "error", Map.of("code", code, "message", message)); }
        catch (RuntimeException error) {
            // 网络边界已无法传递终止事件，保留类型诊断，不回显问题或上游正文。
            log.atDebug().addKeyValue("errorType", error.getClass().getSimpleName()).log("流式终止事件无法发送");
        } finally { emitter.complete(); }
    }

    @Override @PreDestroy public void close() { executor.shutdownNow(); }
}
