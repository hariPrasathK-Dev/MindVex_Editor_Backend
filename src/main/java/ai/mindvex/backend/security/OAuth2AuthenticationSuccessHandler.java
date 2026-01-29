package ai.mindvex.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtService jwtService;
    private final HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;
    private final List<String> authorizedRedirectUris;

    public OAuth2AuthenticationSuccessHandler(
            JwtService jwtService,
            HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository,
            @Value("${app.oauth2.authorized-redirect-uris}") List<String> authorizedRedirectUris) {
        this.jwtService = jwtService;
        this.authorizationRequestRepository = authorizationRequestRepository;
        this.authorizedRedirectUris = authorizedRedirectUris;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {
        String targetUrl = determineTargetUrl(request, response, authentication);

        if (response.isCommitted()) {
            log.debug("Response has already been committed. Unable to redirect to " + targetUrl);
            return;
        }

        clearAuthenticationAttributes(request, response);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    protected String determineTargetUrl(HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        String redirectUri = getRedirectUri(request);

        if (redirectUri != null && !isAuthorizedRedirectUri(redirectUri)) {
            throw new IllegalArgumentException("Unauthorized Redirect URI");
        }

        String targetUrl = redirectUri != null ? redirectUri : getDefaultTargetUrl();

        // Get user from authentication
        CustomOAuth2User oauth2User = (CustomOAuth2User) authentication.getPrincipal();
        String token = jwtService.generateToken(oauth2User.getUser().getEmail());

        return UriComponentsBuilder.fromUriString(targetUrl)
                .queryParam("token", token)
                .build().toUriString();
    }

    private String getRedirectUri(HttpServletRequest request) {
        return authorizationRequestRepository.getCookie(request,
                HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_PARAM_COOKIE_NAME)
                .orElse(null);
    }

    private boolean isAuthorizedRedirectUri(String uri) {
        return java.util.Arrays.stream(authorizedRedirectUris.toArray(new String[0]))
                .anyMatch(authorizedUri -> uri.startsWith(authorizedUri));
    }

    protected void clearAuthenticationAttributes(HttpServletRequest request,
            HttpServletResponse response) {
        super.clearAuthenticationAttributes(request);
        authorizationRequestRepository.removeAuthorizationRequestCookies(request, response);
    }
}
