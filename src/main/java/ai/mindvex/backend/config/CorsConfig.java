package ai.mindvex.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS configuration driven entirely by the CORS_ORIGINS environment variable.
 *
 * Set CORS_ORIGINS to a comma-separated list of allowed origins, e.g.:
 * Local: CORS_ORIGINS=http://localhost:5173,http://localhost:3000
 * Render: CORS_ORIGINS=http://localhost:5173,https://mindvex-editor.pages.dev
 *
 * No values are hardcoded here — only the .env / Render env var needs to
 * change.
 */
@Configuration
public class CorsConfig {

    /** Raw comma-separated origins string from CORS_ORIGINS env var. */
    @Value("${cors.allowed-origins-str}")
    private String allowedOriginsStr;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOriginsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}