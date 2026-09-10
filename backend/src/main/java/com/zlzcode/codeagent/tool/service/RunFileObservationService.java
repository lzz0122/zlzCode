package com.zlzcode.codeagent.tool.service;

import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class RunFileObservationService {

    private final Map<ObservationKey, String> observations = new ConcurrentHashMap<>();

    /*
     * 背景：覆盖和编辑只能基于当前 Run 已读取的文件版本，不能继承其他 Run 或工作区的读取事实。
     * 设计意图：用 Run、工作区和规范相对路径组成内存键，复用同一组件计算原始字节 SHA-256。
     * 关键约束：观察版本不能持久化或只按路径保存；否则重启或其他 Run 可能错误继承修改资格。
     */
    public String record(ToolExecutionContext context, String relativePath, byte[] bytes) {
        String sha256 = sha256(bytes);
        observations.put(key(context, relativePath), sha256);
        return sha256;
    }

    public Optional<String> find(ToolExecutionContext context, String relativePath) {
        return Optional.ofNullable(observations.get(key(context, relativePath)));
    }

    public void forget(ToolExecutionContext context, String relativePath) {
        observations.remove(key(context, relativePath));
    }

    public void clear(String runId) {
        if (runId == null || runId.isBlank()) return;
        observations.keySet().removeIf(key -> key.runId().equals(runId));
    }

    public String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private ObservationKey key(ToolExecutionContext context, String relativePath) {
        if (context == null || relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File observation identity cannot be blank");
        }
        return new ObservationKey(context.runId(), context.workspace().id(), relativePath);
    }

    private record ObservationKey(String runId, String workspaceId, String relativePath) {
    }
}
