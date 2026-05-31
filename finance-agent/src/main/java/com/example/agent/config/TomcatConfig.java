package com.example.agent.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【Tomcat 内嵌容器定制】
 *
 * <p>启用 {@code tcpNoDelay}（禁用 Nagle 算法），减少 SSE 流式输出的 TCP 延迟。
 * 每个 token 的及时推送对前端逐字渲染体验至关重要。
 */
@Configuration
public class TomcatConfig {

    /**
     * 注册 Tomcat 连接器定制器，对所有连接器启用 TCP_NODELAY。
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setProperty("tcpNoDelay", "true");
        });
    }
}
