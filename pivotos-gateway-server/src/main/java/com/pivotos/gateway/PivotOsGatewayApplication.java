package com.pivotos.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 边缘网关启动类（独立进程，默认端口 8090）。
 *
 * <p><b>为什么是独立进程</b>：网关是边缘组件，把它塞进 admin-server 会让单体形态的
 * fat jar 平白带上网关全家桶，与「单体形态零配置直起」的一票否决点冲突；
 * 独立 jar 则可以在单体形态下<b>根本不启动</b>。
 *
 * <p><b>为什么用 WebMVC 变体</b>：Spring Cloud Gateway 5.0.3 同时提供
 * webflux 与 webmvc 两个 server 变体；本项目主应用是 Servlet/Tomcat 栈，
 * 选 webmvc 变体可避免引入 WebFlux 与 Netty（两套栈的线程模型与排障方式完全不同）。
 */
@SpringBootApplication
public class PivotOsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PivotOsGatewayApplication.class, args);
    }
}
