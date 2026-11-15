package cn.reviewsystem.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityHttpErrorTest {
 @LocalServerPort int port;
 @Autowired JdbcTemplate db;
 @Autowired ObjectMapper json;
 private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
 private final String username = "http" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
 private final String password = "test-password-123";
 private String csrfHeader, csrfToken;
 private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
 private HttpResponse<String> get(String path) throws Exception {
  return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
 }
 private HttpResponse<String> post(String path, String body, boolean includeCsrf) throws Exception {
  var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(body));
  if (includeCsrf) request.header(csrfHeader, csrfToken);
  return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
 }
 private void loadCsrf() throws Exception {
  HttpResponse<String> response = get("/api/auth/csrf");
  assertEquals(200, response.statusCode());
  JsonNode body = json.readTree(response.body());
  csrfHeader = body.get("headerName").asText(); csrfToken = body.get("token").asText();
 }
 private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
  assertEquals(status, response.statusCode());
  assertEquals(code, json.readTree(response.body()).get("code").asText());
 }
 @AfterEach void cleanup() {
  db.update("DELETE FROM rs_session WHERE user_id IN (SELECT id FROM rs_user WHERE username=?)", username);
  db.update("DELETE FROM rs_user WHERE username=?", username);
 }
 @Test void csrfAndRoleDenialsHaveCorrectStatusAndPreserveSession() throws Exception {
  assertError(get("/api/me"), 401, "UNAUTHORIZED");
  String credentials = json.writeValueAsString(new Credentials(username, password));
  assertError(post("/api/auth/register", credentials, false), 403, "FORBIDDEN");
  loadCsrf();
  assertEquals(201, post("/api/auth/register", credentials, true).statusCode());
  HttpResponse<String> login = post("/api/auth/login", credentials, true);
  assertEquals(200, login.statusCode());
  long id = json.readTree(login.body()).get("id").asLong();
  assertError(post("/api/admin/users/" + id + "/disable", "{}", true), 403, "FORBIDDEN");
  assertEquals(200, get("/api/me").statusCode());
  assertEquals(200, get("/api/users/" + id).statusCode());
  assertError(post("/api/auth/logout", "{}", false), 403, "FORBIDDEN");
  assertEquals(200, get("/api/me").statusCode());
  assertEquals(204, post("/api/auth/logout", "{}", true).statusCode());
  assertError(get("/api/me"), 401, "UNAUTHORIZED");
 }
 @Test void invalidJsonAndMissingBodiesAreBadRequests() throws Exception {
  loadCsrf();
  for (String path : new String[]{"/api/auth/register", "/api/auth/login"}) {
   for (String body : new String[]{"{", "null", "", "[]", "{\"username\": {}}"}) {
    assertError(post(path, body, true), 400, "BAD_REQUEST");
   }
  }
 }
 record Credentials(String username, String password) {}
}
