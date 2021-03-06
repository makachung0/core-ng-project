package core.framework.internal.web.session;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * @author neo
 */
interface SessionStore {
    Map<String, String> getAndRefresh(String sessionId, String domain, Duration sessionTimeout);

    void save(String sessionId, String domain, Map<String, String> values, Set<String> changedFields, Duration sessionTimeout);

    void invalidate(String sessionId, String domain);

    void invalidateByKey(String key, String value);
}
