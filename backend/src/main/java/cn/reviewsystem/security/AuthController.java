package cn.reviewsystem.security;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController public class AuthController {
 private final IdentityService service; private final boolean secure; private final Map<String,Deque<Long>> failures=new LinkedHashMap<>();
 private static final String DUMMY="$2a$10$7EqJtq98hPqEX7fNZaFWoO5uVZCr1IIF4N3vJ.90ypkQIl9kplHnK";
 public AuthController(IdentityService service,@Value("${COOKIE_SECURE:false}") boolean secure){this.service=service;this.secure=secure;}
 public record Credentials(String username,String password,String role){} public record UserView(long id,String username,String role){}
 static String token(HttpServletRequest req){if(req.getCookies()!=null)for(Cookie c:req.getCookies())if(c.getName().equals("RSID"))return c.getValue();return null;}
 private void cookie(HttpServletResponse res,String value,int age){res.addHeader("Set-Cookie",ResponseCookie.from("RSID",value).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(age).build().toString());}
 @GetMapping("/api/auth/csrf") public Map<String,String> csrf(CsrfToken t){return Map.of("token",t.getToken(),"headerName",t.getHeaderName());}
 @PostMapping("/api/auth/register") public ResponseEntity<?> register(@RequestBody Credentials c){if(!IdentityService.validUsername(c.username())||!IdentityService.validPassword(c.password())||c.role()!=null)return ResponseEntity.badRequest().build();try{service.register(c.username(),c.password());return ResponseEntity.status(201).build();}catch(DuplicateKeyException e){return ResponseEntity.status(409).build();}}
 @PostMapping("/api/auth/login") public ResponseEntity<?> login(@RequestBody Credentials c,HttpServletRequest req,HttpServletResponse res){String ip=req.getRemoteAddr();synchronized(failures){long now=System.currentTimeMillis();failures.entrySet().removeIf(e->{e.getValue().removeIf(t->t<now-900000);return e.getValue().isEmpty();});if(failures.containsKey(ip)&&failures.get(ip).size()>=5)return ResponseEntity.status(429).build();if(!failures.containsKey(ip)&&failures.size()>=10000)return ResponseEntity.status(429).build();var u=c.username()==null?null:service.find(c.username());boolean valid=service.matches(c.password(),u==null?DUMMY:u.passwordHash());if(!valid||u==null||!u.enabled()){failures.computeIfAbsent(ip,k->new ArrayDeque<>()).add(now);return ResponseEntity.status(401).build();}failures.remove(ip);service.logout(token(req));cookie(res,service.newSession(u),1800);return ResponseEntity.ok(new UserView(u.id(),u.username(),u.role()));}}
 @PostMapping("/api/auth/logout") public ResponseEntity<?> logout(HttpServletRequest req,HttpServletResponse res){service.logout(token(req));cookie(res,"",0);req.getSession().invalidate();return ResponseEntity.noContent().build();}
 @GetMapping("/api/me") public UserView me(Authentication a){var u=(IdentityService.User)a.getPrincipal();return new UserView(u.id(),u.username(),u.role());}
 @GetMapping("/api/users/{id}") public ResponseEntity<?> user(@PathVariable long id,Authentication a){var u=(IdentityService.User)a.getPrincipal();if(u.id()!=id)return ResponseEntity.status(403).build();return ResponseEntity.ok(new UserView(u.id(),u.username(),u.role()));}
 @PostMapping("/api/admin/users/{id}/disable") public ResponseEntity<?> disable(@PathVariable long id){return service.disable(id)?ResponseEntity.noContent().build():ResponseEntity.notFound().build();}
}
