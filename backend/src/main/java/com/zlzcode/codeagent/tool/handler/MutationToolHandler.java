package com.zlzcode.codeagent.tool.handler;

import com.zlzcode.codeagent.tool.model.MutationPlan;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import reactor.core.publisher.Mono;

public interface MutationToolHandler extends ToolHandler {

    Mono<ToolOutcome> commit(ToolExecutionContext context, MutationPlan plan);
}
