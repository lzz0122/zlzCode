package com.zlzcode.codeagent.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.tool.config.ToolProperties;
import com.zlzcode.codeagent.tool.handler.ToolHandler;
import com.zlzcode.codeagent.tool.model.ToolCompleted;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolExecutionResult;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Service
public class GlobService implements ToolHandler {

    private final ObjectMapper objectMapper;
    private final FileSearchService fileSearchService;
    private final ToolProperties properties;

    public GlobService(ObjectMapper objectMapper, FileSearchService fileSearchService, ToolProperties properties) {
        this.objectMapper = objectMapper;
        this.fileSearchService = fileSearchService;
        this.properties = properties;
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionContext context, String arguments) {
        return Mono.<ToolExecutionResult>fromCallable(() -> new ToolCompleted(search(context, parse(arguments))))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.executionTimeout())
                .onErrorResume(TimeoutException.class, exception -> Mono.just(new ToolCompleted(failure(
                        "TOOL_TIMEOUT", "文件匹配超时", "The file search did not finish before the tool timeout."))));
    }

    private ToolOutcome search(ToolExecutionContext context, GlobArguments arguments) {
        try {
            ToolProperties.Glob limits = properties.glob();
            FileSearchService.SearchSelection selection = fileSearchService.files(
                    context.workspace().root(), arguments.path(), arguments.pattern(),
                    arguments.force(), limits.maxDepth());
            List<String> paths = selection.files().stream()
                    .map(FileSearchService.SearchFile::relativePath)
                    .limit(limits.maxResults())
                    .toList();
            boolean truncated = selection.incomplete() || selection.files().size() > paths.size();
            GlobResult result = fit(new GlobResult(true, new ArrayList<>(paths), truncated),
                    limits.maxResultChars());
            return new ToolOutcome(true, encode(result), "找到 " + result.paths().size() + " 个文件"
                    + (result.truncated() ? "；结果已截断" : ""));
        } catch (NoSuchFileException exception) {
            return failure("PATH_NOT_FOUND", "搜索路径不存在", "The requested search path does not exist.");
        } catch (FileSystemException exception) {
            return failure("PATH_NOT_SUPPORTED", "搜索路径类型不受支持",
                    "The requested search path is not supported.");
        } catch (IllegalArgumentException exception) {
            return failure("WORKSPACE_PATH_INVALID", "搜索路径无效", "The requested search path is invalid.");
        } catch (IOException exception) {
            return failure("FILE_ACCESS_FAILED", "搜索文件失败", "Workspace files could not be searched.");
        }
    }

    private GlobArguments parse(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            return new GlobArguments(node.get("pattern").asText(), node.path("path").asText(""),
                    node.path("force").asBoolean(false));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated glob arguments could not be decoded", exception);
        }
    }

    private GlobResult fit(GlobResult result, int maxChars) {
        List<String> paths = result.paths();
        boolean truncated = result.truncated();
        while (encode(new GlobResult(true, paths, truncated)).length() > maxChars && !paths.isEmpty()) {
            paths.remove(paths.size() - 1);
            truncated = true;
        }
        return new GlobResult(true, List.copyOf(paths), truncated);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码 glob 结果", exception);
        }
    }

    private ToolOutcome failure(String code, String presentation, String modelMessage) {
        return ToolOutcome.failure(objectMapper, code, presentation, modelMessage);
    }

    private record GlobArguments(String pattern, String path, boolean force) {
    }

    private record GlobResult(boolean ok, List<String> paths, boolean truncated) {
    }
}
