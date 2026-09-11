package com.zlzcode.codeagent.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zlzcode.codeagent.tool.config.ToolProperties;
import com.zlzcode.codeagent.tool.handler.MutationToolHandler;
import com.zlzcode.codeagent.tool.model.ApprovalRequired;
import com.zlzcode.codeagent.tool.model.MutationOperation;
import com.zlzcode.codeagent.tool.model.MutationPlan;
import com.zlzcode.codeagent.tool.model.ToolCompleted;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolExecutionResult;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.workspace.security.WorkspacePathGuard;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Service
public final class MoveService implements MutationToolHandler {

    private final ObjectMapper objectMapper;
    private final WorkspacePathGuard pathGuard;
    private final ToolProperties properties;
    private final RunFileObservationService observationService;

    public MoveService(
            ObjectMapper objectMapper,
            WorkspacePathGuard pathGuard,
            ToolProperties properties,
            RunFileObservationService observationService) {
        this.objectMapper = objectMapper;
        this.pathGuard = pathGuard;
        this.properties = properties;
        this.observationService = observationService;
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionContext context, String arguments) {
        return Mono.fromCallable(() -> prepare(context, parse(arguments)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.executionTimeout())
                .onErrorResume(TimeoutException.class, exception -> Mono.just(new ToolCompleted(failure(
                        "TOOL_TIMEOUT", "准备文件移动超时", "The file move could not be prepared before timeout."))));
    }

    /*
     * 背景：审批等待期间源文件或目标路径可能变化，而批准只绑定原源版本和空目标状态。
     * 设计意图：commit 复核固定源目标后执行一次不覆盖的原子移动，不重算路径也不降级为复制后删除。
     * 关键约束：源摘要不一致或目标出现时必须失败；否则会移动未批准版本、覆盖文件或产生可重复提交副作用。
     */
    @Override
    public Mono<ToolOutcome> commit(ToolExecutionContext context, MutationPlan plan) {
        return Mono.fromCallable(() -> commitBlocking(context, plan))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolExecutionResult prepare(ToolExecutionContext context, MoveArguments arguments) {
        Path source = null;
        try {
            source = pathGuard.resolveExisting(context.workspace().root(), arguments.source());
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                return completedFailure("PATH_NOT_SUPPORTED", "源路径不是普通文件",
                        "The move source is not a regular file.");
            }
            if (Files.size(source) > properties.mutation().maxFileBytes()) {
                return completedFailure("FILE_TOO_LARGE", "源文件超过移动上限",
                        "The source file is too large to move.");
            }
            String sourceRelative = pathGuard.relativePath(context.workspace().root(), source);
            byte[] sourceBytes = Files.readAllBytes(source);
            String observedSha256 = observationService.find(context, sourceRelative).orElse(null);
            if (observedSha256 == null) {
                return completedFailure("FILE_NOT_OBSERVED", "移动前需要先读取源文件",
                        "The source file must be read in the current run before it can be moved.");
            }
            if (!observedSha256.equals(observationService.sha256(sourceBytes))) {
                return completedFailure("FILE_VERSION_CONFLICT", "源文件在读取后已发生变化",
                        "The source file changed after it was read. Read it again before moving it.");
            }

            Path destination = pathGuard.resolveMutationTarget(
                    context.workspace().root(), arguments.destination());
            String destinationRelative = pathGuard.relativePath(context.workspace().root(), destination);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                return completedFailure("TARGET_ALREADY_EXISTS", "移动目标已存在",
                        "The move destination already exists.");
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("source", sourceRelative);
            payload.put("destination", destinationRelative);
            MutationPlan plan = new MutationPlan(
                    context.runId(),
                    context.toolCallId(),
                    "move",
                    MutationOperation.MOVE,
                    List.of(sourceRelative, destinationRelative),
                    payload,
                    observedSha256,
                    false,
                    "将移动文件\n\n" + sourceRelative + " -> " + destinationRelative);
            return new ApprovalRequired(plan);
        } catch (NoSuchFileException exception) {
            return source == null
                    ? completedFailure("PATH_NOT_FOUND", "源文件不存在", "The move source does not exist.")
                    : completedFailure("PARENT_DIRECTORY_NOT_FOUND", "目标父目录不存在",
                            "The move destination parent directory does not exist.");
        } catch (FileSystemException exception) {
            return completedFailure("PATH_NOT_SUPPORTED", "路径类型不受支持",
                    "The requested move path is not supported.");
        } catch (IllegalArgumentException exception) {
            return completedFailure("WORKSPACE_PATH_INVALID", "移动路径无效",
                    "The requested path is outside the workspace.");
        } catch (IOException exception) {
            return completedFailure("FILE_ACCESS_FAILED", "检查移动路径失败",
                    "The requested move could not be inspected.");
        }
    }

    private ToolOutcome commitBlocking(ToolExecutionContext context, MutationPlan plan) {
        MovePlan move = validatePlan(plan);
        Path source = null;
        try {
            source = pathGuard.resolveExisting(context.workspace().root(), move.source());
            String sourceRelative = pathGuard.relativePath(context.workspace().root(), source);
            if (!sourceRelative.equals(move.source())
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(source) > properties.mutation().maxFileBytes()) {
                return versionConflict();
            }
            byte[] sourceBytes = Files.readAllBytes(source);
            if (!plan.observedSha256().equals(observationService.sha256(sourceBytes))) {
                return versionConflict();
            }

            Path destination = pathGuard.resolveMutationTarget(
                    context.workspace().root(), move.destination());
            String destinationRelative = pathGuard.relativePath(context.workspace().root(), destination);
            if (!destinationRelative.equals(move.destination())) {
                throw new IllegalStateException("Move plan destination changed after normalization");
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                return failure("TARGET_ALREADY_EXISTS", "移动目标已存在",
                        "The move destination was created before the approved move was committed.");
            }

            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            observationService.forget(context, sourceRelative);
            observationService.forget(context, destinationRelative);
            MoveResult result = new MoveResult(true, sourceRelative, destinationRelative);
            return new ToolOutcome(true, encode(result),
                    "已移动 " + sourceRelative + " -> " + destinationRelative);
        } catch (FileAlreadyExistsException exception) {
            return failure("TARGET_ALREADY_EXISTS", "移动目标已存在",
                    "The move destination was created before the approved move was committed.");
        } catch (NoSuchFileException exception) {
            return source == null
                    ? versionConflict()
                    : failure("PARENT_DIRECTORY_NOT_FOUND", "目标父目录已发生变化",
                            "The move destination parent directory no longer exists.");
        } catch (AtomicMoveNotSupportedException exception) {
            return failure("FILE_ACCESS_FAILED", "文件系统不支持原子移动",
                    "The file system could not move the file atomically.");
        } catch (FileSystemException exception) {
            return failure("PATH_NOT_SUPPORTED", "路径状态不再支持移动",
                    "The approved move path is no longer supported.");
        } catch (IllegalArgumentException exception) {
            return failure("WORKSPACE_PATH_INVALID", "移动路径无效",
                    "The requested path is outside the workspace.");
        } catch (IOException exception) {
            return failure("FILE_ACCESS_FAILED", "移动文件失败",
                    "The approved file move could not be completed.");
        }
    }

    private MovePlan validatePlan(MutationPlan plan) {
        if (plan.operation() != MutationOperation.MOVE || !"move".equals(plan.toolName())
                || plan.relativePaths().size() != 2 || plan.targetExisted()
                || plan.observedSha256() == null) {
            throw new IllegalStateException("Invalid move mutation plan");
        }
        JsonNode payload = plan.payload();
        JsonNode source = payload.get("source");
        JsonNode destination = payload.get("destination");
        if (payload.size() != 2 || source == null || !source.isTextual()
                || destination == null || !destination.isTextual()
                || !plan.relativePaths().get(0).equals(source.asText())
                || !plan.relativePaths().get(1).equals(destination.asText())
                || source.asText().equals(destination.asText())) {
            throw new IllegalStateException("Invalid move mutation payload");
        }
        return new MovePlan(source.asText(), destination.asText());
    }

    private MoveArguments parse(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            return new MoveArguments(node.get("source").asText(), node.get("destination").asText());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated move arguments could not be decoded", exception);
        }
    }

    private ToolCompleted completedFailure(String code, String presentation, String modelMessage) {
        return new ToolCompleted(failure(code, presentation, modelMessage));
    }

    private ToolOutcome versionConflict() {
        return failure("FILE_VERSION_CONFLICT", "源文件状态与审批计划不一致",
                "The source file changed after the approved move was prepared. Read it again and retry.");
    }

    private ToolOutcome failure(String code, String presentation, String modelMessage) {
        return ToolOutcome.failure(objectMapper, code, presentation, modelMessage);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码 move 结果", exception);
        }
    }

    private record MoveArguments(String source, String destination) {
    }

    private record MovePlan(String source, String destination) {
    }

    private record MoveResult(boolean ok, String source, String destination) {
    }
}
