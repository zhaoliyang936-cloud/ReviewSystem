package cn.reviewsystem.security;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.http.MediaType;
@SpringBootTest @AutoConfigureMockMvc
class IdentityIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired IdentityService service;
 String prefix="t"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,12); String password="valid-password-123";
 String json(String name,String extra){return "{\"username\":\""+name+"\",\"password\":\""+password+"\""+extra+"}";}
 void register(String name)throws Exception{mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(name,""))).andExpect(status().isCreated());}
 Cookie login(String name)throws Exception{return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(name,""))).andExpect(status().isOk()).andReturn().getResponse().getCookie("RSID");}
 @AfterEach void cleanup(){db.update("DELETE FROM rs_session WHERE user_id IN (SELECT id FROM rs_user WHERE username LIKE ?)",prefix+"%");db.update("DELETE FROM rs_user WHERE username LIKE ?",prefix+"%");}
 @Test void authorizationLifecycle()throws Exception{
  String a=prefix+"a",b=prefix+"b",admin=prefix+"admin";register(a);register(b);
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(prefix+"evil",",\"role\":\"ADMIN\""))).andExpect(status().isBadRequest());
  assertNull(service.find(prefix+"evil"));
  var user=service.find(a);assertNotEquals(password,user.passwordHash());assertTrue(user.passwordHash().startsWith("$2"));assertTrue(service.matches(password,user.passwordHash()));
  mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  Cookie session=login(a);assertNotNull(session);mvc.perform(get("/api/me").cookie(session)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value(a));
  mvc.perform(post("/api/admin/users/"+user.id()+"/disable").cookie(session).with(csrf())).andExpect(status().isForbidden());
  mvc.perform(get("/api/users/"+service.find(b).id()).cookie(session)).andExpect(status().isForbidden());
  mvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isForbidden());
  mvc.perform(post("/api/auth/logout").cookie(session).with(csrf())).andExpect(status().isNoContent());
  mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
  session=login(a);db.update("INSERT INTO rs_user(username,password_hash,role) VALUES (?,?,'ADMIN')",admin,user.passwordHash());Cookie adminSession=login(admin);
  mvc.perform(post("/api/admin/users/"+user.id()+"/disable").cookie(adminSession).with(csrf())).andExpect(status().isNoContent());
  mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(a,""))).andExpect(status().isUnauthorized());
 }
 @Test void expiryAndRateLimit()throws Exception{
  String name=prefix+"a";register(name);Cookie session=login(name);
  db.update("UPDATE rs_session SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE user_id=?",service.find(name).id());
  mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
  for(int i=0;i<5;i++)mvc.perform(post("/api/auth/login").with(csrf()).with(r->{r.setRemoteAddr("192.0.2.1");return r;}).contentType(MediaType.APPLICATION_JSON).content(json(prefix+"missing",""))).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/login").with(csrf()).with(r->{r.setRemoteAddr("192.0.2.1");return r;}).contentType(MediaType.APPLICATION_JSON).content(json(name,""))).andExpect(status().isTooManyRequests());
 }
 @Test void csrfIsRequired()throws Exception{mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(prefix+"a",""))).andExpect(status().isForbidden());}
}
