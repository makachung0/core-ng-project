package core.framework.internal.web.session;

import core.framework.internal.log.ActionLog;
import core.framework.web.CookieSpec;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author neo
 */
@ExtendWith(MockitoExtension.class)
class SessionManagerTest {
    @Mock
    Request request;
    @Mock
    Response response;
    private SessionManager sessionManager;
    private LocalSessionStore store;

    @BeforeEach
    void createSessionManager() {
        store = new LocalSessionStore();
        sessionManager = new SessionManager();
        sessionManager.store(store);
    }

    @Test
    void header() {
        sessionManager.header("SessionId");
        ActionLog actionLog = new ActionLog(null);

        String sessionId = UUID.randomUUID().toString();
        store.save(sessionId, "localhost", Map.of("key", "value"), Set.of(), Duration.ofMinutes(30));

        when(request.scheme()).thenReturn("https");
        when(request.header("SessionId")).thenReturn(Optional.of(sessionId));

        Session session = sessionManager.load(request, actionLog);
        assertThat(session).isNotNull();
        assertThat(session.get("key")).isNotNull().hasValue("value");
        assertThat(actionLog.context.get("session_hash")).isNotEmpty();
    }

    @Test
    void cookie() {
        sessionManager.cookie("SessionId", null);

        String sessionId = UUID.randomUUID().toString();
        store.save(sessionId, "localhost", Map.of("key", "value"), Set.of(), Duration.ofMinutes(30));

        when(request.scheme()).thenReturn("https");
        when(request.cookie(eq(new CookieSpec("SessionId").path("/")))).thenReturn(Optional.of(sessionId));

        Session session = sessionManager.load(request, new ActionLog(null));
        assertThat(session).isNotNull();
        assertThat(session.get("key")).isNotNull().hasValue("value");
    }

    @Test
    void putSessionIdToHeader() {
        sessionManager.header("SessionId");

        String sessionId = UUID.randomUUID().toString();
        sessionManager.putSessionId(response, sessionId);

        verify(response).header("SessionId", sessionId);
    }

    @Test
    void putSessionIdToCookie() {
        sessionManager.cookie("SessionId", null);

        String sessionId = UUID.randomUUID().toString();
        sessionManager.putSessionId(response, sessionId);

        verify(response).cookie(eq(new CookieSpec("SessionId").path("/")), eq(sessionId));
    }

    @Test
    void saveWithInvalidatedSessionWithoutId() {
        var session = new SessionImpl("localhost");
        session.invalidate();
        sessionManager.save(session, response, new ActionLog(null));

        verifyNoInteractions(response);
    }

    @Test
    void saveWithInvalidatedSession() {
        sessionManager.header("SessionId");

        var session = new SessionImpl("localhost");
        session.id = UUID.randomUUID().toString();
        session.invalidate();
        sessionManager.save(session, response, new ActionLog(null));

        verify(response).header("SessionId", "");
    }

    @Test
    void saveNewSession() {
        sessionManager.header("SessionId");
        ActionLog actionLog = new ActionLog(null);

        var session = new SessionImpl("localhost");
        session.set("key", "value");
        sessionManager.save(session, response, actionLog);

        assertThat(actionLog.context.get("session_hash")).isNotEmpty();
        verify(response).header(eq("SessionId"), anyString());
    }

    @Test
    void invalidate() {
        assertThatThrownBy(() -> sessionManager.invalidate(null, null))
                .isInstanceOf(Error.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void domainWithHeader() {
        sessionManager.header("SessionId");
        sessionManager.cookie("SessionId", "example.com");

        when(request.hostName()).thenReturn("api.example.com");
        assertThat(sessionManager.domain(request)).isEqualTo("api.example.com");
    }

    @Test
    void domainWithCookie() {
        when(request.hostName()).thenReturn("www.example.com");

        sessionManager.cookie("SessionId", "example.com");
        assertThat(sessionManager.domain(request)).isEqualTo("example.com");

        sessionManager.cookie("SessionId", null);
        assertThat(sessionManager.domain(request)).isEqualTo("www.example.com");
    }
}
