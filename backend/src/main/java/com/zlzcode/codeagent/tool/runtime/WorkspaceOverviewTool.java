package com.zlzcode.codeagent.tool.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.model.ToolDecision;
import com.zlzcode.codeagent.tool.WorkspaceOverviewService;
import com.zlzcode.codeagent.tool.definition.WorkspaceOverviewToolDefinition;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

/**
 * 工作区概览工具的运行时边界，负责校验调用并调度底层扫描。
 */
@Component
public final class WorkspaceOverviewTool {

    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(2);

    private final ObjectMapper objectMapper;
    private final WorkspaceOverviewToolDefinition definition;
    private final WorkspaceOverviewService overviewService;

    public WorkspaceOverviewTool(
            ObjectMapper objectMapper,
            WorkspaceOverviewToolDefinition definition,
            WorkspaceOverviewService overviewService) {
        this.objectMapper = objectMapper;
        this.definition = definition;
        this.overviewService = overviewService;
    }

    public String displayName() {
        return definition.displayName();
    }

    /*
     * 背景：Agent 收到的工具调用来自模型，名称和参数都可能偏离已声明的工具契约。
     * 设计意图：在工具运行时一次性完成识别、校验、调度和结果收口，让 Agent 只编排事件顺序。
     * 关键约束：阻塞扫描必须运行在 boundedElastic，并保留两秒超时；否则模型请求会占用 WebFlux 事件线程。
     */
    public Mono<Result> execute(AuthorizedWorkspace workspace, ToolDecision.ToolCall call) {
        if (!definition.name().equals(call.name())) {
            return Mono.just(failure("TOOL_NOT_AVAILABLE", "未执行未知工具"));
        }
        if (!definition.acceptsArguments(call.arguments())) {
            return Mono.just(failure("TOOL_ARGUMENTS_INVALID", "工具参数无效，未执行工作区访问"));
        }

        return Mono.fromCallable(() -> overviewService.list(workspace.root()))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(EXECUTION_TIMEOUT)
                .map(result -> new Result(result.ok(), result.modelContent(), result.presentation()))
                .onErrorReturn(failure("TOOL_TIMEOUT", "读取工作区超时"));
    }

    private Result failure(String code, String presentation) {
        try {
            String content = objectMapper.writeValueAsString(
                    Map.of("ok", false, "error", Map.of(
                            "code", code,
                            "message", "The selected workspace could not be listed safely.")));
            return new Result(false, content, presentation);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码工作区工具结果", exception);
        }
    }

    public record Result(boolean ok, String modelContent, String presentation) {
    }
}
