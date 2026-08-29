package com.zlzcode.codeagent.openai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zlzcode.codeagent.openai.client.OpenAiTransportClient;
import com.zlzcode.codeagent.openai.dto.OpenAiConnectionInput;
import com.zlzcode.codeagent.openai.dto.OpenAiModel;
import com.zlzcode.codeagent.openai.dto.OpenAiModelListResponse;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelDiscoveryService {

    private final OpenAiTransportClient transportClient;

    public ModelDiscoveryService(OpenAiTransportClient transportClient) {
        this.transportClient = transportClient;
    }

    public Mono<OpenAiModelListResponse> listModels(OpenAiConnectionInput connection) {
        return transportClient.getJson(
                        connection, "/models", Duration.ofSeconds(15),
                        OpenAiIntegrationException::fromModelDiscoveryStatus)
                .map(this::parseModels)
                .onErrorMap(OpenAiIntegrationException::fromModelDiscoveryThrowable);
    }

    private OpenAiModelListResponse parseModels(JsonNode payload) {
        JsonNode data = payload == null ? null : payload.get("data");
        if (data == null || !data.isArray()) {
            throw OpenAiIntegrationException.modelDiscoveryInvalidResponse();
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
            throw OpenAiIntegrationException.modelDiscoveryInvalidResponse();
        }

        List<OpenAiModel> models = new ArrayList<>(modelsById.values());
        models.sort(Comparator.comparing(OpenAiModel::id, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(OpenAiModel::id));
        return new OpenAiModelListResponse(models);
    }

}
