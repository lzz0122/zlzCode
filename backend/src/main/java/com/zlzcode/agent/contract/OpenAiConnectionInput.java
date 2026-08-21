package com.zlzcode.agent.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class OpenAiConnectionInput {

    @NotBlank
    @Size(max = 2048)
    private final String baseUrl;

    @NotBlank
    @Size(max = 4096)
    private final String apiKey;

    @JsonCreator
    public OpenAiConnectionInput(
            @JsonProperty("baseUrl") String baseUrl,
            @JsonProperty("apiKey") String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @JsonProperty("baseUrl")
    public String baseUrl() {
        return baseUrl;
    }

    @JsonProperty("apiKey")
    public String apiKey() {
        return apiKey;
    }

    public URI normalizedBaseUri() {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw invalidBaseUrl();
            }
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalidBaseUrl();
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalidBaseUrl();
        }
    }

    public String normalizedApiKey() {
        String value = apiKey == null ? "" : apiKey.trim();
        if (value.isEmpty()) {
            throw new RequestContractException("请求结构无效");
        }
        return value;
    }

    private RequestContractException invalidBaseUrl() {
        return new RequestContractException("请求结构无效");
    }

    @Override
    public String toString() {
        return "OpenAiConnectionInput[baseUrl=" + baseUrl + ", apiKey=<redacted>]";
    }
}
