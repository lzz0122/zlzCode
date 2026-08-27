package com.zlzcode.agent.workspace.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkspaceRegistry {

    private static final int STATE_VERSION = 1;
    private static final int MAX_STATE_BYTES = 256_000;
    private static final int MAX_WORKSPACES = 256;

    private final ObjectMapper objectMapper;
    private final WorkspacePathGuard pathGuard;
    private final Path stateFile;
    private final Map<String, AuthorizedWorkspace> workspaces = new ConcurrentHashMap<>();

    @Autowired
    public WorkspaceRegistry(
            ObjectMapper objectMapper,
            WorkspacePathGuard pathGuard,
            @Value("${codeagent.workspace.state-file:}") String configuredStateFile) {
        this(objectMapper, pathGuard, configuredStateFile == null || configuredStateFile.isBlank()
                ? defaultStateFile()
                : Path.of(configuredStateFile.trim()));
    }

    public WorkspaceRegistry(ObjectMapper objectMapper, WorkspacePathGuard pathGuard, Path stateFile) {
        this.objectMapper = objectMapper;
        this.pathGuard = pathGuard;
        this.stateFile = stateFile;
        loadState();
    }

    public AuthorizedWorkspace register(Path selected) {
        Path root = canonicalDirectory(selected, "WORKSPACE_UNAVAILABLE", "选择的目录无法访问");
        String id = workspaceId(root);
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        AuthorizedWorkspace workspace = new AuthorizedWorkspace(id, name, root);
        AuthorizedWorkspace existing = workspaces.get(id);
        if (existing != null && !samePath(existing.root(), root)) {
            throw new WorkspaceRegistryException(
                    "WORKSPACE_ID_COLLISION", "工作区标识冲突，请重新选择目录", false);
        }
        Map<String, AuthorizedWorkspace> updated = new LinkedHashMap<>(workspaces);
        updated.put(id, workspace);
        persist(updated);
        workspaces.clear();
        workspaces.putAll(updated);
        return workspace;
    }

    public AuthorizedWorkspace resolve(String id, String claimedPath) {
        if (id == null || id.isBlank() || claimedPath == null || claimedPath.isBlank()) {
            throw notRegistered();
        }
        AuthorizedWorkspace workspace = workspaces.get(id);
        if (workspace == null) throw notRegistered();
        Path claimed = canonicalDirectory(
                Path.of(claimedPath.trim()),
                "WORKSPACE_NOT_REGISTERED", "工作区授权已失效，请重新选择目录");
        if (!samePath(workspace.root(), claimed)) throw notRegistered();
        return workspace;
    }

    /*
     * 背景：本地授权状态可能因中断、手工修改或目录迁移而损坏，不能把文件内容直接视为可信授权。
     * 设计意图：先校验文件边界和版本，再逐条重新规范化路径；无效条目单独丢弃，整体异常则失败关闭。
     * 关键约束：任何解析或路径校验失败都不能新增授权，也不能根据不可信内容猜测或修复授权路径。
     */
    private void loadState() {
        try {
            if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(stateFile)) return;
            byte[] raw = Files.readAllBytes(stateFile);
            if (raw.length > MAX_STATE_BYTES) return;
            JsonNode root = objectMapper.readTree(raw);
            if (root == null || root.path("version").asInt(-1) != STATE_VERSION) return;
            JsonNode entries = root.get("workspaces");
            if (entries == null || !entries.isArray() || entries.size() > MAX_WORKSPACES) return;
            for (JsonNode entry : entries) {
                String id = text(entry, "id");
                String rootValue = text(entry, "root");
                if (id == null || rootValue == null) continue;
                try {
                    Path canonical = canonicalDirectory(
                            Path.of(rootValue), "WORKSPACE_NOT_REGISTERED", "工作区授权已失效，请重新选择目录");
                    if (id.equals(workspaceId(canonical))) {
                        String name = canonical.getFileName() == null
                                ? canonical.toString() : canonical.getFileName().toString();
                        workspaces.put(id, new AuthorizedWorkspace(id, name, canonical));
                    }
                } catch (RuntimeException ignored) {
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /*
     * 背景：直接覆盖授权文件时若进程中断，可能留下半写入状态并导致后续授权全部失效。
     * 设计意图：先在目标目录完整写入临时文件，再替换正式文件，而不是边序列化边覆盖目标。
     * 关键约束：序列化完成前不得写入正式文件；无论替换是否成功，临时文件都必须在 finally 中清理。
     */
    private void persist(Map<String, AuthorizedWorkspace> values) {
        if (values.size() > MAX_WORKSPACES) {
            throw new WorkspaceRegistryException(
                    "WORKSPACE_STATE_PERSISTENCE_FAILED", "无法保存工作区授权，请重试", true);
        }
        List<Map<String, String>> entries = values.values().stream()
                .sorted(Comparator.comparing(AuthorizedWorkspace::id))
                .map(workspace -> Map.of("id", workspace.id(), "root", workspace.root().toString()))
                .toList();
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(
                    Map.of("version", STATE_VERSION, "workspaces", entries));
            if (encoded.length > MAX_STATE_BYTES) {
                throw new WorkspaceRegistryException(
                        "WORKSPACE_STATE_PERSISTENCE_FAILED", "无法保存工作区授权，请重试", true);
            }
            Path parent = stateFile.toAbsolutePath().normalize().getParent();
            if (parent == null) throw new IOException("state file has no parent");
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".authorized-workspaces-", ".tmp");
            try {
                Files.write(temporary, encoded);
                try {
                    Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (WorkspaceRegistryException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new WorkspaceRegistryException(
                    "WORKSPACE_STATE_PERSISTENCE_FAILED", "无法保存工作区授权，请重试", true);
        }
    }

    private Path canonicalDirectory(Path value, String code, String message) {
        try {
            return pathGuard.canonicalDirectory(value);
        } catch (WorkspaceRegistryException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new WorkspaceRegistryException(code, message, false);
        }
    }

    private boolean samePath(Path left, Path right) {
        return pathGuard.samePath(left, right);
    }

    private String workspaceId(Path root) {
        return "workspace-" + sha256(pathGuard.canonicalKey(root)).substring(0, 16);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText() : null;
    }

    private WorkspaceRegistryException notRegistered() {
        return new WorkspaceRegistryException(
                "WORKSPACE_NOT_REGISTERED", "工作区授权已失效，请重新选择目录", false);
    }

    private static Path defaultStateFile() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                : Path.of(localAppData);
        return base.resolve("zlz-code-agent").resolve("authorized-workspaces.json");
    }
}
