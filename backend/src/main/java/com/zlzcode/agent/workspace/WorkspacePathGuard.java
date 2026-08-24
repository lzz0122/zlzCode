package com.zlzcode.agent.workspace;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

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
