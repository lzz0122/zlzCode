package com.zlzcode.codeagent.tool.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ShortNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MutationPlan(
        String runId,
        String toolCallId,
        String toolName,
        MutationOperation operation,
        List<String> relativePaths,
        JsonNode payload,
        String observedSha256,
        boolean targetExisted,
        String presentationSummary) {

    private static final Set<Class<?>> JSON_NODE_CLASSES = Set.of(
            ObjectNode.class, ArrayNode.class, TextNode.class, BooleanNode.class, NullNode.class,
            ShortNode.class, IntNode.class, LongNode.class, BigIntegerNode.class,
            DecimalNode.class, FloatNode.class, DoubleNode.class);

    public MutationPlan {
        requireNonBlank(runId, "Mutation run ID");
        requireNonBlank(toolCallId, "Mutation call ID");
        requireNonBlank(toolName, "Mutation tool name");
        requireNonBlank(presentationSummary, "Mutation presentation summary");
        Objects.requireNonNull(operation, "Mutation operation cannot be null");
        Objects.requireNonNull(relativePaths, "Mutation paths cannot be null");
        if (relativePaths.isEmpty()) {
            throw new IllegalArgumentException("Mutation paths cannot be empty");
        }
        for (String path : relativePaths) {
            requireNonBlank(path, "Mutation path");
        }
        relativePaths = List.copyOf(relativePaths);

        /*
         * 背景：move 的摘要属于源文件，而 targetExisted 描述目标，其他操作的字段关联也各不相同。
         * 设计意图：公共模型只校验结构，操作关联与路径授权留给 Handler，避免按目标存在性推导摘要。
         * 关键约束：这里不能访问磁盘、归一化路径或要求不存在的目标必须无摘要，否则会拒绝合法 move 计划。
         */
        if (observedSha256 != null && !observedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Observed SHA-256 must contain 64 hexadecimal characters");
        }

        /*
         * 背景：审批展示后还会持久化和提交同一计划，调用方持有的 JsonNode 可能继续变化。
         * 设计意图：只接受普通 JSON 树并在构造和读取时复制，而不是仅保存 JsonNode 引用或直接信任 deepCopy。
         * 关键约束：包装 Java 对象、二进制和自定义节点不能进入计划；它们可能保留可变引用，使批准内容发生变化。
         */
        Objects.requireNonNull(payload, "Mutation payload cannot be null");
        if (payload.getClass() != ObjectNode.class) {
            throw new IllegalArgumentException("Mutation payload must be a standard JSON object");
        }
        validateJsonTree(payload, Collections.newSetFromMap(new IdentityHashMap<>()));
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    private static void validateJsonTree(JsonNode node, Set<JsonNode> ancestors) {
        if (node == null || !JSON_NODE_CLASSES.contains(node.getClass())) {
            throw new IllegalArgumentException("Mutation payload must contain only standard JSON values");
        }
        if ((node.getClass() == DoubleNode.class || node.getClass() == FloatNode.class)
                && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("Mutation payload numbers must be finite JSON values");
        }
        if (node.isContainerNode()) {
            /*
             * 背景：内存中的 ObjectNode/ArrayNode 可被调用方连成环，但固定计划要求可序列化的 JSON 树。
             * 设计意图：按对象身份跟踪当前祖先链，而不是用结构相等判断，允许不同字段复用同一无环子树。
             * 关键约束：环必须在 deepCopy 前拒绝，否则复制无法结束；不能用全局去重误拒绝合法共享子树。
             */
            if (!ancestors.add(node)) {
                throw new IllegalArgumentException("Mutation payload cannot contain cycles");
            }
            for (JsonNode child : node) {
                validateJsonTree(child, ancestors);
            }
            ancestors.remove(node);
        }
    }
}
