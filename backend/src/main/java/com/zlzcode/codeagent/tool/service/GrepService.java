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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class GrepService implements ToolHandler {

    private final ObjectMapper objectMapper;
    private final FileSearchService fileSearchService;
    private final ToolProperties properties;

    public GrepService(ObjectMapper objectMapper, FileSearchService fileSearchService, ToolProperties properties) {
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
                        "TOOL_TIMEOUT", "内容搜索超时", "The content search did not finish before the tool timeout."))));
    }

    private ToolOutcome search(ToolExecutionContext context, GrepArguments arguments) {
        final Pattern pattern;
        try {
            pattern = Pattern.compile(arguments.pattern());
        } catch (PatternSyntaxException exception) {
            return failure("SEARCH_PATTERN_INVALID", "正则表达式无效", "The regular expression is invalid.");
        }

        try {
            FileSearchService.SearchSelection selection = fileSearchService.files(
                    context.workspace().root(), arguments.path(), arguments.include(),
                    arguments.force(), Integer.MAX_VALUE);
            ToolProperties.Grep limits = properties.grep();
            List<GrepFileResult> files = new ArrayList<>();
            boolean truncated = selection.incomplete();
            int matchCount = 0;

            for (FileSearchService.SearchFile file : selection.files()) {
                if (Files.size(file.path()) > limits.maxFileBytes()) {
                    if (selection.directFile()) {
                        return failure("FILE_TOO_LARGE", "文件超过搜索上限",
                                "The requested file is too large to search.");
                    }
                    truncated = true;
                    continue;
                }

                final String text;
                try {
                    text = decode(Files.readAllBytes(file.path()));
                } catch (CharacterCodingException exception) {
                    if (selection.directFile()) {
                        return failure("TEXT_ENCODING_INVALID", "文件不是有效 UTF-8 文本",
                                "The requested file is not valid UTF-8 text.");
                    }
                    truncated = true;
                    continue;
                } catch (IOException exception) {
                    if (selection.directFile()) throw exception;
                    truncated = true;
                    continue;
                }

                List<GrepMatch> matches = new ArrayList<>();
                List<String> lines = text.lines().toList();
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (!pattern.matcher(line).find()) continue;
                    Preview preview = preview(line, limits.maxLinePreviewBytes());
                    matches.add(new GrepMatch(index + 1, preview.text()));
                    matchCount++;
                    truncated |= preview.truncated();
                    if (matchCount >= limits.maxMatches()) {
                        truncated = true;
                        break;
                    }
                }
                if (!matches.isEmpty()) files.add(new GrepFileResult(file.relativePath(), matches));
                if (matchCount >= limits.maxMatches()) break;
            }

            GrepResult result = fit(new GrepResult(true, files, matchCount, truncated),
                    limits.maxResultChars());
            return new ToolOutcome(true, encode(result), "找到 " + result.matchCount() + " 行匹配"
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

    private GrepArguments parse(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            String include = node.has("include") ? node.get("include").asText() : null;
            return new GrepArguments(node.get("pattern").asText(), node.path("path").asText(""),
                    include, node.path("force").asBoolean(false));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated grep arguments could not be decoded", exception);
        }
    }

    private String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private Preview preview(String line, int maxBytes) {
        if (line.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return new Preview(line, false);
        StringBuilder value = new StringBuilder();
        int bytes = 0;
        for (int index = 0; index < line.length();) {
            int codePoint = line.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) break;
            value.append(character);
            bytes += characterBytes;
            index += Character.charCount(codePoint);
        }
        return new Preview(value.toString(), true);
    }

    private GrepResult fit(GrepResult original, int maxChars) {
        List<GrepFileResult> files = new ArrayList<>();
        for (GrepFileResult file : original.files()) {
            files.add(new GrepFileResult(file.path(), new ArrayList<>(file.matches())));
        }
        int matchCount = original.matchCount();
        boolean truncated = original.truncated();
        while (encode(new GrepResult(true, files, matchCount, truncated)).length() > maxChars
                && !files.isEmpty()) {
            GrepFileResult last = files.get(files.size() - 1);
            last.matches().remove(last.matches().size() - 1);
            matchCount--;
            truncated = true;
            if (last.matches().isEmpty()) files.remove(files.size() - 1);
        }
        return new GrepResult(true, List.copyOf(files), matchCount, truncated);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码 grep 结果", exception);
        }
    }

    private ToolOutcome failure(String code, String presentation, String modelMessage) {
        return ToolOutcome.failure(objectMapper, code, presentation, modelMessage);
    }

    private record GrepArguments(String pattern, String path, String include, boolean force) {
    }

    private record Preview(String text, boolean truncated) {
    }

    private record GrepMatch(int lineNumber, String line) {
    }

    private record GrepFileResult(String path, List<GrepMatch> matches) {
    }

    private record GrepResult(boolean ok, List<GrepFileResult> files, int matchCount, boolean truncated) {
    }
}
