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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Service
public final class EditService implements MutationToolHandler {

    private final ObjectMapper objectMapper;
    private final WorkspacePathGuard pathGuard;
    private final ToolProperties properties;
    private final RunFileObservationService observationService;
    private final TextMutationSupport textMutationSupport;

    public EditService(
            ObjectMapper objectMapper,
            WorkspacePathGuard pathGuard,
            ToolProperties properties,
            RunFileObservationService observationService,
            TextMutationSupport textMutationSupport) {
        this.objectMapper = objectMapper;
        this.pathGuard = pathGuard;
        this.properties = properties;
        this.observationService = observationService;
        this.textMutationSupport = textMutationSupport;
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionContext context, String arguments) {
        return Mono.fromCallable(() -> prepare(context, parse(arguments)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.executionTimeout())
                .onErrorResume(TimeoutException.class, exception -> Mono.just(new ToolCompleted(failure(
                        "TOOL_TIMEOUT", "准备文件编辑超时", "The file edit could not be prepared before timeout."))));
    }

    /*
     * 背景：用户批准的是 execute 已计算出的确定文本，审批等待期间原文件仍可能发生变化。
     * 设计意图：commit 只使用固定新内容，并在原子替换前复核观察摘要，不重新执行字符串匹配算法。
     * 关键约束：不能根据提交时文件重新计算替换或忽略摘要冲突；否则批准内容会漂移并覆盖审批外的修改。
     */
    @Override
    public Mono<ToolOutcome> commit(ToolExecutionContext context, MutationPlan plan) {
        return Mono.fromCallable(() -> commitBlocking(context, plan))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolExecutionResult prepare(ToolExecutionContext context, EditArguments arguments) {
        ToolProperties.Mutation limits = properties.mutation();
        try {
            Path target = pathGuard.resolveExisting(context.workspace().root(), arguments.filePath());
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return completedFailure("PATH_NOT_SUPPORTED", "目标不是普通文件",
                        "The requested path is not a regular file.");
            }
            if (Files.size(target) > limits.maxFileBytes()) {
                return completedFailure("FILE_TOO_LARGE", "目标文件超过修改上限",
                        "The requested file is too large to edit.");
            }

            byte[] currentBytes = Files.readAllBytes(target);
            String currentContent = textMutationSupport.decode(currentBytes);
            String relative = pathGuard.relativePath(context.workspace().root(), target);
            String observedSha256 = observationService.find(context, relative).orElse(null);
            if (observedSha256 == null) {
                return completedFailure("FILE_NOT_OBSERVED", "编辑前需要先读取文件",
                        "The file must be read in the current run before it can be edited.");
            }
            if (!observedSha256.equals(observationService.sha256(currentBytes))) {
                return completedFailure("FILE_VERSION_CONFLICT", "文件在读取后已发生变化",
                        "The file changed after it was read. Read it again before editing it.");
            }

            int matches = countMatches(currentContent, arguments.oldString());
            if (matches == 0) {
                return completedFailure("EDIT_TARGET_NOT_FOUND", "未找到待替换文本",
                        "The requested text was not found in the file.");
            }
            if (!arguments.replaceAll() && matches > 1) {
                return completedFailure("EDIT_TARGET_NOT_UNIQUE", "待替换文本不是唯一匹配",
                        "The requested text occurs more than once. Use replace_all or provide more context.");
            }

            int replacements = arguments.replaceAll() ? matches : 1;
            String newContent = arguments.replaceAll()
                    ? currentContent.replace(arguments.oldString(), arguments.newString())
                    : replaceFirst(currentContent, arguments.oldString(), arguments.newString());
            byte[] newBytes = newContent.getBytes(StandardCharsets.UTF_8);
            if (newContent.length() > limits.maxContentChars() || newBytes.length > limits.maxFileBytes()) {
                return completedFailure("FILE_TOO_LARGE", "编辑后的文件超过上限",
                        "The edited file would exceed the configured size limit.");
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("file_path", relative);
            payload.put("content", newContent);
            payload.put("replacements", replacements);
            MutationPlan plan = new MutationPlan(
                    context.runId(),
                    context.toolCallId(),
                    "edit",
                    MutationOperation.EDIT,
                    List.of(relative),
                    payload,
                    observedSha256,
                    true,
                    presentationSummary(relative, arguments, replacements));
            return new ApprovalRequired(plan);
        } catch (NoSuchFileException exception) {
            return completedFailure("PATH_NOT_FOUND", "目标文件不存在",
                    "The requested file does not exist.");
        } catch (CharacterCodingException exception) {
            return completedFailure("TEXT_ENCODING_INVALID", "目标文件不是有效 UTF-8 文本",
                    "The requested file is not valid UTF-8 text.");
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
        EditPlan edit = validatePlan(plan);
        try {
            Path target = pathGuard.resolveExisting(context.workspace().root(), edit.filePath());
            String relative = pathGuard.relativePath(context.workspace().root(), target);
            if (!relative.equals(plan.relativePaths().getFirst())
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(target) > properties.mutation().maxFileBytes()) {
                return versionConflict();
            }
            byte[] currentBytes = Files.readAllBytes(target);
            if (!plan.observedSha256().equals(observationService.sha256(currentBytes))) {
                return versionConflict();
            }

            byte[] newBytes = edit.content().getBytes(StandardCharsets.UTF_8);
            textMutationSupport.atomicWrite(target, newBytes, true);
            observationService.forget(context, relative);
            EditResult result = new EditResult(true, relative, edit.replacements());
            return new ToolOutcome(true, encode(result),
                    "已编辑 " + relative + "（替换 " + edit.replacements() + " 处）");
        } catch (NoSuchFileException exception) {
            return versionConflict();
        } catch (AtomicMoveNotSupportedException exception) {
            return failure("FILE_ACCESS_FAILED", "文件系统不支持原子编辑",
                    "The file system could not commit the edit atomically.");
        } catch (FileSystemException exception) {
            return failure("PATH_NOT_SUPPORTED", "路径状态不再支持编辑",
                    "The target path no longer supports the approved edit.");
        } catch (IllegalArgumentException exception) {
            return failure("WORKSPACE_PATH_INVALID", "文件路径无效",
                    "The requested path is outside the workspace.");
        } catch (IOException exception) {
            return failure("FILE_ACCESS_FAILED", "编辑文件失败",
                    "The approved file edit could not be completed.");
        }
    }

    private EditPlan validatePlan(MutationPlan plan) {
        if (plan.operation() != MutationOperation.EDIT || !"edit".equals(plan.toolName())
                || plan.relativePaths().size() != 1 || !plan.targetExisted()
                || plan.observedSha256() == null) {
            throw new IllegalStateException("Invalid edit mutation plan");
        }
        JsonNode payload = plan.payload();
        JsonNode filePath = payload.get("file_path");
        JsonNode content = payload.get("content");
        JsonNode replacements = payload.get("replacements");
        if (payload.size() != 3 || filePath == null || !filePath.isTextual()
                || content == null || !content.isTextual()
                || replacements == null || !replacements.canConvertToInt() || replacements.asInt() <= 0
                || !plan.relativePaths().getFirst().equals(filePath.asText())) {
            throw new IllegalStateException("Invalid edit mutation payload");
        }
        EditPlan edit = new EditPlan(filePath.asText(), content.asText(), replacements.asInt());
        byte[] bytes = edit.content().getBytes(StandardCharsets.UTF_8);
        if (edit.content().length() > properties.mutation().maxContentChars()
                || bytes.length > properties.mutation().maxFileBytes()) {
            throw new IllegalStateException("Approved edit content exceeds configured limits");
        }
        return edit;
    }

    private int countMatches(String content, String target) {
        int matches = 0;
        int offset = 0;
        while (true) {
            int found = content.indexOf(target, offset);
            if (found < 0) return matches;
            matches++;
            offset = found + target.length();
        }
    }

    private String replaceFirst(String content, String oldString, String newString) {
        int start = content.indexOf(oldString);
        return content.substring(0, start) + newString + content.substring(start + oldString.length());
    }

    private String presentationSummary(String path, EditArguments arguments, int replacements) {
        String summary = "将编辑 " + path + "（替换 " + replacements + " 处）\n\n"
                + "旧文本：\n" + arguments.oldString() + "\n\n新文本：\n" + arguments.newString();
        int limit = properties.mutation().maxPreviewChars();
        if (summary.length() <= limit) return summary;
        String suffix = "\n…预览已截断";
        int bodyLimit = Math.max(0, limit - suffix.length());
        return summary.substring(0, bodyLimit) + suffix.substring(0, limit - bodyLimit);
    }

    private EditArguments parse(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            JsonNode replaceAll = node.get("replace_all");
            return new EditArguments(
                    node.get("file_path").asText(),
                    node.get("old_string").asText(),
                    node.get("new_string").asText(),
                    replaceAll != null && replaceAll.asBoolean());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated edit arguments could not be decoded", exception);
        }
    }

    private ToolCompleted completedFailure(String code, String presentation, String modelMessage) {
        return new ToolCompleted(failure(code, presentation, modelMessage));
    }

    private ToolOutcome versionConflict() {
        return failure("FILE_VERSION_CONFLICT", "文件状态与审批计划不一致",
                "The file changed after the approved edit was prepared. Read it again and retry.");
    }

    private ToolOutcome failure(String code, String presentation, String modelMessage) {
        return ToolOutcome.failure(objectMapper, code, presentation, modelMessage);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码 edit 结果", exception);
        }
    }

    private record EditArguments(String filePath, String oldString, String newString, boolean replaceAll) {
    }

    private record EditPlan(String filePath, String content, int replacements) {
    }

    private record EditResult(boolean ok, String path, int replacements) {
    }
}
