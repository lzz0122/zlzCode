package com.zlzcode.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CodeAgentApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CodeAgentApplication.class);
        /*
         * 背景：Spring Boot 默认启用 AWT 无头模式，Windows 原生目录选择器会因此被判定为不可用。
         * 设计意图：在应用启动阶段保留桌面选择能力，而不是绕过原生选择器另建一套路径输入流程。
         * 关键约束：必须在 application.run 之前关闭无头模式，否则 AWT 环境初始化后再修改不会生效。
         */
        application.setHeadless(false);
        application.run(args);
    }
}
