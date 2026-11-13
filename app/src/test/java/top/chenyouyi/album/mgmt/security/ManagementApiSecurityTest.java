package top.chenyouyi.album.mgmt.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Iterator;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import top.chenyouyi.album.mgmt.common.CorrelationId;
import top.chenyouyi.album.mgmt.common.ForbiddenFields;

/**
 * 契约 managementApi + identity 的端到端安全矩阵测试（真实过滤器链 + fixture provider）：
 * 1. /api/v1/health 公开；2. 无令牌 401 missing_bearer_token、坏令牌 401 invalid_access_token；
 * 3. viewer 读 resources 200 / 读 logs 403；auditor 读 audit 200 / 读 billing 403；
 * 4. admin 七域全 200；5. correlationId 透传并回显（头与 body 一致，格式 corr_[0-9a-f]{16}）；
 * 6. 审计事件真实落库（domain/outcome/correlationId 可查）；7. 域响应无 forbidden 键；
 * 8. refresh 换发新令牌可用。
 *
 * End-to-end security matrix for contract managementApi + identity (real filter chain +
 * fixture providers): public health; 401 missing/invalid tokens; viewer 200 on resources
 * but 403 on logs; auditor 200 on audit but 403 on billing; admin 200 on all seven
 * domains; correlationId propagated and echoed (header matches body, corr_[0-9a-f]{16});
 * audit events really recorded; no forbidden keys in domain responses; refresh round-trip.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManagementApiSecurityTest {

    private static final String CORR = "corr_0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 契约 identity.sharedKey：所有演示账户共用一把共享口令 / contract identity.sharedKey for all demo accounts. */
    private static final String SHARED_KEY = "album-mgmt-demo";

    private String login(String principal) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"principal\":\"" + principal + "\",\"password\":\"" + SHARED_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value(principal))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText();
    }

    private String tokenFor(String principal) throws Exception {
        return login(principal);
    }

    /** 递归断言响应 JSON 中不存在 forbidden 键 / Recursively assert no forbidden keys in the JSON. */
    private static void assertNoForbiddenKeys(JsonNode node, String path) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (ForbiddenFields.isForbiddenKey(key)) {
                    throw new AssertionError("响应含 forbidden 键 " + key + " @ " + path
                            + " / response contains forbidden key at " + path);
                }
                assertNoForbiddenKeys(field.getValue(), path + "." + key);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertNoForbiddenKeys(node.get(i), path + "[" + i + "]");
            }
        }
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("album-mgmt"))
                .andExpect(jsonPath("$.mode").value("read-only"));
    }

    @Test
    void loginIssuesTypedTokensAndRejectsBadCredentials() throws Exception {
        // 正确共享口令 / correct shared key
        MvcResult ok = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"principal\":\"admin\",\"password\":\"" + SHARED_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.role").value("admin"))
                .andReturn();

        JsonNode body = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertTrue(body.path("accessToken").asText().split("\\.").length == 3, "access token 应为 JWT 三段式");
        assertTrue(body.path("refreshToken").asText().split("\\.").length == 3, "refresh token 应为 JWT 三段式");

        // 错误共享口令 / wrong shared key
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"principal\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));

        // 白名单外主体 / principal outside the allowlist
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"principal\":\"intruder\",\"password\":\"" + SHARED_KEY + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void domainWithoutTokenIs401AndEchoesCorrelationId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/resources"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_bearer_token"))
                .andReturn();

        String echoed = result.getResponse().getHeader(CorrelationId.HEADER);
        assertTrue(echoed != null && CorrelationId.isValid(echoed),
                "401 也必须回显合法 correlationId / even 401 must echo a valid correlationId");
    }

    @Test
    void domainWithGarbageTokenIs401() throws Exception {
        mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_access_token"));

        // access 令牌拿去当 refresh 校验域请求同样 401 / access token alone must still fail type check
        mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer a.b.c"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_access_token"));
    }

    @Test
    void viewerCanReadResourcesButNotLogs() throws Exception {
        String token = tokenFor("viewer");

        MvcResult ok = mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("fixture"))
                .andExpect(jsonPath("$.unavailable").doesNotExist())
                .andReturn();
        JsonNode body = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertTrue(body.path("correlationId").asText("").startsWith("corr_"), "envelope 必须带 correlationId");
        assertTrue(body.path("items").isArray(), "envelope 必须带 items 数组");

        mockMvc.perform(get("/api/v1/logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("domain_permission_denied"));
    }

    @Test
    void auditorCanReadAuditAndSecurityButNotBilling() throws Exception {
        String token = tokenFor("auditor");

        mockMvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("fixture"));

        mockMvc.perform(get("/api/v1/security").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("fixture"));

        mockMvc.perform(get("/api/v1/billing").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("domain_permission_denied"));
    }

    @Test
    void adminCanReadEveryDomain() throws Exception {
        String token = tokenFor("admin");

        // fixture 数据域：source=fixture / fixture-backed domains
        for (String path : new String[]{
                "/api/v1/cicd/workflows", "/api/v1/resources", "/api/v1/metrics",
                "/api/v1/security", "/api/v1/audit"}) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.source").value("fixture"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }

        // 契约降级：本地档 logs/billing 明确 unavailable，绝不伪造数据
        // Contract degradation: local-profile logs/billing report unavailable, data never faked
        for (String path : new String[]{"/api/v1/logs", "/api/v1/billing"}) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.source").value("unavailable"))
                    .andExpect(jsonPath("$.unavailable").isNotEmpty())
                    .andExpect(jsonPath("$.items").isArray());
        }
    }

    @Test
    void correlationIdIsPropagatedAndEchoed() throws Exception {
        String token = tokenFor("viewer");

        MvcResult result = mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer " + token)
                        .header(CorrelationId.HEADER, CORR))
                .andExpect(status().isOk())
                .andReturn();

        // 头回显 / header echo
        String echoed = result.getResponse().getHeader(CorrelationId.HEADER);
        assertEquals(CORR, echoed, "客户端合法 correlationId 必须原样透传");

        // body 与头一致 / body matches header
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(CORR, body.path("correlationId").asText(),
                "envelope.correlationId 必须与响应头一致");

        // 客户端非法值 → 服务端生成新值 / invalid client value → server generates one
        MvcResult generated = mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer " + token)
                        .header(CorrelationId.HEADER, "not-valid"))
                .andExpect(status().isOk())
                .andReturn();
        String newId = generated.getResponse().getHeader(CorrelationId.HEADER);
        assertTrue(CorrelationId.isValid(newId), "服务端必须生成 corr_[0-9a-f]{16} 格式的新值");
        assertTrue(!CORR.equals(newId), "新生成的 correlationId 不应等于注入值");
    }

    @Test
    void auditTrailRecordsDomainReads() throws Exception {
        String token = tokenFor("admin");

        mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer " + token)
                        .header(CorrelationId.HEADER, CORR))
                .andExpect(status().isOk());

        MvcResult auditResult = mockMvc.perform(get("/api/v1/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode audit = objectMapper.readTree(auditResult.getResponse().getContentAsString());
        JsonNode items = audit.path("items");
        assertTrue(items.isArray() && items.size() > 0, "审计列表不应为空");

        boolean found = false;
        for (JsonNode event : items) {
            if (CORR.equals(event.path("correlationId").asText())
                    && "resources".equals(event.path("domain").asText())) {
                found = true;
                assertEquals("allow", event.path("outcome").asText(), "200 请求 outcome 应为 allow");
                assertEquals("read", event.path("action").asText(), "审计事件应记录动作");
                assertTrue(event.has("who") && !event.path("who").asText().isEmpty(), "审计事件应有 who");
                assertTrue(event.has("when"), "审计事件应有 when");
                break;
            }
        }
        assertTrue(found, "本次 resources 读取必须出现在审计事件中（correlationId=" + CORR + "）");
    }

    @Test
    void domainResponsesCarryNoForbiddenKeys() throws Exception {
        String token = tokenFor("admin");

        for (String path : new String[]{
                "/api/v1/resources", "/api/v1/logs", "/api/v1/audit", "/api/v1/security"}) {
            MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            assertNoForbiddenKeys(
                    objectMapper.readTree(result.getResponse().getContentAsString()), path);
        }
    }

    @Test
    void refreshTokenRoundTripIssuesUsableAccess() throws Exception {
        // viewer 登录拿 refresh / viewer logs in to obtain a refresh token
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"principal\":\"viewer\",\"password\":\"" + SHARED_KEY + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("refreshToken").asText();

        // 用 refresh 换新令牌 / exchange the refresh token
        MvcResult refresh = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("viewer"))
                .andReturn();
        String newAccess = objectMapper.readTree(refresh.getResponse().getContentAsString())
                .path("accessToken").asText();

        // 新 access 可读 viewer 权限域 / the new access token can read a viewer domain
        mockMvc.perform(get("/api/v1/cicd/workflows")
                        .header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("fixture"));

        // refresh 不能当 access 用 / the refresh token must not work as an access token
        mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_access_token"));
    }

    @Test
    void auditRecordsDenialsWithAnonymousActor() throws Exception {
        // 未授权请求也进审计（who=anonymous, outcome=deny）/ denied requests are audited too
        mockMvc.perform(get("/api/v1/billing")
                        .header(CorrelationId.HEADER, CORR))
                .andExpect(status().isUnauthorized());

        String token = tokenFor("admin");
        MvcResult auditResult = mockMvc.perform(get("/api/v1/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode audit = objectMapper.readTree(auditResult.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode event : audit.path("items")) {
            if (CORR.equals(event.path("correlationId").asText())
                    && "billing".equals(event.path("domain").asText())) {
                found = true;
                assertEquals("deny", event.path("outcome").asText(), "401 请求 outcome 应为 deny");
                assertEquals("anonymous", event.path("who").asText(), "未授权请求 who 应为 anonymous");
                break;
            }
        }
        assertTrue(found, "401 拒绝也应落审计 / a 401 denial must also be audited");
    }
}
