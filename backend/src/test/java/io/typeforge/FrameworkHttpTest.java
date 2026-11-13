package io.typeforge;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
class FrameworkHttpTest {
    @Autowired private MockMvc mvc;
    @Test void exposesOnlyHonestScaffoldStatus() throws Exception {
        mvc.perform(get("/api/v1/framework")).andExpect(status().isOk())
            .andExpect(jsonPath("$.businessReady").value(false))
            .andExpect(jsonPath("$.modules.length()").value(16));
    }
    @Test void healthDoesNotClaimDatabaseReadiness() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("process-only; database/model/worker not connected"));
    }
    @Test void noBusinessOrAdminRouteIsAccidentallyPublic() throws Exception {
        mvc.perform(get("/api/v1/attempts/example")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/releases").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
}
