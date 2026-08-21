package com.zlzcode.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.agent.contract.ApiErrorDetail;
import com.zlzcode.agent.contract.ApiErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoopbackAccessFilter implements WebFilter {

    private final ObjectMapper objectMapper;

    public LoopbackAccessFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/")) {
            return chain.filter(exchange);
        }
        if (isLoopback(exchange.getRequest())) return chain.filter(exchange);

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        try {
            byte[] body = objectMapper.writeValueAsBytes(new ApiErrorResponse(
                    new ApiErrorDetail("LOCAL_ACCESS_REQUIRED", "只能从本机回环地址访问该接口", false)));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (Exception exception) {
            return response.setComplete();
        }
    }

    private boolean isLoopback(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) return false;
        InetAddress address = remote.getAddress();
        if (address != null) return address.isLoopbackAddress();
        return "localhost".equalsIgnoreCase(remote.getHostString());
    }
}
