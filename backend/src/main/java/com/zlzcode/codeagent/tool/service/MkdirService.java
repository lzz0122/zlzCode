package com.zlzcode.codeagent.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Service
public final class MkdirService implements MutationToolHandler {

    private final ObjectMapper objectMapper;
    private final WorkspacePathGuard pathGuard;
    private final ToolProperties properties;

    public MkdirService(ObjectMapper objectMapper, WorkspacePathGuard pathGuard, ToolProperties properties) {
        this.objectMapper = objectMapper;
        this.pathGuard = pathGuard;
        this.properties = properties;
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionContext context, String arguments) {
        return Mono.fromCallable(() -> prepare(context, parse(arguments)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.executionTimeout())
                .onErrorResume(TimeoutException.class, exception -> Mono.just(new ToolCompleted(failure(
                        "TOOL_TIMEOUT", "准备目录创建超时",
                        "The directory creation could not be prepared before timeout."))));
    }

    /*
     * 背景：parents=true 可能创建多个目录，而用户只批准 execute 阶段展示的确定范围。
     * 设计意图：commit 按固定清单逐项创建，不调用 createDirectories 临时推导新的父目录范围。
     * 关键约束：审批后不能扩大、缩小或替换清单；否则会创建未展示的目录，部分失败也不能伪装成完整成功。
     */
    @Override
    public Mono<ToolOutcome> commit(ToolExecutionContext context, MutationPlan plan) {
        return Mono.fromCallable(() -> commitBlocking(context, plan))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolExecutionResult prepare(ToolExecutionContext context, MkdirArguments arguments) {
        try {
            Path target = pathGuard.resolveCreationTarget(context.workspace().root(), arguments.path());
            String relative = pathGuard.relativePath(context.workspace().root(), target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    return completedFailure("PATH_NOT_SUPPORTED", "目标不是目录",
                            "The requested path already exists and is not a directory.");
                }
                return new ToolCompleted(success(relative, false));
            }

            List<String> missing = missingDirectories(context, target);
            if (!arguments.parents() && missing.size() != 1) {
                return completedFailure("PARENT_DIRECTORY_NOT_FOUND", "目标父目录不存在",
                        "The target parent directory does not exist. Set parents to true to create it.");
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("path", relative);
            payload.put("parents", arguments.parents());
            ArrayNode directories = payload.putArray("directories");
            missing.forEach(directories::add);
            MutationPlan plan = new MutationPlan(
                    context.runId(),
                    context.toolCallId(),
                    "mkdir",
                    MutationOperation.MKDIR,
                    missing,
                    payload,
                    null,
                    false,
                    presentationSummary(relative, missing));
            return new ApprovalRequired(plan);
        } catch (FileSystemException exception) {
            return completedFailure("PATH_NOT_SUPPORTED", "路径类型不受支持",
                    "The requested directory path is not supported.");
        } catch (IllegalArgumentException exception) {
            return completedFailure("WORKSPACE_PATH_INVALID", "目录路径无效",
                    "The requested path is outside the workspace.");
        } catch (IOException exception) {
            return completedFailure("FILE_ACCESS_FAILED", "检查目录失败",
                    "The requested directory could not be inspected.");
        }
    }

    private ToolOutcome commitBlocking(ToolExecutionContext context, MutationPlan plan) {
        MkdirPlan mkdir = validatePlan(plan);
        try {
            Path target = pathGuard.resolveCreationTarget(context.workspace().root(), mkdir.path());
            String relative = pathGuard.relativePath(context.workspace().root(), target);
            if (!relative.equals(mkdir.path())) {
                throw new IllegalStateException("Mkdir plan path changed after normalization");
            }

            List<Path> directories = new ArrayList<>();
            for (String planned : mkdir.directories()) {
                Path directory = pathGuard.resolveCreationTarget(context.workspace().root(), planned);
                if (!planned.equals(pathGuard.relativePath(context.workspace().root(), directory))) {
                    throw new IllegalStateException("Mkdir plan directory changed after normalization");
                }
                if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    return failure("TARGET_ALREADY_EXISTS", "目录状态与审批计划不一致",
                            "A directory in the approved creation plan already exists. Inspect the workspace and retry.");
                }
                directories.add(directory);
            }

            for (Path directory : directories) {
                Files.createDirectory(directory);
            }
            return success(relative, true);
        } catch (FileAlreadyExistsException exception) {
            return failure("TARGET_ALREADY_EXISTS", "目录状态与审批计划不一致",
                    "A directory in the approved creation plan already exists. Inspect the workspace and retry.");
        } catch (NoSuchFileException exception) {
            return failure("PARENT_DIRECTORY_NOT_FOUND", "目录父路径已发生变化",
                    "A parent directory from the approved plan no longer exists.");
        } catch (FileSystemException exception) {
            return failure("PATH_NOT_SUPPORTED", "路径状态不再支持创建目录",
                    "The approved directory path is no longer supported.");
        } catch (IllegalArgumentException exception) {
            return failure("WORKSPACE_PATH_INVALID", "目录路径无效",
                    "The requested path is outside the workspace.");
        } catch (IOException exception) {
            return failure("FILE_ACCESS_FAILED", "创建目录失败",
                    "The approved directory creation could not be completed.");
        }
    }

    private List<String> missingDirectories(ToolExecutionContext context, Path target) throws IOException {
        List<Path> reversed = new ArrayList<>();
        Path current = target;
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            reversed.add(current);
            current = current.getParent();
            if (current == null) throw new IllegalArgumentException("Directory path leaves the workspace");
        }
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileSystemException(target.toString(), null, "existing ancestor is not a directory");
        }
        Collections.reverse(reversed);
        List<String> relative = new ArrayList<>(reversed.size());
        for (Path directory : reversed) {
            relative.add(pathGuard.relativePath(context.workspace().root(), directory));
        }
        return List.copyOf(relative);
    }

    private MkdirPlan validatePlan(MutationPlan plan) {
        if (plan.operation() != MutationOperation.MKDIR || !"mkdir".equals(plan.toolName())
                || plan.relativePaths().isEmpty() || plan.targetExisted()
                || plan.observedSha256() != null) {
            throw new IllegalStateException("Invalid mkdir mutation plan");
        }
        JsonNode payload = plan.payload();
        JsonNode path = payload.get("path");
        JsonNode parents = payload.get("parents");
        JsonNode directories = payload.get("directories");
        if (payload.size() != 3 || path == null || !path.isTextual()
                || parents == null || !parents.isBoolean()
                || directories == null || !directories.isArray() || directories.isEmpty()) {
            throw new IllegalStateException("Invalid mkdir mutation payload");
        }
        List<String> planned = new ArrayList<>();
        for (JsonNode directory : directories) {
            if (!directory.isTextual() || directory.asText().isBlank()) {
                throw new IllegalStateException("Invalid mkdir directory plan");
            }
            planned.add(directory.asText());
        }
        if (!plan.relativePaths().equals(planned)
                || !path.asText().equals(planned.getLast())
                || (!parents.asBoolean() && planned.size() != 1)) {
            throw new IllegalStateException("Invalid mkdir directory range");
        }
        return new MkdirPlan(path.asText(), parents.asBoolean(), List.copyOf(planned));
    }

    private String presentationSummary(String target, List<String> directories) {
        String summary = "将创建目录 " + target + "\n\n创建范围：\n- " + String.join("\n- ", directories);
        int limit = properties.mutation().maxPreviewChars();
        if (summary.length() <= limit) return summary;
        String suffix = "\n…预览已截断";
        int bodyLimit = Math.max(0, limit - suffix.length());
        return summary.substring(0, bodyLimit) + suffix.substring(0, limit - bodyLimit);
    }

    private MkdirArguments parse(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            JsonNode parents = node.get("parents");
            return new MkdirArguments(node.get("path").asText(), parents != null && parents.asBoolean());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated mkdir arguments could not be decoded", exception);
        }
    }

    private ToolCompleted completedFailure(String code, String presentation, String modelMessage) {
        return new ToolCompleted(failure(code, presentation, modelMessage));
    }

    private ToolOutcome success(String path, boolean created) {
        MkdirResult result = new MkdirResult(true, path, created);
        return new ToolOutcome(true, encode(result),
                created ? "已创建目录 " + path : "目录已存在 " + path);
    }

    private ToolOutcome failure(String code, String presentation, String modelMessage) {
        return ToolOutcome.failure(objectMapper, code, presentation, modelMessage);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码 mkdir 结果", exception);
        }
    }

    private record MkdirArguments(String path, boolean parents) {
    }

    private record MkdirPlan(String path, boolean parents, List<String> directories) {
    }

    private record MkdirResult(boolean ok, String path, boolean created) {
    }
}
