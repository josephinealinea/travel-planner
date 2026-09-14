package com.josephinealinea.planner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AppProperties props;

    public SecurityConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    JwtCookieAuthFilter authFilter,
                                    CsrfFilter csrfFilter,
                                    PasswordChangeGate passwordGate) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsSource()))
            // Replaced by CsrfFilter, which speaks the double-submit contract
            // the frontend fetch wrapper already implements.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/p/**", "/actuator/health/**").permitAll()
                // Carries no user data and is the same for everyone; the
                // sign-in page may need it before anybody has signed in.
                .requestMatchers(HttpMethod.GET, "/api/v1/config").permitAll()
                .requestMatchers("/api/v1/auth/login",
                                 "/api/v1/auth/logout",
                                 "/api/v1/auth/csrf",
                                 "/api/v1/auth/me").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) -> {
                response.setStatus(401);
                response.setContentType("application/problem+json");
                response.getWriter().write("""
                        {"status":401,"code":"unauthorized","detail":"Please sign in."}""");
            }))
            .addFilterBefore(csrfFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(authFilter, CsrfFilter.class)
            .addFilterAfter(passwordGate, JwtCookieAuthFilter.class);

        return http.build();
    }

    private CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", AuthCookies.CSRF_HEADER));
        // Cookie-based auth across origins requires this.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
