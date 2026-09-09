package com.zlzcode.codeagent.tool.service;

import com.zlzcode.codeagent.workspace.security.WorkspacePathGuard;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class FileSearchService {

    private final WorkspacePathGuard pathGuard;

    public FileSearchService(WorkspacePathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    /*
     * 背景：glob 与 grep 需要共享相同的搜索根、忽略规则和稳定路径顺序，分别遍历会产生行为分叉。
     * 设计意图：用一个具体服务承载首版 NIO 顺序扫描，不预建可替换搜索后端或并发调度抽象。
     * 关键约束：扫描不得跟随链接且必须永久跳过 .git；放宽任一约束都会越过工作区边界或暴露内部仓库数据。
     */
    public SearchSelection files(
            Path workspaceRoot,
            String requestedPath,
            String includePattern,
            boolean force,
            int maxDepth) throws IOException {
        Path root = pathGuard.canonicalDirectory(workspaceRoot);
        Path selected = pathGuard.resolveExisting(root, requestedPath == null ? "" : requestedPath);
        IgnoreRules ignoreRules = force ? IgnoreRules.empty() : IgnoreRules.load(root);
        GlobMatcher include = includePattern == null ? null : GlobMatcher.compile(includePattern);

        if (Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)) {
            String workspaceRelative = pathGuard.relativePath(root, selected);
            String matchPath = selected.getFileName() == null ? workspaceRelative : selected.getFileName().toString();
            if (containsGitSegment(workspaceRelative)
                    || ignoreRules.ignored(workspaceRelative, false)
                    || include != null && !include.matches(normalize(matchPath))) {
                return new SearchSelection(true, false, List.of());
            }
            return new SearchSelection(true, false, List.of(new SearchFile(selected, workspaceRelative)));
        }
        if (!Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileSystemException(requestedPath, null, "path is not a file or directory");
        }

        List<SearchFile> matches = new ArrayList<>();
        boolean[] incomplete = {false};
        Files.walkFileTree(selected, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                            throws IOException {
                        if (directory.equals(selected)) return FileVisitResult.CONTINUE;
                        String relative = pathGuard.relativePath(root, directory);
                        if (containsGitSegment(relative) || ignoreRules.ignored(relative, true)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                        String workspaceRelative = pathGuard.relativePath(root, file);
                        if (containsGitSegment(workspaceRelative)
                                || ignoreRules.ignored(workspaceRelative, false)) return FileVisitResult.CONTINUE;
                        String relativeToSearchRoot = normalize(selected.relativize(file).toString());
                        if (include == null || include.matches(relativeToSearchRoot)) {
                            matches.add(new SearchFile(file, workspaceRelative));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exception) {
                        incomplete[0] = true;
                        return FileVisitResult.CONTINUE;
                    }
                });
        matches.sort(Comparator.comparing(SearchFile::relativePath));
        return new SearchSelection(false, incomplete[0], List.copyOf(matches));
    }

    private static boolean containsGitSegment(String path) {
        for (String segment : path.split("/")) {
            if (".git".equals(segment)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value.replace('\\', '/');
    }

    public record SearchFile(Path path, String relativePath) {
    }

    public record SearchSelection(boolean directFile, boolean incomplete, List<SearchFile> files) {
    }

    private record IgnoreRule(GlobMatcher matcher, boolean negated, boolean directoryOnly) {

        boolean matches(String relativePath, boolean directory) {
            return matcher.matches(relativePath);
        }
    }

    private record IgnoreRules(List<IgnoreRule> rules) {

        static IgnoreRules empty() {
            return new IgnoreRules(List.of());
        }

        static IgnoreRules load(Path root) {
            List<IgnoreRule> rules = new ArrayList<>();
            addRules(root.resolve(".gitignore"), rules);
            addRules(root.resolve(".ignore"), rules);
            return new IgnoreRules(List.copyOf(rules));
        }

        private static void addRules(Path file, List<IgnoreRule> rules) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return;
            try {
                for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String line = rawLine.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    boolean negated = line.startsWith("!");
                    if (negated) line = line.substring(1);
                    boolean directoryOnly = line.endsWith("/");
                    if (directoryOnly) line = line.substring(0, line.length() - 1);
                    if (line.startsWith("/")) line = line.substring(1);
                    if (!line.isEmpty()) {
                        rules.add(new IgnoreRule(GlobMatcher.compileIgnore(line, directoryOnly),
                                negated, directoryOnly));
                    }
                }
            } catch (IOException ignored) {
                // Happy Path：无法读取根 ignore 文件时继续普通搜索，不引入独立恢复或诊断通道。
            }
        }

        boolean ignored(String relativePath, boolean directory) {
            boolean ignored = false;
            for (IgnoreRule rule : rules) {
                if (rule.matches(relativePath, directory)) ignored = !rule.negated();
            }
            return ignored;
        }
    }

    private record GlobMatcher(Pattern pattern) {

        static GlobMatcher compile(String expression) {
            String value = normalize(expression);
            while (value.startsWith("/")) value = value.substring(1);
            return new GlobMatcher(Pattern.compile("^" + globBody(value) + "$"));
        }

        static GlobMatcher compileIgnore(String expression, boolean directoryOnly) {
            String value = normalize(expression);
            boolean containsSlash = value.contains("/");
            String prefix = containsSlash ? "^" : "^(?:.*/)?";
            String suffix = directoryOnly ? "(?:/.*)?$" : "$";
            return new GlobMatcher(Pattern.compile(prefix + globBody(value) + suffix));
        }

        boolean matches(String value) {
            return pattern.matcher(normalize(value)).matches();
        }

        private static String globBody(String value) {
            StringBuilder regex = new StringBuilder();
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '*') {
                    boolean doubleStar = index + 1 < value.length() && value.charAt(index + 1) == '*';
                    if (doubleStar) {
                        index++;
                        if (index + 1 < value.length() && value.charAt(index + 1) == '/') {
                            index++;
                            regex.append("(?:.*/)?");
                        } else {
                            regex.append(".*");
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                } else if (current == '?') {
                    regex.append("[^/]");
                } else {
                    if ("\\.[]{}()+-^$|".indexOf(current) >= 0) regex.append('\\');
                    regex.append(current);
                }
            }
            return regex.toString();
        }
    }
}
