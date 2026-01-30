package ai.mindvex.backend.security;

import ai.mindvex.backend.dto.OAuth2UserInfo;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = super.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        Map<String, Object> attributes = oauth2User.getAttributes();

        // Get the GitHub access token - we'll store this for later API use
        String accessToken = userRequest.getAccessToken().getTokenValue();

        // Get email - may be null if user has private email
        String email = (String) attributes.get("email");

        // If email is null, fetch from GitHub's emails API
        if (email == null || email.isBlank()) {
            email = fetchGitHubEmail(accessToken);
        }

        OAuth2UserInfo userInfo = OAuth2UserInfo.builder()
                .id(String.valueOf(attributes.get("id")))
                .email(email)
                .name((String) attributes.get("name"))
                .avatarUrl((String) attributes.get("avatar_url"))
                .provider("github")
                .build();

        // Validate email before proceeding
        if (userInfo.getEmail() == null || userInfo.getEmail().isBlank()) {
            log.error("Unable to retrieve email from GitHub for user: {}", userInfo.getId());
            throw new OAuth2AuthenticationException(
                    "Unable to retrieve email from GitHub. Please make sure your GitHub account has a public email or grant email access.");
        }

        // Handle null name - use login/username as fallback
        if (userInfo.getName() == null || userInfo.getName().isBlank()) {
            String login = (String) attributes.get("login");
            userInfo.setName(login != null ? login : "GitHub User");
        }

        // Find or create user, also storing the access token
        User user = processOAuth2User(userInfo, accessToken);

        return new CustomOAuth2User(oauth2User, user);
    }

    /**
     * Fetch the primary email from GitHub's emails API.
     * This is needed when the user has their email set to private.
     */
    private String fetchGitHubEmail(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    "https://api.github.com/user/emails",
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    });

            List<Map<String, Object>> emails = response.getBody();
            if (emails != null) {
                // First try to find the primary email
                for (Map<String, Object> emailObj : emails) {
                    Boolean primary = (Boolean) emailObj.get("primary");
                    Boolean verified = (Boolean) emailObj.get("verified");
                    if (Boolean.TRUE.equals(primary) && Boolean.TRUE.equals(verified)) {
                        return (String) emailObj.get("email");
                    }
                }
                // If no primary, get the first verified email
                for (Map<String, Object> emailObj : emails) {
                    Boolean verified = (Boolean) emailObj.get("verified");
                    if (Boolean.TRUE.equals(verified)) {
                        return (String) emailObj.get("email");
                    }
                }
                // If no verified, get any email
                if (!emails.isEmpty()) {
                    return (String) emails.get(0).get("email");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch email from GitHub emails API: {}", e.getMessage());
        }
        return null;
    }

    private User processOAuth2User(OAuth2UserInfo userInfo, String accessToken) {
        // Check if user exists by provider and provider ID
        Optional<User> userOptional = userRepository
                .findByProviderAndProviderId(userInfo.getProvider(), userInfo.getId());

        if (userOptional.isPresent()) {
            // Update existing user and refresh the access token
            User user = userOptional.get();
            user.setFullName(userInfo.getName());
            user.setAvatarUrl(userInfo.getAvatarUrl());
            user.setGithubAccessToken(accessToken); // Update token on each login
            return userRepository.save(user);
        }

        // Check if user exists by email
        userOptional = userRepository.findByEmail(userInfo.getEmail());
        if (userOptional.isPresent()) {
            // Link OAuth account to existing user
            User user = userOptional.get();
            user.setProvider(userInfo.getProvider());
            user.setProviderId(userInfo.getId());
            user.setAvatarUrl(userInfo.getAvatarUrl());
            user.setGithubAccessToken(accessToken);
            return userRepository.save(user);
        }

        // Create new user with access token
        User newUser = User.builder()
                .email(userInfo.getEmail())
                .fullName(userInfo.getName())
                .provider(userInfo.getProvider())
                .providerId(userInfo.getId())
                .avatarUrl(userInfo.getAvatarUrl())
                .githubAccessToken(accessToken)
                .build();

        return userRepository.save(newUser);
    }
}
