package com.zlzcode.codeagent.openai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zlzcode.codeagent.openai.client.OpenAiTransportClient;
import com.zlzcode.codeagent.openai.dto.OpenAiConnectionInput;
import com.zlzcode.codeagent.openai.dto.OpenAiModel;
import com.zlzcode.codeagent.openai.dto.OpenAiModelListResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
public class ModelDiscoveryService {

    private final OpenAiTransportClient transportClient;

    public ModelDiscoveryService(OpenAiTransportClient transportClient) {
        this.transportClient = transportClient;
    }

    public Mono<OpenAiModelListResponse> listModels(OpenAiConnectionInput connection) {
        return transportClient.getJson(
                        connection, "/models", Duration.ofSeconds(15), this::statusError)
                .map(this::parseModels)
                .onErrorMap(error -> {
                    if (error instanceof ModelDiscoveryException) {
                        return error;
                    }
                    if (error instanceof TimeoutException) {
                        return new ModelDiscoveryException(504, "获取模型超时", true);
                    }
                    if (error instanceof WebClientRequestException) {
                        return new ModelDiscoveryException(502, "无法连接 OpenAI Base URL", true);
                    }
                    return new ModelDiscoveryException(502, "模型服务返回了无法识别的响应", false);
                });
    }

    private RuntimeException statusError(int value) {
        if (value == 401 || value == 403) {
            return new ModelDiscoveryException(401, "API Key 无效或没有获取模型权限", false);
        }
        if (value == 404) {
            return new ModelDiscoveryException(400, "Base URL 不支持模型列表接口", false);
        }
        if (value == 429) {
            return new ModelDiscoveryException(429, "模型服务请求过于频繁或额度不足", true);
        }
        if (value >= 300 && value < 400) {
            return new ModelDiscoveryException(502, "模型服务返回了不受支持的重定向", true);
        }
        if (value >= 500) {
            return new ModelDiscoveryException(502, "模型服务暂时不可用", true);
        }
        return new ModelDiscoveryException(502, "获取模型失败，请检查 Base URL 和 API Key", false);
    }

    private OpenAiModelListResponse parseModels(JsonNode payload) {
        JsonNode data = payload == null ? null : payload.get("data");
        if (data == null || !data.isArray()) {
            throw new ModelDiscoveryException(502, "模型服务返回的模型列表格式不兼容", false);
        }

        Map<String, OpenAiModel> modelsById = new HashMap<>();
        for (JsonNode item : data) {
            if (!item.isObject()) {
                continue;
            }
            JsonNode rawId = item.get("id");
            if (rawId == null || !rawId.isTextual() || rawId.asText().trim().isEmpty()) {
                continue;
            }
            String id = rawId.asText().trim();
            if (id.length() > 256) {
                continue;
            }

            JsonNode rawOwner = item.get("owned_by");
            String owner = rawOwner != null && rawOwner.isTextual() && !rawOwner.asText().trim().isEmpty()
                    ? rawOwner.asText().trim()
                    : null;
            if (owner != null && owner.length() > 128) {
                owner = owner.substring(0, 128);
            }

            OpenAiModel current = modelsById.get(id);
            if (current == null || (current.ownedBy() == null && owner != null)) {
                modelsById.put(id, new OpenAiModel(id, owner));
            }
        }

        if (data.size() > 0 && modelsById.isEmpty()) {
            throw new ModelDiscoveryException(502, "模型服务返回的模型列表格式不兼容", false);
        }

        List<OpenAiModel> models = new ArrayList<>(modelsById.values());
        models.sort(Comparator.comparing(OpenAiModel::id, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(OpenAiModel::id));
        return new OpenAiModelListResponse(models);
    }

    public static final class ModelDiscoveryException extends RuntimeException {
        private final int status;
        private final String safeMessage;
        private final boolean retryable;

        public ModelDiscoveryException(int status, String safeMessage, boolean retryable) {
            super(safeMessage);
            this.status = status;
            this.safeMessage = safeMessage;
            this.retryable = retryable;
        }

        public int status() {
            return status;
        }

        public String safeMessage() {
            return safeMessage;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
