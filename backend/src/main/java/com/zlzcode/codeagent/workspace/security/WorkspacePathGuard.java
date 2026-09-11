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
        Path target = resolveRelative(root, relativePath);

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

    /*
     * 背景：write 创建文件时末级目标尚不存在，但父目录和已有路径段仍必须处于授权工作区且不能经过链接。
     * 设计意图：复用现有路径边界校验，只允许缺少最后一个路径项，不为修改工具放宽父目录检查。
     * 关键约束：不能允许缺失父目录、链接父目录或绝对路径；否则提交阶段可能越界或隐式创建未审批目录。
     */
    public Path resolveMutationTarget(Path workspaceRoot, String relativePath) throws IOException {
        Path root = canonicalDirectory(workspaceRoot);
        Path target = resolveRelative(root, relativePath);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return resolveExisting(root, relativePath);
        }

        Path parent = target.getParent();
        if (parent == null || !inside(root, parent)) {
            throw new IllegalArgumentException("workspace path leaves the workspace");
        }
        String relativeParent = root.equals(parent) ? "" : root.relativize(parent).toString();
        Path existingParent = resolveExisting(root, relativeParent);
        if (!Files.isDirectory(existingParent, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileSystemException(relativePath, null, "parent is not a directory");
        }
        return target;
    }

    /*
     * 背景：mkdir(parents=true) 的多个尾部目录在准备阶段都可能不存在，但已有祖先仍属于工作区安全边界。
     * 设计意图：逐段校验所有已存在路径并允许从首个缺失段开始待创建，而不是让 mkdir 绕过统一路径授权。
     * 关键约束：已有段不能是符号链接或非目录；否则后续逐级创建可能越过工作区，或把审批范围落到错误位置。
     */
    public Path resolveCreationTarget(Path workspaceRoot, String relativePath) throws IOException {
        Path root = canonicalDirectory(workspaceRoot);
        Path target = resolveRelative(root, relativePath);
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(current)) {
                throw new FileSystemException(relativePath, null, "symbolic links are not supported");
            }
            if (!current.equals(target) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileSystemException(relativePath, null, "path ancestor is not a directory");
            }
        }
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

    private Path resolveRelative(Path root, String relativePath) {
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
        return target;
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
