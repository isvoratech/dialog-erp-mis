package lk.hayleys.dialogerp.config;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
  private final JdbcTemplate db;

  public DatabaseUserDetailsService(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    List<UserDetails> users = db.query(
        "select username,password_hash,role,enabled from app_user where lower(username)=lower(?)",
        (rs, n) -> User.withUsername(rs.getString("username"))
            .password(rs.getString("password_hash"))
            .roles(rs.getString("role").toUpperCase())
            .disabled(!rs.getBoolean("enabled"))
            .build(),
        username);
    if (users.isEmpty()) throw new UsernameNotFoundException("Unknown user");
    return users.get(0);
  }
}
