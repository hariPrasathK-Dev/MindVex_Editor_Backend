package ai.mindvex.backend.controller;

import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;

/**
 * Git Proxy Controller
 * 
 * This controller acts as a CORS proxy for git operations from the frontend.
 * It forwards git-related HTTP requests to the actual git servers (like GitHub)
 * to bypass CORS restrictions that browsers impose.
 * 
 * Supports GitHub authentication for private repositories by using the user's
 * stored GitHub access token.
 */
@RestController
@RequestMapping("/api/git-proxy")
@RequiredArgsConstructor
@Slf4j
public class GitProxyController {

  private final RestTemplate restTemplate = new RestTemplate();
  private final UserRepository userRepository;

  /**
   * Proxy all GET requests to git servers
   */
  /**
   * Proxy all GET requests to git servers
   */
  @GetMapping("/{*path}")
  public ResponseEntity<byte[]> proxyGet(
      HttpServletRequest request,
      @PathVariable String path,
      @RequestHeader HttpHeaders headers,
      Authentication authentication) {
    
    String cleanPath = path.startsWith("/") ? path.substring(1) : path;
    log.info("[GitProxy-DEBUG] GET Proxy for path: {}", cleanPath);
    
    String queryString = request.getQueryString();
    String targetUrl = "https://" + cleanPath + (queryString != null ? "?" + queryString : "");

    return forwardRequest(targetUrl, HttpMethod.GET, headers, null, authentication);
  }

  /**
   * Proxy all POST requests to git servers
   */
  @PostMapping("/{*path}")
  public ResponseEntity<byte[]> proxyPost(
      HttpServletRequest request,
      @PathVariable String path,
      @RequestHeader HttpHeaders headers,
      @RequestBody(required = false) byte[] body,
      Authentication authentication) {
    
    String cleanPath = path.startsWith("/") ? path.substring(1) : path;
    log.info("[GitProxy-DEBUG] POST Proxy for path: {}", cleanPath);
    
    String queryString = request.getQueryString();
    String targetUrl = "https://" + cleanPath + (queryString != null ? "?" + queryString : "");

    return forwardRequest(targetUrl, HttpMethod.POST, headers, body, authentication);
  }

  /**
   * Proxy all PUT requests to git servers
   */
  @PutMapping("/{*path}")
  public ResponseEntity<byte[]> proxyPut(
      HttpServletRequest request,
      @PathVariable String path,
      @RequestHeader HttpHeaders headers,
      @RequestBody(required = false) byte[] body,
      Authentication authentication) {
    
    String cleanPath = path.startsWith("/") ? path.substring(1) : path;
    log.info("[GitProxy-DEBUG] PUT Proxy for path: {}", cleanPath);
    
    String queryString = request.getQueryString();
    String targetUrl = "https://" + cleanPath + (queryString != null ? "?" + queryString : "");

    return forwardRequest(targetUrl, HttpMethod.PUT, headers, body, authentication);
  }

