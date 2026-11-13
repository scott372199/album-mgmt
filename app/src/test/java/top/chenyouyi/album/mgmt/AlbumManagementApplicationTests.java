package top.chenyouyi.album.mgmt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AlbumManagementApplicationTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void exposesPublicReadOnlySnapshot() throws Exception {
        mockMvc.perform(get("/api/management/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].kind").value("ECS Express"))
                .andExpect(jsonPath("$.resources[0].source").value("fixture"))
                .andExpect(jsonPath("$.workflows[0].repository").value("album"));
    }

    @Test
    void includesCorrelationIdForPublicReadOnlySnapshot() throws Exception {
        mockMvc.perform(get("/api/management/snapshot")
                        .header("X-Correlation-Id", "test-correlation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].kind").value("ECS Express"))
                .andExpect(jsonPath("$.resources[0].source").value("fixture"))
                .andExpect(jsonPath("$.workflows[0].repository").value("album"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Correlation-Id", "test-correlation"));
    }
}
