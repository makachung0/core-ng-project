package core.framework.internal.web.response;

import io.undertow.io.Sender;

/**
 * @author neo
 */
interface Body {
    void send(Sender sender, ResponseHandlerContext context);
}
