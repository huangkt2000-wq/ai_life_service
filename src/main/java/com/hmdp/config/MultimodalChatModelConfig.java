package com.hmdp.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 多模态 ChatModel 配置
 * 用于图片分析功能
 *
 * 使用支持图片输入的多模态模型
 * 与对话模型共用相同的 API 服务，只是模型名称不同
 * 使用 JDK HttpClient 解决 Windows 环境 RestClient 连接问题
 */
@Configuration
public class MultimodalChatModelConfig {

    @Value("${spring.ai.openai.multimodal.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.multimodal.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.multimodal.options.model}")
    private String model;

    @Value("${spring.ai.openai.multimodal.options.temperature:0.7}")
    private Double temperature;

    /**
     * 创建多模态 ChatModel（用于图片分析）
     */
    @Bean(name = "multimodalChatModel")
    public OpenAiChatModel multimodalChatModel() {
        // 使用 JDK HttpClient 解决 RestClient 连接问题
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        ClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();

        RestClient.Builder restClientBuilder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}