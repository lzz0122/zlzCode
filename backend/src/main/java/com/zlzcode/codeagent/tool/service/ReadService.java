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
import com.zlzcode.codeagent.workspace.security.WorkspacePathGuard;
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
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Service
public class ReadService implements ToolHandler {

    private final ObjectMapper objectMapper;
    private final WorkspacePathGuard pathGuard;
    private final ToolProperties properties;

    public ReadService(ObjectMapper objectMapper, WorkspacePathGuard pathGuard, ToolProperties properties) {
        this.objectMapper = objectMapper;
        this.pathGuard = pathGuard;
        this.properties = properties;
    }

    /*
     * 背景：真实文件读取使用阻塞式 NIO，直接运行在 WebFlux 事件线程会阻塞其他请求。
     * 设计意图：保持 Handler 自包含，用 boundedElastic 执行一次读取并消费统一工具超时，不增加额外调度抽象。
     * 关键约束：不能切回事件线程或旧的 codeagent.run.tool-timeout；否则会阻塞响应链或形成两套超时语义。
     */
    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionContext context, String arguments) {
        return Mono.<ToolExecutionResult>fromCallable(() -> new ToolCompleted(read(context, parse(arguments))))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.executionTimeout())
                .onErrorResume(TimeoutException.class, exception -> Mono.just(new ToolCompleted(failure(
                        "TOOL_TIMEOUT", "读取文件超时", "The file could not be read before the tool timeout."))));
    }

    private ToolOutcome read(ToolExecutionContext context, ReadArguments arguments) {
        try {
            Path file = pathGuard.resolveExisting(context.workspace().root(), arguments.filePath());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return failure("PATH_NOT_SUPPORTED", "目标不是普通文件", "The requested path is not a regular file.");
            }
            ToolProperties.Read limits = properties.read();
            if (Files.size(file) > limits.maxFileBytes()) {
                return failure("FILE_TOO_LARGE", "文件超过读取上限", "The requested file is too large to read.");
            }

            String text = decode(Files.readAllBytes(file));
            List<String> lines = text.lines().toList();
            int startIndex = Math.min(arguments.offset() - 1, lines.size());
            int endIndex = Math.min(startIndex + arguments.limit(), lines.size());
            List<String> window = new ArrayList<>();
            boolean truncated = arguments.offset() > 1 || endIndex < lines.size();
            for (int index = startIndex; index < endIndex; index++) {
                String line = lines.get(index);
                if (line.length() > limits.maxLineLength()) {
                    line = line.substring(0, limits.maxLineLength());
                    truncated = true;
                }
                window.add(line);
            }

            String content = String.join(newline(text), window);
            int startLine = window.isEmpty() ? 0 : arguments.offset();
            int endLine = window.isEmpty() ? 0 : arguments.offset() + window.size() - 1;
            String relative = pathGuard.relativePath(context.workspace().root(), file);
            ReadResult result = fit(new ReadResult(true, relative, startLine, endLine,
                    lines.size(), content, truncated), limits.maxResultChars());
            return new ToolOutcome(true, encode(result), presentation(result));
        } catch (NoSuchFileException exception) {
            return failure("PATH_NOT_FOUND", "文件不存在", "The requested file does not exist.");
        } catch (CharacterCodingException exception) {
            return failure("TEXT_ENCODING_INVALID", "文件不是有效 UTF-8 文本",
                    "The requested file is not valid UTF-8 text.");
        } catch (FileSystemException exception) {
            return failure("PATH_NOT_SUPPORTED", "路径类型不受支持", "The requested path is not supported.");
        } catch (IllegalArgumentException exception) {
            return failure("WORKSPACE_PATH_INVALID", "文件路径无效", "The requested path is outside the workspace.");
        } catch (IOException exception) {
            return failure("FILE_ACCESS_FAILED", "读取文件失败", "The requested file could not be read.");
        }
    }

    private ReadArguments parse(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            int offset = node.has("offset") ? node.get("offset").intValue() : 1;
            int limit = node.has("limit") ? node.get("limit").intValue() : properties.read().defaultLimit();
            return new ReadArguments(node.get("file_path").asText(), offset, limit);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated read arguments could not be decoded", exception);
        }
    }

    private String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private String newline(String text) {
        if (text.contains("\r\n")) return "\r\n";
        if (text.contains("\r")) return "\r";
        return "\n";
    }

    private ReadResult fit(ReadResult result, int maxChars) {
        if (encodedLength(result) <= maxChars) return result;
        int low = 0;
        int high = result.content().length();
        String content = "";
        while (low <= high) {
            int middle = (low + high) >>> 1;
            ReadResult candidate = result.withContent(result.content().substring(0, middle), true);
            if (encodedLength(candidate) <= maxChars) {
                content = candidate.content();
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result.withContent(content, true);
    }

    private int encodedLength(ReadResult result) {
        return encode(result).length();
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码 read 结果", exception);
        }
    }

    private String presentation(ReadResult result) {
        if (result.startLine() == 0) return "已读取 " + result.path() + "；文件没有可返回的行";
        return "已读取 " + result.path() + " 第 " + result.startLine() + "-" + result.endLine() + " 行"
                + (result.truncated() ? "；结果已截断" : "");
    }

    private ToolOutcome failure(String code, String presentation, String modelMessage) {
        return ToolOutcome.failure(objectMapper, code, presentation, modelMessage);
    }

    private record ReadArguments(String filePath, int offset, int limit) {
    }

    private record ReadResult(
            boolean ok,
            String path,
            int startLine,
            int endLine,
            int totalLines,
            String content,
            boolean truncated) {

        ReadResult withContent(String value, boolean truncated) {
            return new ReadResult(ok, path, startLine, endLine, totalLines, value, truncated);
        }
    }
}
