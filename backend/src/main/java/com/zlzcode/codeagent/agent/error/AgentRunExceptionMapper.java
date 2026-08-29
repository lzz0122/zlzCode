package com.zlzcode.codeagent.agent.error;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.workspace.exception.WorkspaceRegistryException;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

@Component
public final class AgentRunExceptionMapper {

    /*
     * 背景：SSE 已开始输出后，普通 HTTP 异常处理器无法再改变响应，只能发送 Agent 错误事件。
     * 设计意图：集中定义运行边界允许公开的异常映射，避免 Agent 服务重复判断异常类型。
     * 关键约束：未知异常必须原样传播；将程序缺陷伪装成 INTERNAL_ERROR 会阻断日志、监控和测试发现问题。
     */
    public Mono<AgentEvent.Error> mapException(Throwable exception) {
        if (exception instanceof OpenAiIntegrationException openAiException) {
            return Mono.just(new AgentEvent.Error(
                    openAiException.safeMessage(),
                    openAiException.code(),
                    openAiException.retryable()));
        }
        if (exception instanceof WorkspaceRegistryException workspaceException) {
            return Mono.just(new AgentEvent.Error(
                    workspaceException.getMessage(),
                    workspaceException.code(),
                    workspaceException.retryable()));
        }
        return Mono.error(exception);
    }
}
