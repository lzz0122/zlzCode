package com.zlzcode.codeagent.workspace.security;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 背景：仅做字符串规范化无法识别符号链接跳转，也无法处理 Windows 路径大小写不敏感的比较规则。
 * 设计意图：用真实路径建立授权根并拒绝根符号链接，再通过平台相关的规范键比较路径身份。
 * 关键约束：授权判断不得退化为 Path.normalize 的词法比较；Windows 规范键必须保持大小写折叠。
 */
@Component
public class WorkspacePathGuard {

    public Path canonicalDirectory(Path value) throws IOException {
        if (value == null) throw new IOException("workspace path is missing");
        Path root = value.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IOException("workspace path is not a regular directory");
        }
        return root;
    }

    /*
     * 背景：模型提供的文件路径不能直接交给 NIO，否则绝对路径、父目录跳转或链接可能越过已授权工作区。
     * 设计意图：在统一安全边界内完成相对路径解析并拒绝链接，而不是让每个文件工具复制一套词法判断。
     * 关键约束：不能改成单纯的 root.resolve(...).normalize()；缺少链接检查或根边界复核会允许读取工作区外文件。
     */
    public Path resolveExisting(Path workspaceRoot, String relativePath) throws IOException {
        Path root = canonicalDirectory(workspaceRoot);
        final Path supplied;
        try {
            supplied = Path.of(relativePath == null ? "" : relativePath);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("workspace path is invalid", exception);
        }
        if (supplied.isAbsolute()) throw new IllegalArgumentException("workspace path must be relative");
        for (Path segment : supplied) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException("workspace path cannot contain parent traversal");
            }
        }

        Path target = root.resolve(supplied).normalize();
        if (!inside(root, target)) throw new IllegalArgumentException("workspace path leaves the workspace");

        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new FileSystemException(relativePath, null, "symbolic links are not supported");
            }
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(relativePath);
        return target;
    }

    public String relativePath(Path workspaceRoot, Path value) throws IOException {
        Path root = canonicalDirectory(workspaceRoot);
        Path target = value.toAbsolutePath().normalize();
        if (!inside(root, target)) throw new IllegalArgumentException("path leaves the workspace");
        return root.relativize(target).toString().replace('\\', '/');
    }

    public boolean samePath(Path left, Path right) {
        return canonicalKey(left).equals(canonicalKey(right));
    }

    public String canonicalKey(Path root) {
        String value = root.toAbsolutePath().normalize().toString();
        return isWindows() ? value.toLowerCase(Locale.ROOT) : value;
    }

    private boolean inside(Path root, Path target) {
        String rootKey = canonicalKey(root);
        String targetKey = canonicalKey(target);
        return targetKey.equals(rootKey) || targetKey.startsWith(rootKey + java.io.File.separator);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