  /**
   * Forward the request to the target URL with GitHub authentication if available
   */
  private ResponseEntity<byte[]> forwardRequest(
      String targetUrl,
      HttpMethod method,
      HttpHeaders incomingHeaders,
      byte[] body,
      Authentication authentication) {

    try {
      // Log incoming request details
      log.info("[GitProxy] {} Request to: {}", method, targetUrl);
      incomingHeaders.forEach((key, value) -> log.debug("[GitProxy] Incoming Header: {} = {}", key, value));

      // Create headers for the outgoing request
      HttpHeaders outgoingHeaders = new HttpHeaders();

      // Copy relevant headers (exclude host, origin, referer, cookie to avoid issues)
      // We'll handle authorization separately for GitHub
      incomingHeaders.forEach((key, value) -> {
        String lowerKey = key.toLowerCase();
        if (!lowerKey.equals("host") &&
            !lowerKey.equals("origin") &&
            !lowerKey.equals("referer") &&
            !lowerKey.equals("authorization") &&
            !lowerKey.equals("cookie") &&
            !lowerKey.equals("accept-encoding")) { // Crucial: avoid compressed response
          outgoingHeaders.put(key, value);
        }
      });

      // Add GitHub authentication for GitHub requests
      if (targetUrl.contains("github.com") && authentication != null && authentication.isAuthenticated()
          && authentication.getPrincipal() instanceof UserDetails) {
        try {
          UserDetails userDetails = (UserDetails) authentication.getPrincipal();
          User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

          if (user != null && user.getGithubAccessToken() != null && !user.getGithubAccessToken().isBlank()) {
            // Use standard Bearer token for GitHub REST API
            String token = user.getGithubAccessToken();
            outgoingHeaders.set("Authorization", "Bearer " + token);
            log.info("[GitProxy] Injected GitHub Bearer token for user: {}", user.getEmail());
          } else {
            log.warn("[GitProxy] No GitHub token found for user: {}", userDetails.getUsername());
          }
        } catch (Exception e) {
          log.error("[GitProxy] Failed to get GitHub token for user: {}", e.getMessage());
        }
      }

      // Force GitHub to send uncompressed data
      outgoingHeaders.set("Accept-Encoding", "identity");

      // Ensure we accept all content types
      outgoingHeaders.setAccept(Collections.singletonList(MediaType.ALL));

      outgoingHeaders.forEach((key, value) -> log.debug("[GitProxy] Outgoing Header: {} = {}", key, value));

      // Create request entity
      HttpEntity<byte[]> requestEntity = new HttpEntity<>(body, outgoingHeaders);

      // Forward the request
      log.info("[GitProxy] Forwarding request to GitHub: {} {}", method, targetUrl);
      ResponseEntity<byte[]> response = restTemplate.exchange(
          URI.create(targetUrl),
          method,
          requestEntity,
          byte[].class);

      log.info("[GitProxy] Target responded with: {} (Size: {} bytes)", 
          response.getStatusCode(), 
          response.getBody() != null ? response.getBody().length : 0);
      
      response.getHeaders().forEach((key, value) -> log.debug("[GitProxy] Target Header: {} = {}", key, value));

      // Clean up response headers to avoid conflicting CORS headers
      HttpHeaders responseHeaders = new HttpHeaders();
      responseHeaders.putAll(response.getHeaders());

      // Let Spring's global CorsFilter handle CORS instead of GitHub's or our manual headers
      responseHeaders.remove("Access-Control-Allow-Origin");
      responseHeaders.remove("Access-Control-Allow-Methods");
      responseHeaders.remove("Access-Control-Allow-Headers");
      responseHeaders.remove("Access-Control-Allow-Credentials");
      
      // CRITICAL: Remove Transfer-Encoding as it conflicts with Spring Boot's response handling
      responseHeaders.remove("Transfer-Encoding");
      responseHeaders.remove("Content-Encoding"); // GitHub might send gzip, but RestTemplate already decompressed it
      responseHeaders.remove("Content-Length"); // Spring will calculate it automatically
      responseHeaders.remove("Vary"); // Avoid cache mismatch issues
      responseHeaders.remove("Content-Language");
      responseHeaders.remove("Server");
      responseHeaders.remove("X-Frame-Options");
      responseHeaders.remove("X-Content-Type-Options");
      responseHeaders.remove("X-XSS-Protection");

      return ResponseEntity
          .status(response.getStatusCode())
          .headers(responseHeaders)
          .body(response.getBody());

    } catch (Exception e) {
      log.error("[GitProxy] Error proxying request to {}: {}", targetUrl, e.getMessage());

      return ResponseEntity
          .status(HttpStatus.BAD_GATEWAY)
          .body(("Git proxy error: " + e.getMessage()).getBytes());
    }
  }

  /**
   * Handle OPTIONS requests for CORS preflight
   */
  @RequestMapping(value = "/**", method = RequestMethod.OPTIONS)
  public ResponseEntity<Void> handleOptions() {
    // Spring Boot's global CorsFilter handles preflight
    return ResponseEntity.ok().build();
  }
}
