package com.zlzcode.codeagent.tool.handler;

import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolExecutionResult;
import reactor.core.publisher.Mono;

public interface ToolHandler {

    Mono<ToolExecutionResult> execute(ToolExecutionContext context, String arguments);
}
