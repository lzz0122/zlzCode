package com.zlzcode.agent.workspace;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    public boolean samePath(Path left, Path right) {
        return canonicalKey(left).equals(canonicalKey(right));
    }

    public String canonicalKey(Path root) {
        String value = root.toAbsolutePath().normalize().toString();
        return isWindows() ? value.toLowerCase(Locale.ROOT) : value;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
