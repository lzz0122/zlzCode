package com.zlzcode.codeagent.agent.service;

import com.zlzcode.codeagent.tool.model.MutationPlan;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import reactor.core.publisher.Mono;

@FunctionalInterface
interface ToolApprovalCoordinator {

    Mono<ToolOutcome> awaitOutcome(
            ToolExecutionContext context,
            MutationPlan plan);
}
