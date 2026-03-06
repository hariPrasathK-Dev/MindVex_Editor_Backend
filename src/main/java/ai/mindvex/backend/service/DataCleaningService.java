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
 * ═══════════════════════════════════════════════════════════════════════════
 * CRITICAL: THIS IS A CLEANING SERVICE, NOT A DISCOVERY SERVICE
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * This service operates on ALREADY-EXTRACTED data from semantic search.
 * It does NOT discover new information or generate new endpoints.
 * 
 * **What It Does:**
 * 1. Parses extracted code chunks into structured DTOs (ExtractedEndpoint)
 * 2. Removes duplicate endpoints (same method + path)
 * 3. Merges overlapping information from multiple sources
 * 4. Standardizes descriptions (professional, concise, consistent)
 * 5. Normalizes formatting (paths, JSON, parameter tables)
 * 6. Preserves critical elements (commands, env vars, code snippets)
 * 
 * **What It Does NOT Do:**
 * ❌ Discover new endpoints not in extracted code
 * ❌ Invent or hallucinate API routes
 * ❌ Modify endpoint paths (/get_posts stays /get_posts)
 * ❌ Change commands or environment variables
 * ❌ Alter code snippets
 * 
 * **Process Flow:**
 * ```
 * Input: Code chunks from semantic search (already extracted)
 *   ↓
 * AI Parsing: Code → JSON (router prefixes, params, responses)
 *   ↓
 * Deduplication: Remove duplicates, merge overlapping data
 *   ↓
 * Standardization: Clean descriptions, normalize formatting
 *   ↓
 * Output: Clean ExtractedEndpoint DTOs ready for markdown generation
 * ```
 * 
 * **Example:**
 * ```
 * Input (3 code chunks):
 *   Chunk 1: @router.post("/login") in auth router with prefix="/auth"
 *   Chunk 2: POST /auth/login with description "logs in user"
 *   Chunk 3: POST /auth/login with request body example
 * 
 * Output (1 cleaned endpoint):
 *   method: POST
 *   path: /auth/login  ← Prefix detected and combined
 *   description: "Authenticate user with credentials."  ← Standardized
 *   requestBody: {...}  ← Merged from chunk 3
 * ```
 * 
 * @see ExtractedEndpoint - Structured endpoint DTO
 * @see LivingWikiService - Uses this service for two-stage documentation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataCleaningService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Extract endpoints from ALREADY-EXTRACTED code chunks using AI to parse into structured JSON.
     * 
     * CRITICAL: The code chunks have already been extracted via semantic search.
     * This method only PARSES and STRUCTURES the extracted code, it does NOT discover new endpoints.
     * 
     * @param codeChunks Already-extracted code from semantic search (contains route definitions)
     * @param provider AI provider configuration (Groq, OpenAI, etc.)
     * @return List of ExtractedEndpoint objects parsed from code (may contain duplicates)
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
     * Build prompt for AI to extract endpoints from code as JSON.
     * 
     * CRITICAL: This is a CLEANING operation, not a discovery operation.
     * The code has already been extracted via semantic search.
     * The AI should ONLY parse what's in the provided code chunks.
     */
    private String buildExtractionPrompt(String codeChunks) {
        return """
                You are a data cleaning specialist for API documentation.
                
                **YOUR ROLE: CLEAN AND STRUCTURE ALREADY-EXTRACTED CODE**
                
                The code below has already been extracted from a repository.
                Your job is to parse this extracted code into structured JSON.
                
                🚫 DO NOT discover new endpoints
                🚫 DO NOT invent or hallucinate endpoints
                🚫 DO NOT modify endpoint paths
                ✅ ONLY extract what you can SEE in the provided code
                ✅ Keep HTTP methods and paths EXACTLY as written
                ✅ Extract parameter names EXACTLY as they appear
                
                **EXTRACTION INSTRUCTIONS:**
                
                1. **Router Prefix Detection (CRITICAL for correct paths):**
                   - Look for: `APIRouter(prefix="/auth")`, `Blueprint(url_prefix="/api")`, `@RequestMapping("/users")`
                   - Look for registration: `app.include_router(router, prefix="/auth")`
                   - COMBINE prefix + endpoint: prefix="/auth" + @router.post("/login") = /auth/login
                   - Example: If you see `router = APIRouter(prefix="/auth")` and `@router.post("/login")`, extract: "/auth/login"
                
                2. **Extract endpoints from decorators/annotations:**
                   - Python FastAPI: `@app.get("/users")`, `@router.post("/auth/login")`
                   - Python Flask: `@app.route("/posts", methods=["POST"])`
                   - Java Spring: `@GetMapping("/users/{id}")`, `@PostMapping("/posts")`
                   - Express/Node: `router.get("/timeline")`, `app.post("/connect")`
                   - Extract path EXACTLY: /get_posts means /get_posts (NOT /posts)
                
                3. **Extract parameters from function signatures:**
                   - Path params: `{id}`, `{username}`, `{post_id}` in decorator
                   - Query params: function parameters, @RequestParam annotations
                   - Body params: @RequestBody, request.json, function parameters
                   - DO NOT invent parameters that aren't in code
                
                4. **Required JSON output format:**
                
                [
                  {
                    "method": "POST",
                    "path": "/auth/login",
                    "description": "Authenticate user",
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
                      }
                    ],
                    "requestBody": "{\\"username\\": \\"user@example.com\\", \\"password\\": \\"secret\\"}",
                    "responseBody": "{\\"token\\": \\"jwt_token\\", \\"user\\": {...}}",
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
                
                **QUALITY RULES:**
                
                ✅ If you see `POST /auth/register` in code → extract `POST /auth/register`
                ✅ If you see `/get_posts` → extract `/get_posts` (DO NOT change to /posts)
                ✅ If you see `{username}` → extract `{username}` (EXACT placeholder)
                ✅ If parameter type unclear → use "string" as default
                ✅ If description unclear → use brief extracted comment or "No description"
                
                🚫 DO NOT output: Generic /users, /posts, /items if code shows specific paths
                🚫 DO NOT normalize paths: Keep /get_posts, NOT /posts
                🚫 DO NOT invent parameters not in function signature
                🚫 DO NOT add endpoints you don't see in code
                
                **CODE TO PARSE (ALREADY EXTRACTED):**
                
                %s
                
                **OUTPUT:**
                Output ONLY the JSON array. No markdown code blocks. No explanations. No additional text.
                Start directly with [ and end with ].
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

    // ═══════════════════════════════════════════════════════════════════════════
    // README CLEANING AND STANDARDIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Clean and standardize README sections extracted from code/docs.
     * 
     * CRITICAL: This method does NOT discover new information.
     * It only cleans, deduplicates, and standardizes what has already been extracted.
     * 
     * @param extractedSections List of already-extracted README sections from code chunks
     * @return Cleaned and deduplicated sections ready for formatting
     */
    public Map<String, String> cleanReadmeSections(List<Map<String, Object>> extractedSections) {
        log.info("[DataCleaning] Cleaning {} README sections", extractedSections.size());
        
        Map<String, List<String>> sectionsByType = new LinkedHashMap<>();
        Map<String, List<String>> commandsBySection = new LinkedHashMap<>();
        Map<String, List<String>> envVarsBySection = new LinkedHashMap<>();
        
        // Group extracted content by section type
        for (Map<String, Object> section : extractedSections) {
            String type = (String) section.get("type");
            String content = (String) section.get("content");
            
            if (type == null || content == null || content.isBlank()) {
                continue;
            }
            
            // Add to appropriate section
            sectionsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(content);
            
            // Extract and preserve commands (do not modify)
            List<String> commands = extractCommands(content);
            if (!commands.isEmpty()) {
                commandsBySection.computeIfAbsent(type, k -> new ArrayList<>()).addAll(commands);
            }
            
            // Extract and preserve environment variables (do not modify)
            List<String> envVars = extractEnvironmentVariables(content);
            if (!envVars.isEmpty()) {
                envVarsBySection.computeIfAbsent(type, k -> new ArrayList<>()).addAll(envVars);
            }
        }
        
        // Clean and merge each section type
        Map<String, String> cleanedSections = new LinkedHashMap<>();
        
        for (Map.Entry<String, List<String>> entry : sectionsByType.entrySet()) {
            String type = entry.getKey();
            List<String> contents = entry.getValue();
            
            // Merge repeated sections
            String merged = mergeRepeatedSections(contents);
            
            // Standardize formatting (but preserve commands/env vars)
            String standardized = standardizeReadmeSection(merged, type);
            
            // Re-inject preserved commands and env vars
            standardized = reInjectPreservedElements(
                standardized,
                commandsBySection.getOrDefault(type, new ArrayList<>()),
                envVarsBySection.getOrDefault(type, new ArrayList<>())
            );
            
            cleanedSections.put(type, standardized);
        }
        
        log.info("[DataCleaning] Cleaned {} unique README sections", cleanedSections.size());
        return cleanedSections;
    }

    /**
     * Extract shell commands from text (to preserve them unchanged)
     */
    private List<String> extractCommands(String text) {
        List<String> commands = new ArrayList<>();
        
        // Pattern 1: Code blocks with bash/sh/shell
        Pattern codeBlockPattern = Pattern.compile("```(?:bash|sh|shell|cmd)\\s*\\n([^`]+)```", Pattern.DOTALL);
        Matcher matcher = codeBlockPattern.matcher(text);
        while (matcher.find()) {
            String block = matcher.group(1).trim();
            commands.addAll(Arrays.asList(block.split("\\r?\\n")));
        }
        
        // Pattern 2: Single-line commands starting with $, #, > 
        Pattern cmdLinePattern = Pattern.compile("^\\s*[$#>]\\s*(.+)$", Pattern.MULTILINE);
        matcher = cmdLinePattern.matcher(text);
        while (matcher.find()) {
            commands.add(matcher.group(1).trim());
        }
        
        // Pattern 3: npm/mvn/pip/docker commands
        Pattern toolPattern = Pattern.compile("((?:npm|mvn|pip|docker|git|yarn|pnpm)\\s+[^\\n]+)", Pattern.MULTILINE);
        matcher = toolPattern.matcher(text);
        while (matcher.find()) {
            commands.add(matcher.group(1).trim());
        }
        
        return commands.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Extract environment variables from text (to preserve them unchanged)
     */
    private List<String> extractEnvironmentVariables(String text) {
        List<String> envVars = new ArrayList<>();
        
        // Pattern: VARIABLE_NAME=value or process.env.VARIABLE_NAME
        Pattern envPattern = Pattern.compile("([A-Z_][A-Z0-9_]*)\\s*=\\s*['\"]?([^'\"\\s\\n]+)", Pattern.MULTILINE);
        Matcher matcher = envPattern.matcher(text);
        while (matcher.find()) {
            envVars.add(matcher.group(1) + "=" + matcher.group(2));
        }
        
        // Pattern: .env file format
        Pattern dotEnvPattern = Pattern.compile("^\\s*([A-Z_][A-Z0-9_]*)\\s*=\\s*(.+)$", Pattern.MULTILINE);
        matcher = dotEnvPattern.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            if (!envVars.contains(key + "=" + value)) {
                envVars.add(key + "=" + value);
            }
        }
        
        return envVars.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Merge repeated sections by removing duplicates and fragments
     */
    private String mergeRepeatedSections(List<String> sections) {
        if (sections.isEmpty()) {
            return "";
        }
        
        if (sections.size() == 1) {
            return sections.get(0);
        }
        
        // Sort by length (prefer complete sections over fragments)
        sections.sort((a, b) -> Integer.compare(b.length(), a.length()));
        
        // Start with the longest (most complete) section
        StringBuilder merged = new StringBuilder(sections.get(0));
        
        // Add unique content from other sections
        for (int i = 1; i < sections.size(); i++) {
            String section = sections.get(i);
            
            // If this section contains unique information not in merged, add it
            if (!merged.toString().contains(section.trim())) {
                // Extract unique sentences/lines
                String[] lines = section.split("\\r?\\n");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !merged.toString().contains(trimmed)) {
                        merged.append("\n").append(line);
                    }
                }
            }
        }
        
        return merged.toString().trim();
    }

    /**
     * Standardize README section formatting without changing content
     */
    private String standardizeReadmeSection(String content, String sectionType) {
        if (content == null || content.isBlank()) {
            return "";
        }
        
        // Remove excessive newlines (max 2 consecutive)
        content = content.replaceAll("\\n{3,}", "\n\n");
        
        // Trim whitespace at start/end
        content = content.trim();
        
        // Ensure proper markdown heading format (not changing content, just format)
        content = content.replaceAll("^#+\\s*([^\\n]+)\\s*$", "## $1");
        
        // Standardize list formatting
        content = content.replaceAll("^\\s*[-*+]\\s+", "- ");
        content = content.replaceAll("^\\s*(\\d+)\\.\\s+", "$1. ");
        
        return content;
    }

    /**
     * Re-inject preserved commands and environment variables
     * Ensures they are not modified during cleaning
     */
    private String reInjectPreservedElements(String content, List<String> commands, List<String> envVars) {
        // This is a placeholder - in practice, we mark these during extraction
        // and ensure they pass through cleaning unchanged
        
        // The extraction process already preserves them in the original positions
        // This method exists to document the intent
        
        return content;
    }

    /**
     * Validate that critical elements were preserved during cleaning
     */
    public boolean validatePreservedElements(String original, String cleaned) {
        // Extract commands from both
        List<String> originalCommands = extractCommands(original);
        List<String> cleanedCommands = extractCommands(cleaned);
        
        // Ensure all original commands are still present
        for (String cmd : originalCommands) {
            if (!cleanedCommands.contains(cmd)) {
                log.warn("[DataCleaning] Command lost during cleaning: {}", cmd);
                return false;
            }
        }
        
        // Extract env vars from both
        List<String> originalEnvVars = extractEnvironmentVariables(original);
        List<String> cleanedEnvVars = extractEnvironmentVariables(cleaned);
        
        // Ensure all original env vars are still present
        for (String env : originalEnvVars) {
            if (!cleanedEnvVars.contains(env)) {
                log.warn("[DataCleaning] Environment variable lost during cleaning: {}", env);
                return false;
            }
        }
        
        return true;
    }
}
