package cn.reviewsystem.api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters=false)
@WebMvcTest(HealthController.class) class HealthControllerTest {
 @Autowired MockMvc mvc;
 @Test void health() throws Exception { mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")); }
 @Test void missing() throws Exception { mvc.perform(get("/api/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND")); }
 @Test void wrongMethod() throws Exception { mvc.perform(post("/api/health")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED")); }
}
