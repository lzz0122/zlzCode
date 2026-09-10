package com.zlzcode.codeagent.tool.config;

import com.zlzcode.codeagent.tool.definition.GlobToolDefinition;
import com.zlzcode.codeagent.tool.definition.GrepToolDefinition;
import com.zlzcode.codeagent.tool.definition.ReadToolDefinition;
import com.zlzcode.codeagent.tool.definition.WorkspaceOverviewToolDefinition;
import com.zlzcode.codeagent.tool.definition.WriteToolDefinition;
import com.zlzcode.codeagent.tool.registry.ToolRegistration;
import com.zlzcode.codeagent.tool.service.GlobService;
import com.zlzcode.codeagent.tool.service.GrepService;
import com.zlzcode.codeagent.tool.service.ReadService;
import com.zlzcode.codeagent.tool.service.WorkspaceOverviewService;
import com.zlzcode.codeagent.tool.service.WriteService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ToolProperties.class)
public class ToolConfiguration {

    @Bean
    ToolRegistration readRegistration(ReadToolDefinition definition, ReadService handler) {
        return new ToolRegistration(definition, handler, true);
    }

    @Bean
    ToolRegistration globRegistration(GlobToolDefinition definition, GlobService handler) {
        return new ToolRegistration(definition, handler, true);
    }

    @Bean
    ToolRegistration grepRegistration(GrepToolDefinition definition, GrepService handler) {
        return new ToolRegistration(definition, handler, true);
    }

    @Bean
    ToolRegistration writeRegistration(WriteToolDefinition definition, WriteService handler) {
        return new ToolRegistration(definition, handler, true);
    }

    @Bean
    ToolRegistration workspaceOverviewRegistration(
            WorkspaceOverviewToolDefinition definition,
            WorkspaceOverviewService handler) {
        return new ToolRegistration(definition, handler, true);
    }
}
