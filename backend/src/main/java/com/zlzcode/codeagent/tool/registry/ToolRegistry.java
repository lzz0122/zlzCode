package com.zlzcode.codeagent.tool.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import com.zlzcode.codeagent.tool.handler.MutationToolHandler;
import com.zlzcode.codeagent.tool.handler.ToolHandler;
import com.zlzcode.codeagent.tool.model.ApprovalRequired;
import com.zlzcode.codeagent.tool.model.MutationPlan;
import com.zlzcode.codeagent.tool.model.ToolCompleted;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolExecutionResult;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Component
public final class ToolRegistry {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final ObjectMapper objectMapper;
    private final Map<String, RegisteredTool> tools;
    private final List<LlmRequest.ToolDeclaration> declarations;

    /*
     * 背景：多工具配对的注入顺序不固定，禁用项中的错误也不能等到启用或请求模型时才暴露。
     * 设计意图：启动时校验全部配对，再从同一注册表按名称导出声明，避免运行期间各自组装造成分歧。
     * 关键约束：重名和声明校验必须包含禁用项；先过滤会隐藏配置错误，静默覆盖则会改变实际调用的 Handler。
     */
    public ToolRegistry(
            ObjectMapper objectMapper,
            List<ToolRegistration> registrations) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "Tool registry ObjectMapper cannot be null");
        Objects.requireNonNull(registrations, "Tool registrations cannot be null");

        Map<String, RegisteredTool> registeredTools = new TreeMap<>();
        for (ToolRegistration registration : registrations) {
            Objects.requireNonNull(registration, "Tool registration cannot be null");
            String name = registration.definition().name();
            if (name == null || !TOOL_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException("Tool name must match [A-Za-z0-9_-]{1,64}");
            }
            RegisteredTool tool = new RegisteredTool(
                    registration.definition(), registration.handler(), registration.available());
            if (registeredTools.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + name);
            }
        }

        List<LlmRequest.ToolDeclaration> availableDeclarations = new ArrayList<>();
        for (Map.Entry<String, RegisteredTool> entry : registeredTools.entrySet()) {
            RegisteredTool tool = entry.getValue();
            LlmRequest.ToolDeclaration declaration = new LlmRequest.ToolDeclaration(
                    entry.getKey(), tool.definition().description(), tool.definition().parametersSchema());
            if (tool.available()) {
                availableDeclarations.add(declaration);
            }
        }
        this.tools = Map.copyOf(registeredTools);
        this.declarations = List.copyOf(availableDeclarations);
    }

    public RegisteredTool find(String name) {
        return tools.getOrDefault(name, RegisteredTool.unavailable());
    }

    /*
     * 背景：每轮模型请求都需要一致的工具声明，启用状态与定义元数据在启动后固定。
     * 设计意图：复用启动时已校验的声明快照，避免每轮重新读取 Definition 并重复构造。
     * 关键约束：Definition 的名称、描述和嵌套 Schema 不得在启动后修改；声明只做顶层复制，修改嵌套数据会使模型声明与执行合同失去一致性。
     */
    public List<LlmRequest.ToolDeclaration> modelToolDeclarations() {
        return declarations;
    }

    public String displayName(String name) {
        return find(name).displayName();
    }

    public Mono<ToolExecutionResult> execute(
            String name,
            ToolExecutionContext context,
            String arguments) {
        return execute(find(name), context, arguments);
    }

    /*
     * 背景：模型返回的工具名称和参数必须经过同一条边界才能进入本机文件操作。
     * 设计意图：由 Registry 串联查找后的定义校验与 Handler 执行，避免 Agent 层重复实现工具协议。
     * 关键约束：必须先完成 validate 再调用 handler；任何绕过校验的路径都可能把未授权参数传入工具。
     */
    public Mono<ToolExecutionResult> execute(
            RegisteredTool tool,
            ToolExecutionContext context,
            String arguments) {
        Objects.requireNonNull(tool, "Registered tool cannot be null");
        Objects.requireNonNull(context, "Tool execution context cannot be null");
        if (!tool.available()) {
            return Mono.just(new ToolCompleted(ToolOutcome.failure(
                    objectMapper,
                    "TOOL_NOT_AVAILABLE",
                    "未执行未知工具",
                    "The requested tool is not available.")));
        }

        ToolDefinition.Validation validation = tool.definition().validate(arguments);
        if (!validation.valid()) {
            return Mono.just(new ToolCompleted(ToolOutcome.failure(
                    objectMapper,
                    validation.code(),
                    validation.presentation(),
                    "The tool arguments are invalid.")));
        }
        return Mono.defer(() -> requireResult(
                        tool.handler().execute(context, arguments),
                        "Tool execute returned no result"))
                .map(result -> validateExecutionResult(tool, context, result));
    }

    /*
     * 背景：批准后的提交必须回到生成固定计划的修改工具，不能重新走模型参数校验或普通 execute。
     * 设计意图：Registry 只验证调用身份和修改能力，再把原计划分发给对应 MutationToolHandler。
     * 关键约束：不能把只读 Handler 当作修改工具，也不能接受其他 Run、调用或工具名的计划；否则审批可能授权错误操作。
     */
    public Mono<ToolOutcome> commit(
            ToolExecutionContext context,
            MutationPlan plan) {
        Objects.requireNonNull(context, "Tool execution context cannot be null");
        Objects.requireNonNull(plan, "Mutation plan cannot be null");
        RegisteredTool tool = find(plan.toolName());
        if (!tool.available() || !(tool.handler() instanceof MutationToolHandler mutationHandler)) {
            return Mono.error(protocolError("Mutation tool is not available"));
        }
        validatePlan(tool, context, plan);
        return Mono.defer(() -> requireResult(
                mutationHandler.commit(context, plan),
                "Tool commit returned no outcome"));
    }

    private ToolExecutionResult validateExecutionResult(
            RegisteredTool tool,
            ToolExecutionContext context,
            ToolExecutionResult result) {
        if (result instanceof ApprovalRequired approval) {
            if (!(tool.handler() instanceof MutationToolHandler)) {
                throw protocolError("Read-only tool returned an approval plan");
            }
            validatePlan(tool, context, approval.plan());
        }
        return result;
    }

    private void validatePlan(
            RegisteredTool tool,
            ToolExecutionContext context,
            MutationPlan plan) {
        if (!context.runId().equals(plan.runId())
                || !context.toolCallId().equals(plan.toolCallId())
                || !tool.definition().name().equals(plan.toolName())) {
            throw protocolError("Mutation plan identity does not match the tool call");
        }
    }

    private static <T> Mono<T> requireResult(Mono<T> result, String message) {
        if (result == null) {
            return Mono.error(protocolError(message));
        }
        return result.switchIfEmpty(Mono.error(protocolError(message)));
    }

    private static IllegalStateException protocolError(String message) {
        return new IllegalStateException(message);
    }

    public record RegisteredTool(
            ToolDefinition definition,
            ToolHandler handler,
            boolean available) {

        private static RegisteredTool unavailable() {
            return new RegisteredTool(null, null, false);
        }

        public String displayName() {
            return available ? definition.displayName() : "未知工具";
        }
    }
}
