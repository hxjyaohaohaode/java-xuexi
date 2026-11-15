package io.typeforge;

import io.typeforge.shared.JsonCodec;
import java.util.Map;
import java.util.UUID;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A real password-authenticated persisted session, not a mock Authentication that bypasses lifecycle checks. */
public final class HttpSessionFixture {
    private HttpSessionFixture() {}
    public static MockHttpSession login(MockMvc mvc,JsonCodec json,String username,String password) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/session").session(new PersistentDatabaseSession()).with(csrf()).contentType("application/json").content(json.write(Map.of("username",username,"password",password)))).andReturn();
        assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());
        var session=(MockHttpSession)result.getRequest().getSession(false);assertNotNull(session);return session;
    }
    /** Default MockHttpSession IDs restart at 1 in each JVM; a persisted database must not confuse two test runs. */
    private static final class PersistentDatabaseSession extends MockHttpSession {
        private final String namespace=UUID.randomUUID()+"-";
        @Override public String getId() {return namespace+super.getId();}
        @Override public String changeSessionId() {super.changeSessionId();return getId();}
    }
}
