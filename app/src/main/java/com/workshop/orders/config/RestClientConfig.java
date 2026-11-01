package com.workshop.orders.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient paymentRestClient(RestClient.Builder builder,
                                        @Value("${app.payment.base-url}") String baseUrl,
                                        @Value("${app.payment.connect-timeout-ms:2000}") int connectTimeoutMs,
                                        @Value("${app.payment.read-timeout-ms:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
