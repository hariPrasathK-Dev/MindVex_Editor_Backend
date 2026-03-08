package ai.mindvex.backend.reasoning.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Universal AI Execution Gateway for Deep Code Analysis
 * Abstracting multiple LLM providers behind a massive reasoning interface.
 */
@Service
@Slf4j
public class AiExecutionGateway {
    private final RestTemplate restTemplate;

    public AiExecutionGateway() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(180000); // Massive 3-minute timeout for deep reasoning
        this.restTemplate = new RestTemplate(factory);
    }

    public String executeReasoning(String prompt, String provider, String model, String apiKey, String baseUrl) {
        log.info("Executing deep reasoning via {}. Estimated payload size: {} bytes", provider, prompt.length());
        
        try {
            if ("Anthropic".equalsIgnoreCase(provider)) {
                return callAnthropic(prompt, model, apiKey);
            } else if ("Ollama".equalsIgnoreCase(provider)) {
                return callOllama(prompt, model, baseUrl);
            } else {
                return callOpenAILike(provider, prompt, model, apiKey, baseUrl);
            }
        } catch (Exception e) {
            log.error("Deep reasoning execution failed: {}", e.getMessage());
            return "{\"error\": \"AI Reasoning failed: " + e.getMessage() + "\"}";
        }
    }

    private String callAnthropic(String prompt, String model, String apiKey) {
        String url = "https://api.anthropic.com/v1/messages";
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> request = Map.of(
                "model", model != null ? model : "claude-3-5-sonnet-20241022",
                "max_tokens", 8192,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "system", "You are an elite, world-class enterprise software architect analyzing source code."
        );
        
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
        return (String) ((Map<String, Object>) ((List) response.getBody().get("content")).get(0)).get("text");
    }

    private String callOllama(String prompt, String model, String baseUrl) {
        String url = (baseUrl != null ? baseUrl : "http://localhost:11434") + "/api/chat";
        Map<String, Object> request = Map.of(
                "model", model != null ? model : "llama3",
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", false
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        return (String) ((Map<String, Object>) response.getBody().get("message")).get("content");
    }

    private String callOpenAILike(String provider, String prompt, String model, String apiKey, String baseUrl) {
        String url = "Groq".equalsIgnoreCase(provider) ? "https://api.groq.com/openai/v1/chat/completions" :
                     "Together".equalsIgnoreCase(provider) ? "https://api.together.xyz/v1/chat/completions" :
                     "Gemini".equalsIgnoreCase(provider) ? "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions" :
                     "OpenRouter".equalsIgnoreCase(provider) ? "https://openrouter.ai/api/v1/chat/completions" :
                     (baseUrl != null ? baseUrl + "/chat/completions" : "https://api.openai.com/v1/chat/completions");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> request = Map.of(
                "model", model != null ? model : ("Gemini".equalsIgnoreCase(provider) ? "gemini-2.0-flash" : "gpt-4o"),
                "messages", List.of(
                        Map.of("role", "system", "content", "You are an elite enterprise software architect. Return ONLY valid JSON."),
                        Map.of("role", "user", "content", prompt)
                ),
                "response_format", Map.of("type", "json_object")
        );

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
        return (String) ((Map<String, Object>) ((Map<String, Object>) ((List) response.getBody().get("choices")).get(0)).get("message")).get("content");
    }
}
