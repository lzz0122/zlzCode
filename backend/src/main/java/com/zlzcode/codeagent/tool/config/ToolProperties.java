package com.zlzcode.codeagent.tool.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/*
 * 背景：工具声明在启动时缓存，后续工具需要从同一份配置取得默认值和执行上限。
 * 设计意图：使用不可变分组并在启动时完整校验，而不是由各消费者分别补默认值或修正错误。
 * 关键约束：嵌套分组即使没有消费者也必须校验，未知配置项不得忽略；否则拼写错误或后续接入会改变实际限制。
 */
@Validated
@ConfigurationProperties(prefix = "codeagent.tool", ignoreUnknownFields = false)
public record ToolProperties(
        @Valid @NotNull @DefaultValue Read read,
        @Valid @NotNull @DefaultValue Glob glob,
        @Valid @NotNull @DefaultValue Grep grep,
        @Valid @NotNull @DefaultValue Mutation mutation,
        @NotNull @DefaultValue("PT10S") Duration executionTimeout) {

    @AssertTrue(message = "codeagent.tool.execution-timeout must be greater than zero")
    public boolean isExecutionTimeoutPositive() {
        return executionTimeout == null || executionTimeout.compareTo(Duration.ZERO) > 0;
    }

    public record Read(
            @Positive @DefaultValue("2000") int defaultLimit,
            @Positive @DefaultValue("2000") int maxLimit,
            @Positive @DefaultValue("2000") int maxLineLength,
            @Positive @DefaultValue("20000") int maxResultChars,
            @Positive @DefaultValue("10485760") long maxFileBytes) {

        @AssertTrue(message = "codeagent.tool.read.default-limit must not exceed codeagent.tool.read.max-limit")
        public boolean isDefaultLimitWithinMaxLimit() {
            return defaultLimit <= maxLimit;
        }
    }

    public record Glob(
            @Positive @DefaultValue("100") int maxResults,
            @Positive @DefaultValue("50") int maxDepth,
            @Positive @DefaultValue("20000") int maxResultChars) {
    }

    public record Grep(
            @Positive @DefaultValue("250") int maxMatches,
            @Positive @DefaultValue("2000") int maxLinePreviewBytes,
            @Positive @DefaultValue("20000") int maxResultChars,
            @Positive @DefaultValue("10485760") long maxFileBytes) {
    }

    public record Mutation(
            @Positive @DefaultValue("10485760") long maxFileBytes,
            @NotNull @DefaultValue("PT10M") Duration approvalTimeout,
            @Positive @DefaultValue("10485760") int maxContentChars,
            @Positive @DefaultValue("20000") int maxPreviewChars) {

        @AssertTrue(message = "codeagent.tool.mutation.approval-timeout must be greater than zero")
        public boolean isApprovalTimeoutPositive() {
            return approvalTimeout == null || approvalTimeout.compareTo(Duration.ZERO) > 0;
        }
    }
}
