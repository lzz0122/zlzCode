package com.zlzcode.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CodeAgentApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CodeAgentApplication.class);
        application.setHeadless(false);
        application.run(args);
    }
}
