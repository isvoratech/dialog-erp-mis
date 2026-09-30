package lk.hayleys.dialogerp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  SecurityFilterChain api(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/reports/**").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/recovery/**").hasAnyRole("REVIEWER", "FINANCE", "ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/imports/**").hasAnyRole("FINANCE", "ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/billing/**").hasAnyRole("FINANCE", "ADMIN")
            .requestMatchers(HttpMethod.PATCH, "/api/recovery/**").hasAnyRole("REVIEWER", "ADMIN")
            .anyRequest().authenticated())
        .formLogin(f -> f.disable())
        .httpBasic(h -> h.realmName("dialog-erp"))
        .build();
  }
}
