package cn.reviewsystem.security;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
@Configuration public class SecurityConfig {
 @Bean SecurityFilterChain security(HttpSecurity http,IdentityService service)throws Exception{
  http.authorizeHttpRequests(a->a.requestMatchers("/api/health","/api/auth/csrf","/api/auth/register","/api/auth/login").permitAll().requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().authenticated())
  .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->securityError(res, 401, "UNAUTHORIZED", "Authentication required")).accessDeniedHandler((req,res,ex)->securityError(res, 403, "FORBIDDEN", "Access denied")))
  .addFilterBefore(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{var u=service.session(AuthController.token(req));if(u!=null)SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of(new SimpleGrantedAuthority("ROLE_"+u.role()))));chain.doFilter(req,res);}},AnonymousAuthenticationFilter.class)
  .securityContext(c->c.requireExplicitSave(true));return http.build();
 }
 private static void securityError(HttpServletResponse response,int status,String code,String message)throws IOException {
  response.setStatus(status);
  response.setContentType("application/json");
  response.setCharacterEncoding("UTF-8");
  response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");
 }
 @Bean ApplicationRunner initialize(IdentityService service,@Value("${ADMIN_USERNAME:}") String user,@Value("${ADMIN_PASSWORD:}") String password){return args->{if(!user.isEmpty()||!password.isEmpty()){if(!IdentityService.validUsername(user)||!IdentityService.validPassword(password))throw new IllegalStateException("Invalid administrator initialization settings");service.initializeAdmin(user,password);}};}
}
