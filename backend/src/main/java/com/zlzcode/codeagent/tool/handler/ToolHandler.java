package com.zlzcode.codeagent.tool.handler;

import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import reactor.core.publisher.Mono;

public interface ToolHandler {

    Mono<ToolOutcome> execute(AuthorizedWorkspace workspace, String arguments);
}
