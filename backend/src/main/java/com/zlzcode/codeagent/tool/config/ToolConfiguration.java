package com.zlzcode.codeagent.tool.config;

import com.zlzcode.codeagent.tool.definition.WorkspaceOverviewToolDefinition;
import com.zlzcode.codeagent.tool.registry.ToolRegistration;
import com.zlzcode.codeagent.tool.service.WorkspaceOverviewService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ToolProperties.class)
public class ToolConfiguration {

    @Bean
    ToolRegistration workspaceOverviewRegistration(
            WorkspaceOverviewToolDefinition definition,
            WorkspaceOverviewService handler) {
        return new ToolRegistration(definition, handler, true);
    }
}
