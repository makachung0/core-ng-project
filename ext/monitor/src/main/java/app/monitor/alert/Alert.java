package app.monitor.alert;

import core.framework.log.Severity;

/**
 * @author neo
 */
public class Alert {
    public String id;
    public String app;
    public String action;
    public Severity severity;
    public String errorCode;
    public String errorMessage;
    public String kibanaIndex;
    public String host;

    public void severity(String result) {
        severity = "WARN".equals(result) ? Severity.WARN : Severity.ERROR;
    }
}
