package lk.hayleys.dialogerp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminBootstrapConfig {
  @Bean
  ApplicationRunner bootstrapAdmin(
      JdbcTemplate db,
      PasswordEncoder encoder,
      @Value("${ADMIN_USERNAME:}") String username,
      @Value("${ADMIN_PASSWORD:}") String password) {
    return args -> {
      Integer count = db.queryForObject("select count(*) from app_user", Integer.class);
      if (count != null && count > 0) return;
      if (username == null || username.isBlank() || password == null || password.isBlank() || "change-me-before-use".equals(password)) {
        throw new IllegalStateException("app_user is empty and secure ADMIN_USERNAME/ADMIN_PASSWORD are not configured");
      }
      db.update(
          "insert into app_user(username,password_hash,role,enabled) values(?,?,?,true)",
          username.trim(),
          encoder.encode(password),
          "ADMIN");
    };
  }
}
