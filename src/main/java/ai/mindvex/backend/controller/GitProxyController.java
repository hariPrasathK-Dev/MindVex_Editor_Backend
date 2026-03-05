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
  @GetMapping("/**")
  public ResponseEntity<byte[]> proxyGet(
      HttpServletRequest request,
      @RequestHeader HttpHeaders headers,
      Authentication authentication) {

    // Extract the target URL from the request path
    String path = request.getRequestURI().substring("/api/git-proxy/".length());
    String queryString = request.getQueryString();
    String targetUrl = "https://" + path + (queryString != null ? "?" + queryString : "");

    return forwardRequest(targetUrl, HttpMethod.GET, headers, null, authentication);
  }

  /**
   * Proxy all POST requests to git servers
   */
  @PostMapping("/**")
  public ResponseEntity<byte[]> proxyPost(
      HttpServletRequest request,
      @RequestHeader HttpHeaders headers,
      @RequestBody(required = false) byte[] body,
      Authentication authentication) {

    // Extract the target URL from the request path
    String path = request.getRequestURI().substring("/api/git-proxy/".length());
    String queryString = request.getQueryString();
    String targetUrl = "https://" + path + (queryString != null ? "?" + queryString : "");

    return forwardRequest(targetUrl, HttpMethod.POST, headers, body, authentication);
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
            !lowerKey.equals("cookie")) {
          outgoingHeaders.put(key, value);
        }
      });

      // Add GitHub authentication for GitHub requests
      if (targetUrl.contains("github.com") && authentication != null) {
        try {
          UserDetails userDetails = (UserDetails) authentication.getPrincipal();
          User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);
          
          if (user != null && user.getGithubAccessToken() != null && !user.getGithubAccessToken().isBlank()) {
            // Use Basic Auth with token as password (GitHub's preferred method for HTTPS git operations)
            String token = user.getGithubAccessToken();
            outgoingHeaders.setBasicAuth("oauth2", token);
            log.debug("[GitProxy] Using GitHub authentication for: {}", targetUrl);
          }
        } catch (Exception e) {
          log.warn("[GitProxy] Failed to get GitHub token for user: {}", e.getMessage());
        }
      }

      // Ensure we accept all content types
      outgoingHeaders.setAccept(Collections.singletonList(MediaType.ALL));

      // Create request entity
      HttpEntity<byte[]> requestEntity = new HttpEntity<>(body, outgoingHeaders);

      // Forward the request
      ResponseEntity<byte[]> response = restTemplate.exchange(
          URI.create(targetUrl),
          method,
          requestEntity,
          byte[].class);

      // Return the response with CORS headers
      HttpHeaders responseHeaders = new HttpHeaders();
      responseHeaders.putAll(response.getHeaders());
      responseHeaders.set("Access-Control-Allow-Origin", "*");
      responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      responseHeaders.set("Access-Control-Allow-Headers", "*");

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
    HttpHeaders headers = new HttpHeaders();
    headers.set("Access-Control-Allow-Origin", "*");
    headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    headers.set("Access-Control-Allow-Headers", "*");
    headers.set("Access-Control-Max-Age", "3600");

    return ResponseEntity.ok().headers(headers).build();
  }
}
