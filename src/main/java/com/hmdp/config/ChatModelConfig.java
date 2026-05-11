package com.hmdp.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * ChatModel 配置
 * 显式配置普通对话模型，确保 base-url 正确读取
 * 使用 JDK HttpClient 解决 Windows 环境 RestClient 连接问题
 */
@Configuration
public class ChatModelConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private Double temperature;

    /**
     * 创建普通 ChatModel（用于纯文本对话）
     * 设置为 Primary，覆盖 Spring AI 自动配置
     */
    @Bean
    @Primary
    public OpenAiChatModel chatModel() {
        // 使用 JDK HttpClient 解决 RestClient 连接问题
        // Tomcat负责处理进入应用的HTTP请求，RestClient负责处理应用发出的HTTP请求
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // requestFactory 负责创建 RestClient 的 HTTP 请求，使用 JDK HttpClient 进行连接
        ClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();

        // 规范化 baseUrl：去除末尾的 /v1 或 /v1/
        String resolvedBaseUrl = baseUrl == null ? null : baseUrl.replaceAll("/v1/?$", "");

        // 打印关键配置，便于定位 404 问题
        System.out.println("[ChatModelConfig] creating chatModel with baseUrl=" + resolvedBaseUrl + " model=" + model);

        // 构建 RestClient，设置 baseUrl 和 Authorization 头
        RestClient.Builder restClientBuilder = RestClient.builder()
                .baseUrl(resolvedBaseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory);

        // 构建 OpenAiApi，注入 RestClient
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(resolvedBaseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();

        // 构建 OpenAiChatOptions，设置模型和温度
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        // 构建 OpenAiChatModel，注入 OpenAiApi 和默认选项
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}