package com.zlzcode.codeagent.agent.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
final class AgentRunScheduler {

    @Value("${codeagent.run.max-concurrency:4}")
    private int maxConcurrency;

    @Value("${codeagent.run.queue-capacity:100}")
    private int queueCapacity;

    private final Map<String, CompletableFuture<Void>> sessionExecutionTails =
            new ConcurrentHashMap<>();
    private ExecutorService runExecutor;

    @PostConstruct
    void start() {
        runExecutor = new ThreadPoolExecutor(
                maxConcurrency,
                maxConcurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void stop() {
        if (runExecutor != null) runExecutor.shutdownNow();
    }

    /*
     * 背景：同一 Session 的多个 Run 共享已完成历史，后提交的 Run 必须等前一个 Run 完整收口后再读取历史。
     * 设计意图：每个 Session 只保存最后一个完成信号，新任务链接到该信号后面；不同 Session 仍共用有界线程池并行。
     * 关键约束：调度器只维护执行顺序，不能持久化 Run 状态或映射业务错误，否则生命周期会分散到两个模块。
     */
    synchronized CompletableFuture<Void> schedule(
            String sessionId,
            Supplier<? extends CompletionStage<Void>> task) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null");
        Objects.requireNonNull(task, "Run task cannot be null");
        CompletableFuture<Void> previous = sessionExecutionTails.getOrDefault(
                sessionId, CompletableFuture.completedFuture(null));
        final CompletableFuture<Void> current;
        try {
            current = previous.handle((ignored, error) -> null)
                    .thenComposeAsync(ignored -> task.get(), runExecutor);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        sessionExecutionTails.put(sessionId, current);
        current.whenComplete((ignored, error) ->
                sessionExecutionTails.remove(sessionId, current));
        return current;
    }
}
