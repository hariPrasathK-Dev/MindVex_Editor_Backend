package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.EndpointParameter;
import ai.mindvex.backend.dto.ErrorResponse;
import ai.mindvex.backend.dto.ExtractedEndpoint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Data Cleaning and Standardization Service
 * 
 * Responsible for cleaning, deduplicating, and standardizing extracted documentation data
 * before final formatting. This ensures consistency and removes redundancy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataCleaningService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Extract endpoints from code chunks using AI to parse into structured JSON.
     * This is the first pass: code → structured data.
     */
    public List<ExtractedEndpoint> extractEndpointsFromChunks(String codeChunks, Map<String, Object> provider) {
        log.info("[DataCleaning] Extracting endpoints from code chunks ({} chars)", codeChunks.length());
        
        String prompt = buildExtractionPrompt(codeChunks);
        
        try {
            String jsonResponse = callAiForExtraction(prompt, provider);
            
            if (jsonResponse != null && !jsonResponse.isBlank()) {
                // Parse JSON response into ExtractedEndpoint objects
                List<ExtractedEndpoint> endpoints = parseEndpointsFromJson(jsonResponse);
                log.info("[DataCleaning] Extracted {} endpoints from chunks", endpoints.size());
                return endpoints;
            }
        } catch (Exception e) {
            log.warn("[DataCleaning] Failed to extract endpoints: {}", e.getMessage());
        }
        
        return new ArrayList<>();
    }

    /**
     * Clean and deduplicate extracted endpoints.
     * Removes duplicates, merges overlapping information, standardizes formatting.
     */
    public List<ExtractedEndpoint> cleanAndDeduplicateEndpoints(List<ExtractedEndpoint> endpoints) {
        log.info("[DataCleaning] Cleaning {} endpoints", endpoints.size());
        
        // Step 1: Deduplicate by method + path
        Map<String, ExtractedEndpoint> uniqueEndpoints = new LinkedHashMap<>();
        
        for (ExtractedEndpoint endpoint : endpoints) {
            String key = endpoint.getUniqueKey();
            
            if (uniqueEndpoints.containsKey(key)) {
                // Merge with existing endpoint
                uniqueEndpoints.get(key).mergeWith(endpoint);
                log.debug("[DataCleaning] Merged duplicate: {}", key);
            } else {
                uniqueEndpoints.put(key, endpoint);
            }
        }
        
        // Step 2: Standardize descriptions
        for (ExtractedEndpoint endpoint : uniqueEndpoints.values()) {
            endpoint.setDescription(standardizeDescription(endpoint.getDescription()));
        }
        
        // Step 3: Normalize paths (remove trailing slashes, etc.)
        for (ExtractedEndpoint endpoint : uniqueEndpoints.values()) {
            endpoint.setPath(normalizePath(endpoint.getPath()));
        }
        
        // Step 4: Sort parameters alphabetically
        for (ExtractedEndpoint endpoint : uniqueEndpoints.values()) {
            if (endpoint.getParameters() != null) {
                endpoint.getParameters().sort(Comparator.comparing(EndpointParameter::getName));
            }
        }
        
        // Step 5: Sort endpoints by method and path
        List<ExtractedEndpoint> cleaned = new ArrayList<>(uniqueEndpoints.values());
        cleaned.sort(Comparator
            .comparing(ExtractedEndpoint::getPath)
            .thenComparing(ExtractedEndpoint::getMethod));
        
        log.info("[DataCleaning] Cleaned {} unique endpoints (removed {} duplicates)", 
            cleaned.size(), endpoints.size() - cleaned.size());
        
        return cleaned;
    }

    /**
     * Build prompt for AI to extract endpoints from code as JSON
     */
    private String buildExtractionPrompt(String codeChunks) {
        return """
                You are a code analysis expert. Extract API endpoints from the provided code.
                
                **CRITICAL INSTRUCTIONS:**
                
                1. **Router Prefix Detection:**
                   - Look for: `APIRouter(prefix="/auth")`, `Blueprint(url_prefix="/api")`, `@RequestMapping("/users")`
                   - Look for: `app.include_router(router, prefix="/auth")`
                   - COMBINE prefix + endpoint path: prefix="/auth" + @router.post("/login") = /auth/login
                
                2. **Extract ONLY real endpoints from code:**
                   - HTTP decorators: @app.get, @app.post, @router.delete, @GetMapping, @PostMapping
                   - Route definitions: router.get(), app.route(), path()
                   - Extract EXACT path as written in code
                
                3. **Parse parameters:**
                   - Path params: {id}, {username}, {post_id}
                   - Query params: @RequestParam, request.args, query parameters
                   - Request body: @RequestBody, request.json, function parameters
                
                4. **Output ONLY valid JSON array:**
                
                ```json
                [
                  {
                    "method": "POST",
                    "path": "/auth/login",
                    "description": "Authenticate user with email and password",
                    "requiresAuth": false,
                    "routerPrefix": "/auth",
                    "sourceFile": "routers/auth.py",
                    "parameters": [
                      {
                        "name": "username",
                        "type": "string",
                        "location": "body",
                        "required": true,
                        "description": "User's email or username"
                      },
                      {
                        "name": "password",
                        "type": "string",
                        "location": "body",
                        "required": true,
                        "description": "User's password"
                      }
                    ],
                    "requestBody": "{\\"username\\": \\"user@example.com\\", \\"password\\": \\"secret\\"}",
                    "responseBody": "{\\"token\\": \\"jwt_token_here\\", \\"user\\": {...}}",
                    "errorResponses": [
                      {
                        "code": 401,
                        "name": "Unauthorized",
                        "description": "Invalid credentials",
                        "example": "{\\"error\\": \\"Invalid username or password\\"}"
                      }
                    ]
                  }
                ]
                ```
                
                **Code to analyze:**
                
                %s
                
                Output ONLY the JSON array. No markdown, no code blocks, no explanations.
                """.formatted(codeChunks);
    }

    /**
     * Call AI provider to extract endpoints as JSON
     */
    private String callAiForExtraction(String prompt, Map<String, Object> provider) {
        if (provider == null) {
            return null;
        }
        
        String providerName = (String) provider.get("name");
        String apiKey = (String) provider.get("apiKey");
        String model = (String) provider.get("model");
        
        if ("Groq".equals(providerName) || "OpenAI".equals(providerName)) {
            String url = "Groq".equals(providerName) 
                ? "https://api.groq.com/openai/v1/chat/completions"
                : "https://api.openai.com/v1/chat/completions";
            
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model != null ? model : "llama-3.1-8b-instant");
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            request.put("max_tokens", 3000);
            request.put("temperature", 0.1); // Low temperature for precise extraction
            
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new org.springframework.http.HttpEntity<>(request, headers),
                    String.class
                );
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> parsed = objectMapper.readValue(
                        response.getBody(), 
                        new TypeReference<>() {}
                    );
                    
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        return (String) message.get("content");
                    }
                }
            } catch (Exception e) {
                log.warn("[DataCleaning] AI extraction failed: {}", e.getMessage());
            }
        }
        
        return null;
    }

    /**
     * Parse JSON response into ExtractedEndpoint objects
     */
    private List<ExtractedEndpoint> parseEndpointsFromJson(String jsonResponse) {
        try {
            // Remove markdown code blocks if present
            String cleaned = jsonResponse.trim();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            }
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
            
            // Parse JSON array
            List<Map<String, Object>> rawEndpoints = objectMapper.readValue(
                cleaned,
                new TypeReference<>() {}
            );
            
            List<ExtractedEndpoint> endpoints = new ArrayList<>();
            
            for (Map<String, Object> raw : rawEndpoints) {
                ExtractedEndpoint endpoint = ExtractedEndpoint.builder()
                    .method((String) raw.get("method"))
                    .path((String) raw.get("path"))
                    .description((String) raw.get("description"))
                    .requiresAuth(Boolean.TRUE.equals(raw.get("requiresAuth")))
                    .routerPrefix((String) raw.get("routerPrefix"))
                    .sourceFile((String) raw.get("sourceFile"))
                    .requestBody((String) raw.get("requestBody"))
                    .responseBody((String) raw.get("responseBody"))
                    .parameters(parseParameters(raw.get("parameters")))
                    .errorResponses(parseErrorResponses(raw.get("errorResponses")))
                    .build();
                
                endpoints.add(endpoint);
            }
            
            return endpoints;
            
        } catch (Exception e) {
            log.warn("[DataCleaning] Failed to parse JSON: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Parse parameters from JSON
     */
    @SuppressWarnings("unchecked")
    private List<EndpointParameter> parseParameters(Object paramsObj) {
        if (paramsObj == null) {
            return new ArrayList<>();
        }
        
        List<EndpointParameter> parameters = new ArrayList<>();
        List<Map<String, Object>> rawParams = (List<Map<String, Object>>) paramsObj;
        
        for (Map<String, Object> raw : rawParams) {
            EndpointParameter param = EndpointParameter.builder()
                .name((String) raw.get("name"))
                .type((String) raw.get("type"))
                .location((String) raw.get("location"))
                .required(Boolean.TRUE.equals(raw.get("required")))
                .description((String) raw.get("description"))
                .example((String) raw.get("example"))
                .build();
            
            parameters.add(param);
        }
        
        return parameters;
    }

    /**
     * Parse error responses from JSON
     */
    @SuppressWarnings("unchecked")
    private List<ErrorResponse> parseErrorResponses(Object errorsObj) {
        if (errorsObj == null) {
            return new ArrayList<>();
        }
        
        List<ErrorResponse> errors = new ArrayList<>();
        List<Map<String, Object>> rawErrors = (List<Map<String, Object>>) errorsObj;
        
        for (Map<String, Object> raw : rawErrors) {
            Object codeObj = raw.get("code");
            int code = codeObj instanceof Integer ? (Integer) codeObj : 
                       Integer.parseInt(codeObj.toString());
            
            ErrorResponse error = ErrorResponse.builder()
                .code(code)
                .name((String) raw.get("name"))
                .description((String) raw.get("description"))
                .example((String) raw.get("example"))
                .build();
            
            errors.add(error);
        }
        
        return errors;
    }

    /**
     * Standardize description text
     */
    private String standardizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return "No description available";
        }
        
        // Trim whitespace
        description = description.trim();
        
        // Capitalize first letter
        if (!description.isEmpty()) {
            description = Character.toUpperCase(description.charAt(0)) + description.substring(1);
        }
        
        // Ensure ends with period
        if (!description.endsWith(".") && !description.endsWith("!") && !description.endsWith("?")) {
            description += ".";
        }
        
        // Replace common vague phrases
        description = description.replace("This endpoint", "")
                                 .replace("this endpoint", "")
                                 .replace("endpoint", "")
                                 .trim();
        
        if (!description.isEmpty()) {
            description = Character.toUpperCase(description.charAt(0)) + description.substring(1);
        }
        
        return description;
    }

    /**
     * Normalize path (remove trailing slashes, ensure starts with /)
     */
    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        
        // Ensure starts with /
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        
        // Remove trailing slash (except for root /)
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        
        // Normalize double slashes
        path = path.replaceAll("//+", "/");
        
        return path;
    }
}
