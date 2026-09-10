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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
public final class WriteService implements MutationToolHandler {

    private final ObjectMapper objectMapper;
    private final WorkspacePathGuard pathGuard;
    private final ToolProperties properties;
    private final RunFileObservationService observationService;

    public WriteService(
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
                        "TOOL_TIMEOUT", "准备文件写入超时", "The file write could not be prepared before timeout."))));
    }

    /*
     * 背景：用户批准的是 execute 阶段固定的内容，但审批等待期间目标文件仍可能被外部修改。
     * 设计意图：commit 只读取固定计划，在同目录写入临时文件并原子替换，不重新解释原始模型参数。
     * 关键约束：覆盖前必须复核原始字节摘要，创建前必须复核目标仍不存在；失败时不能改写其他目标或降级为非原子提交。
     */
    @Override
    public Mono<ToolOutcome> commit(ToolExecutionContext context, MutationPlan plan) {
        return Mono.fromCallable(() -> commitBlocking(context, plan))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolExecutionResult prepare(ToolExecutionContext context, WriteArguments arguments) {
        ToolProperties.Mutation limits = properties.mutation();
        if (arguments.content().length() > limits.maxContentChars()) {
            return completedFailure("FILE_TOO_LARGE", "写入内容超过上限",
                    "The requested file content is too large.");
        }
        byte[] newBytes = arguments.content().getBytes(StandardCharsets.UTF_8);
        if (newBytes.length > limits.maxFileBytes()) {
            return completedFailure("FILE_TOO_LARGE", "写入内容超过上限",
                    "The requested file content is too large.");
        }

        try {
            Path target = pathGuard.resolveMutationTarget(
                    context.workspace().root(), arguments.filePath());
            String relative = pathGuard.relativePath(context.workspace().root(), target);
            boolean targetExisted = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            String observedSha256 = null;
            if (targetExisted) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    return completedFailure("PATH_NOT_SUPPORTED", "目标不是普通文件",
                            "The requested path is not a regular file.");
                }
                if (Files.size(target) > limits.maxFileBytes()) {
                    return completedFailure("FILE_TOO_LARGE", "目标文件超过修改上限",
                            "The requested file is too large to replace.");
                }
                byte[] currentBytes = Files.readAllBytes(target);
                decode(currentBytes);
                observedSha256 = observationService.find(context, relative).orElse(null);
                if (observedSha256 == null) {
                    return completedFailure("FILE_NOT_OBSERVED", "覆盖前需要先读取文件",
                            "The existing file must be read in the current run before it can be replaced.");
                }
                if (!observedSha256.equals(observationService.sha256(currentBytes))) {
                    return completedFailure("FILE_VERSION_CONFLICT", "文件在读取后已发生变化",
                            "The file changed after it was read. Read it again before replacing it.");
                }
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("file_path", relative);
            payload.put("content", arguments.content());
            MutationPlan plan = new MutationPlan(
                    context.runId(),
                    context.toolCallId(),
                    "write",
                    MutationOperation.WRITE,
                    List.of(relative),
                    payload,
                    observedSha256,
                    targetExisted,
                    presentationSummary(relative, arguments.content(), newBytes.length, targetExisted));
            return new ApprovalRequired(plan);
        } catch (NoSuchFileException exception) {
            return completedFailure("PARENT_DIRECTORY_NOT_FOUND", "目标父目录不存在",
                    "The target parent directory does not exist.");
        } catch (CharacterCodingException exception) {
            return completedFailure("TEXT_ENCODING_INVALID", "现有文件不是有效 UTF-8 文本",
                    "The existing file is not valid UTF-8 text.");
        } catch (FileSystemException exception) {
            return completedFailure("PATH_NOT_SUPPORTED", "路径类型不受支持",
                    "The requested path is not supported.");
        } catch (IllegalArgumentException exception) {
            return completedFailure("WORKSPACE_PATH_INVALID", "文件路径无效",
                    "The requested path is outside the workspace.");
        } catch (IOException exception) {
            return completedFailure("FILE_ACCESS_FAILED", "读取目标文件失败",
                    "The requested file could not be inspected.");
        }
    }

    private ToolOutcome commitBlocking(ToolExecutionContext context, MutationPlan plan) {
        WriteArguments arguments = validatePlan(plan);
        byte[] newBytes = arguments.content().getBytes(StandardCharsets.UTF_8);
        Path target = null;
        try {
            target = pathGuard.resolveMutationTarget(context.workspace().root(), arguments.filePath());
            String relative = pathGuard.relativePath(context.workspace().root(), target);
            if (!relative.equals(plan.relativePaths().getFirst())) {
                throw new IllegalStateException("Write plan path changed after normalization");
            }

            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (!plan.targetExisted()) {
                if (exists) {
                    return failure("TARGET_ALREADY_EXISTS", "目标文件已存在",
                            "The target file was created before the approved write was committed.");
                }
            } else {
                if (!exists || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    return versionConflict();
                }
                if (Files.size(target) > properties.mutation().maxFileBytes()) {
                    return versionConflict();
                }
                byte[] currentBytes = Files.readAllBytes(target);
                if (!plan.observedSha256().equals(observationService.sha256(currentBytes))) {
                    return versionConflict();
                }
            }

            atomicWrite(target, newBytes, plan.targetExisted());
            observationService.forget(context, relative);
            WriteResult result = new WriteResult(
                    true, plan.targetExisted() ? "update" : "create", relative, newBytes.length);
            return new ToolOutcome(true, encode(result),
                    (plan.targetExisted() ? "已覆盖 " : "已创建 ") + relative + "（" + newBytes.length + " 字节）");
        } catch (FileAlreadyExistsException exception) {
            return failure("TARGET_ALREADY_EXISTS", "目标文件已存在",
                    "The target file was created before the approved write was committed.");
        } catch (NoSuchFileException exception) {
            return plan.targetExisted()
                    ? versionConflict()
                    : failure("PARENT_DIRECTORY_NOT_FOUND", "目标父目录不存在",
                            "The target parent directory no longer exists.");
        } catch (AtomicMoveNotSupportedException exception) {
            return failure("FILE_ACCESS_FAILED", "文件系统不支持原子写入",
                    "The file system could not commit the write atomically.");
        } catch (FileSystemException exception) {
            return failure("PATH_NOT_SUPPORTED", "路径状态不再支持写入",
                    "The target path no longer supports the approved write.");
        } catch (IllegalArgumentException exception) {
            return failure("WORKSPACE_PATH_INVALID", "文件路径无效",
                    "The requested path is outside the workspace.");
        } catch (IOException exception) {
            return failure("FILE_ACCESS_FAILED", "写入文件失败",
                    "The approved file write could not be completed.");
        }
    }

    private WriteArguments validatePlan(MutationPlan plan) {
        if (plan.operation() != MutationOperation.WRITE || !"write".equals(plan.toolName())
                || plan.relativePaths().size() != 1) {
            throw new IllegalStateException("Invalid write mutation plan");
        }
        JsonNode payload = plan.payload();
        JsonNode filePath = payload.get("file_path");
        JsonNode content = payload.get("content");
        if (payload.size() != 2 || filePath == null || !filePath.isTextual()
                || content == null || !content.isTextual()
                || !plan.relativePaths().getFirst().equals(filePath.asText())
                || plan.targetExisted() != (plan.observedSha256() != null)) {
            throw new IllegalStateException("Invalid write mutation payload");
        }
        WriteArguments arguments = new WriteArguments(filePath.asText(), content.asText());
        byte[] bytes = arguments.content().getBytes(StandardCharsets.UTF_8);
        if (arguments.content().length() > properties.mutation().maxContentChars()
                || bytes.length > properties.mutation().maxFileBytes()) {
            throw new IllegalStateException("Approved write content exceeds configured limits");
        }
        return arguments;
    }

    private void atomicWrite(Path target, byte[] bytes, boolean replace) throws IOException {
        Path parent = target.getParent();
        String prefix = "." + target.getFileName() + ".";
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        try {
            Files.write(temporary, bytes);
            if (replace) {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String presentationSummary(String path, String content, int bytes, boolean existed) {
        String heading = (existed ? "将覆盖 " : "将创建 ") + path + "（" + bytes + " 字节）";
        if (content.isEmpty()) return heading + "\n\n<empty file>";
        String summary = heading + "\n\n" + content;
        int limit = properties.mutation().maxPreviewChars();
        if (summary.length() <= limit) return summary;
        String suffix = "\n…预览已截断";
        int contentLimit = Math.max(0, limit - suffix.length());
        return summary.substring(0, contentLimit) + suffix.substring(0, limit - contentLimit);
    }

    private String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private WriteArguments parse(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            return new WriteArguments(node.get("file_path").asText(), node.get("content").asText());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated write arguments could not be decoded", exception);
        }
    }

    private ToolCompleted completedFailure(String code, String presentation, String modelMessage) {
        return new ToolCompleted(failure(code, presentation, modelMessage));
    }

    private ToolOutcome versionConflict() {
        return failure("FILE_VERSION_CONFLICT", "文件状态与审批计划不一致",
                "The file changed after the approved plan was prepared. Read it again and retry.");
    }

    private ToolOutcome failure(String code, String presentation, String modelMessage) {
        return ToolOutcome.failure(objectMapper, code, presentation, modelMessage);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码 write 结果", exception);
        }
    }

    private record WriteArguments(String filePath, String content) {
    }

    private record WriteResult(boolean ok, String operation, String path, int bytes) {
    }
}
