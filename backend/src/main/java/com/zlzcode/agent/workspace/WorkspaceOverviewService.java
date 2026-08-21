package com.zlzcode.agent.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class WorkspaceOverviewService {

    private static final int MAX_ENTRIES = 200;
    private static final int MAX_RESULT_CHARS = 20_000;

    private final ObjectMapper objectMapper;

    public WorkspaceOverviewService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Result list(String workspacePath) {
        final Path root;
        try {
            String value = workspacePath == null ? "" : workspacePath.trim();
            if (value.isEmpty()) return failure("WORKSPACE_UNAVAILABLE", "无法安全读取所选工作区");
            root = Path.of(value).toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (InvalidPathException | IOException | SecurityException exception) {
            return failure("WORKSPACE_UNAVAILABLE", "无法安全读取所选工作区");
        }

        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(root)) {
                return failure("WORKSPACE_UNAVAILABLE", "无法安全读取所选工作区");
            }

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
                    return new Result(true, content, detail);
                }
                entries.remove(entries.size() - 1);
                truncated = true;
            }
        } catch (IOException | RuntimeException exception) {
            return failure("WORKSPACE_PERMISSION_DENIED", "无法安全读取所选工作区");
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

    private Result failure(String code, String presentation) {
        try {
            String content = objectMapper.writeValueAsString(
                    Map.of("ok", false, "error", Map.of("code", code,
                            "message", "The selected workspace could not be listed safely.")));
            return new Result(false, content, presentation);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码工作区工具结果", exception);
        }
    }

    public record Result(boolean ok, String modelContent, String presentation) {
    }

    private record Entry(String name, String kind) {
    }

    private record Overview(boolean ok, List<Entry> entries, boolean truncated) {
    }
}
