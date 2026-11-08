package cn.reviewsystem.security;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class IdentityService {
 public record User(long id,String username,String role,boolean enabled,String passwordHash) {}
 private final JdbcTemplate db; private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(); private final SecureRandom random=new SecureRandom();
 public IdentityService(JdbcTemplate db){this.db=db;}
 public static boolean validUsername(String s){return s!=null && s.matches("[A-Za-z0-9_]{3,40}");}
 public static boolean validPassword(String s){return s!=null && s.length()>=12 && s.getBytes(StandardCharsets.UTF_8).length<=72;}
 private User one(String sql,Object value){var users=db.query(sql,(r,n)->new User(r.getLong("id"),r.getString("username"),r.getString("role"),r.getBoolean("enabled"),r.getString("password_hash")),value);return users.isEmpty()?null:users.get(0);}
 public User find(String name){return one("SELECT * FROM rs_user WHERE username=?",name);}
 public void register(String name,String password){db.update("INSERT INTO rs_user(username,password_hash,role) VALUES (?,?,'STUDENT')",name,encoder.encode(password));}
 public boolean matches(String password,String hash){return password!=null && password.getBytes(StandardCharsets.UTF_8).length<=72 && encoder.matches(password,hash);}
 public String newSession(User user){byte[] b=new byte[32];random.nextBytes(b);String t=Base64.getUrlEncoder().withoutPadding().encodeToString(b);db.update("INSERT INTO rs_session(token_hash,user_id,expires_at) VALUES (?,?,?)",hash(t),user.id(),java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));return t;}
 public User session(String t){if(t==null||!t.matches("[A-Za-z0-9_-]{43}"))return null;return one("SELECT u.* FROM rs_user u JOIN rs_session s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.enabled",hash(t));}
 public void logout(String t){if(t!=null)db.update("DELETE FROM rs_session WHERE token_hash=?",hash(t));}
 @Transactional public boolean disable(long id){int n=db.update("UPDATE rs_user SET enabled=false WHERE id=? AND role='STUDENT'",id);if(n>0)db.update("DELETE FROM rs_session WHERE user_id=?",id);return n>0;}
 @Transactional public void initializeAdmin(String name,String password){db.execute("SELECT pg_advisory_xact_lock(87102401)");Integer n=db.queryForObject("SELECT count(*) FROM rs_user WHERE role='ADMIN'",Integer.class);if(n!=null&&n==0)db.update("INSERT INTO rs_user(username,password_hash,role) VALUES (?,?,'ADMIN')",name,encoder.encode(password));}
 private String hash(String t){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
