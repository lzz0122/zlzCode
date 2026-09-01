package com.zlzcode.codeagent.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.tool.handler.ToolHandler;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.workspace.security.WorkspacePathGuard;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class WorkspaceOverviewService implements ToolHandler {

    private static final int MAX_ENTRIES = 200;
    private static final int MAX_RESULT_CHARS = 20_000;
    private final Duration executionTimeout;

    private final ObjectMapper objectMapper;
    private final WorkspacePathGuard pathGuard;

    public WorkspaceOverviewService(
            ObjectMapper objectMapper,
            WorkspacePathGuard pathGuard,
            @Value("${codeagent.run.tool-timeout:PT10S}") Duration executionTimeout) {
        this.objectMapper = objectMapper;
        this.pathGuard = pathGuard;
        this.executionTimeout = executionTimeout;
    }

    /*
     * 背景：工作区扫描使用阻塞式文件系统 API，不能直接占用 WebFlux 的事件线程。
     * 设计意图：工具服务同时作为 Handler，统一负责调度、超时和扫描结果收口，避免增加仅转发的适配类。
     * 关键约束：扫描必须运行在 boundedElastic 且保留两秒超时，否则单次慢目录会阻塞其他 Agent 请求。
     */
    @Override
    public Mono<ToolOutcome> execute(AuthorizedWorkspace workspace, String arguments) {
        return Mono.fromCallable(() -> scan(workspace.root()))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(executionTimeout)
                .onErrorReturn(ToolOutcome.failure(
                        objectMapper,
                        "TOOL_TIMEOUT",
                        "读取工作区超时",
                        "The selected workspace could not be listed safely."));
    }

    private ToolOutcome scan(Path workspaceRoot) {
        /*
         * 背景：工作区规模和内容不受服务控制，递归或无界扫描会拖慢请求并向模型暴露过多目录信息。
         * 设计意图：只读取根目录，按稳定顺序输出，并同时限制条目数和序列化字符数。
         * 关键约束：不得改为递归扫描或跟随链接；结果必须维持 200 个条目和 20000 个字符的上限。
         */
        final Path root;
        try {
            root = pathGuard.canonicalDirectory(workspaceRoot);
        } catch (IOException | RuntimeException exception) {
            return ToolOutcome.failure(objectMapper,
                    "WORKSPACE_UNAVAILABLE",
                    "无法安全读取所选工作区",
                    "The selected workspace could not be listed safely.");
        }

        try {
            List<Entry> scannedEntries = new ArrayList<>();
            try (var stream = Files.list(root)) {
                stream.limit(MAX_ENTRIES + 1L).forEach(path -> scannedEntries.add(entry(path)));
            }
            List<Entry> entries = scannedEntries;
            boolean truncated = entries.size() > MAX_ENTRIES;
            if (truncated) entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
            entries.sort(Comparator
                    .comparingInt((Entry entry) -> kindOrder(entry.kind()))
                    .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Entry::name));

            while (true) {
                String content = objectMapper.writeValueAsString(new Overview(true, entries, truncated));
                if (content.length() <= MAX_RESULT_CHARS || entries.isEmpty()) {
                    String detail = presentation(entries, truncated);
                    return new ToolOutcome(true, content, detail);
                }
                entries.remove(entries.size() - 1);
                truncated = true;
            }
        } catch (IOException | RuntimeException exception) {
            return ToolOutcome.failure(objectMapper,
                    "WORKSPACE_PERMISSION_DENIED",
                    "无法安全读取所选工作区",
                    "The selected workspace could not be listed safely.");
        }
    }

    private Entry entry(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        if (Files.isSymbolicLink(path)) return new Entry(name, "link");
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) return new Entry(name, "directory");
            if (attributes.isRegularFile()) return new Entry(name, "file");
            return new Entry(name, "link");
        } catch (IOException | SecurityException exception) {
            return new Entry(name, "link");
        }
    }

    private int kindOrder(String kind) {
        return switch (kind) {
            case "directory" -> 0;
            case "file" -> 1;
            default -> 2;
        };
    }

    private String presentation(List<Entry> entries, boolean truncated) {
        long directories = entries.stream().filter(entry -> "directory".equals(entry.kind())).count();
        long files = entries.stream().filter(entry -> "file".equals(entry.kind())).count();
        long links = entries.stream().filter(entry -> "link".equals(entry.kind())).count();
        StringBuilder detail = new StringBuilder("发现 ")
                .append(directories).append(" 个目录、")
                .append(files).append(" 个文件");
        if (links > 0) detail.append("、").append(links).append(" 个链接");
        if (truncated) detail.append("；结果已截断");
        return detail.toString();
    }

    private record Entry(String name, String kind) {
    }

    private record Overview(boolean ok, List<Entry> entries, boolean truncated) {
    }
}
