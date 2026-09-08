package io.quotapilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * QuotaPilot —— 面向模型与第三方 API 的实时预算控制网关。
 * 单应用 + 模块化包结构（V1，规范 §11）；模块边界用 Java 包与接口约束。
 */
@SpringBootApplication
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
