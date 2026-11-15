package cn.reviewsystem.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class IdentityExtendedTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired IdentityService service; @Autowired ObjectMapper mapper;
 final String prefix="x"+UUID.randomUUID().toString().replace("-","").substring(0,12);
 final String pass="correct-password-123";
 String json(Object value)throws Exception {return mapper.writeValueAsString(value);}
 String credentials(String user,String password)throws Exception{return json(Map.of("username",user,"password",password));}
 void register(String user,String password)throws Exception{mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(credentials(user,password))).andExpect(status().isCreated());}
 Cookie login(String user,String password)throws Exception{return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(credentials(user,password))).andExpect(status().isOk()).andReturn().getResponse().getCookie("RSID");}
 void addAdmin(String name){db.update("INSERT INTO rs_user(username,password_hash,role) VALUES (?,?,'ADMIN')",name,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(pass));}
 @AfterEach void clean(){db.update("DELETE FROM rs_session WHERE user_id IN (SELECT id FROM rs_user WHERE username LIKE ?)",prefix+"%");db.update("DELETE FROM rs_user WHERE username LIKE ?",prefix+"%");}
 @Test void usernameAndPasswordValidation()throws Exception {
  for(String user:List.of("", "ab", "abc def", "abc-xyz", "abc@example", "学生账号", "a".repeat(41), "abc' OR 1=1--"))
   mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(credentials(user,pass))).andExpect(status().isBadRequest());
  for(String password:List.of("", "a".repeat(11), "a".repeat(73), "中".repeat(25)))
   mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(credentials(prefix+"a",password))).andExpect(status().isBadRequest());
  register(prefix+"_", "a".repeat(12)); register(prefix+"b", "a".repeat(72)); register(prefix+"c", "中".repeat(24));
  register(prefix+"d".repeat(40-prefix.length()),pass);
 }
 @Test void duplicateAndRoleEscalation()throws Exception {
  register(prefix+"a",pass);
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(credentials(prefix+"a",pass))).andExpect(status().isConflict());
  for(String role:List.of("ADMIN","STUDENT","admin","ROLE_ADMIN",""))
   mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("username",prefix+"bad","password",pass,"role",role)))).andExpect(status().isBadRequest());
  assertNull(service.find(prefix+"bad")); assertEquals("STUDENT",service.find(prefix+"a").role());
 }
 @Test void databaseStoresSaltedBcryptAndHashedSession()throws Exception {
  register(prefix+"a",pass);register(prefix+"b",pass);
  String a=service.find(prefix+"a").passwordHash(), b=service.find(prefix+"b").passwordHash();
  assertTrue(a.matches("\\$2[aby]\\$[0-9]{2}\\$.{53}")); assertNotEquals(a,b);assertTrue(service.matches(pass,a));assertFalse(service.matches(pass+"!",a));
  Cookie cookie=login(prefix+"a",pass);String token=cookie.getValue();
  String stored=db.queryForObject("SELECT token_hash FROM rs_session WHERE user_id=?",String.class,service.find(prefix+"a").id());
  assertNotEquals(token,stored);assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))),stored);
 }
 @Test void sessionIsolationExpiryAndMultipleSessions()throws Exception {
  register(prefix+"a",pass);register(prefix+"b",pass);Cookie one=login(prefix+"a",pass),two=login(prefix+"a",pass),other=login(prefix+"b",pass);
  assertNotEquals(one.getValue(),two.getValue());
  mvc.perform(get("/api/users/"+service.find(prefix+"a").id()).cookie(one)).andExpect(status().isOk());
  mvc.perform(get("/api/users/"+service.find(prefix+"b").id()).cookie(one)).andExpect(status().isForbidden());
  for(String token:List.of("", "a".repeat(42),"a".repeat(43),one.getValue()+"!"))mvc.perform(get("/api/me").cookie(new Cookie("RSID",token))).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/logout").with(csrf()).cookie(one)).andExpect(status().isNoContent());
  mvc.perform(get("/api/me").cookie(one)).andExpect(status().isUnauthorized());mvc.perform(get("/api/me").cookie(two)).andExpect(status().isOk());mvc.perform(get("/api/me").cookie(other)).andExpect(status().isOk());
  db.update("UPDATE rs_session SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE user_id=?",service.find(prefix+"a").id());
  mvc.perform(get("/api/me").cookie(two)).andExpect(status().isUnauthorized());mvc.perform(get("/api/me").cookie(other)).andExpect(status().isOk());
 }
 @Test void disableRevokesEverySessionAndProtectsAdmins()throws Exception {
  register(prefix+"a",pass);Cookie a=login(prefix+"a",pass),b=login(prefix+"a",pass);addAdmin(prefix+"admin");Cookie admin=login(prefix+"admin",pass);
  long id=service.find(prefix+"a").id(),adminId=service.find(prefix+"admin").id();
  mvc.perform(post("/api/admin/users/"+id+"/disable").cookie(a).with(csrf())).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/users/"+adminId+"/disable").cookie(admin).with(csrf())).andExpect(status().isNotFound());mvc.perform(get("/api/me").cookie(admin)).andExpect(status().isOk());
  mvc.perform(post("/api/admin/users/"+id+"/disable").cookie(admin).with(csrf())).andExpect(status().isNoContent());
  mvc.perform(get("/api/me").cookie(a)).andExpect(status().isUnauthorized());mvc.perform(get("/api/me").cookie(b)).andExpect(status().isUnauthorized());
  assertEquals(0,db.queryForObject("SELECT count(*) FROM rs_session WHERE user_id=?",Integer.class,id));
  mvc.perform(post("/api/auth/login").with(csrf()).with(r->{r.setRemoteAddr("198.51.100.12");return r;}).contentType(MediaType.APPLICATION_JSON).content(credentials(prefix+"a",pass))).andExpect(status().isUnauthorized());
 }
 @Test void actualCsrfTokenBoundToSessionAndCookieFlags()throws Exception {
  var csrfResult=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
  var body=mapper.readTree(csrfResult.getResponse().getContentAsString());String header=body.get("headerName").asText(), token=body.get("token").asText();
  MockHttpSession session=(MockHttpSession)csrfResult.getRequest().getSession(false); assertNotNull(session);
  mvc.perform(post("/api/auth/register").session(session).header(header,"wrong").contentType(MediaType.APPLICATION_JSON).content(credentials(prefix+"a",pass))).andExpect(status().isForbidden());
  mvc.perform(post("/api/auth/register").header(header,token).contentType(MediaType.APPLICATION_JSON).content(credentials(prefix+"a",pass))).andExpect(status().isForbidden());
  mvc.perform(post("/api/auth/register").session(session).header(header,token).contentType(MediaType.APPLICATION_JSON).content(credentials(prefix+"a",pass))).andExpect(status().isCreated());
  var response=mvc.perform(post("/api/auth/login").session(session).header(header,token).contentType(MediaType.APPLICATION_JSON).content(credentials(prefix+"a",pass))).andExpect(status().isOk()).andReturn().getResponse();
  String setCookie=response.getHeader("Set-Cookie"); assertNotNull(setCookie);assertTrue(setCookie.contains("HttpOnly"));assertTrue(setCookie.contains("SameSite=Lax"));assertTrue(setCookie.contains("Max-Age=1800"));assertFalse(setCookie.contains("Secure"));
  mvc.perform(post("/api/auth/logout").session(session).cookie(response.getCookie("RSID")).header(header,token)).andExpect(status().isNoContent());
 }
 @Test void loginFailuresAreGenericAndRateLimitedByDirectIp()throws Exception {
  register(prefix+"a",pass);
  for(int i=0;i<5;i++) {String name=i%2==0?prefix+"a":prefix+"missing";mvc.perform(post("/api/auth/login").with(csrf()).with(r->{r.setRemoteAddr("198.51.100.22");return r;}).contentType(MediaType.APPLICATION_JSON).content(credentials(name,"wrong-password"))).andExpect(status().isUnauthorized());}
  mvc.perform(post("/api/auth/login").with(csrf()).with(r->{r.setRemoteAddr("198.51.100.22");return r;}).contentType(MediaType.APPLICATION_JSON).content(credentials(prefix+"a",pass))).andExpect(status().isTooManyRequests());
  mvc.perform(post("/api/auth/login").with(csrf()).with(r->{r.setRemoteAddr("198.51.100.23");return r;}).contentType(MediaType.APPLICATION_JSON).content(credentials(prefix+"a",pass))).andExpect(status().isOk());
 }
}
