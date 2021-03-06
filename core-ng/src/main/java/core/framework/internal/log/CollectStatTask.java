package core.framework.internal.log;

import core.framework.internal.log.appender.LogAppender;
import core.framework.internal.stat.StatCollector;
import core.framework.internal.stat.Stats;
import core.framework.log.message.StatMessage;
import core.framework.util.Network;

import java.time.Instant;

/**
 * @author neo
 */
public final class CollectStatTask implements Runnable {
    private final LogAppender appender;
    private final StatCollector collector;

    public CollectStatTask(LogAppender appender, StatCollector collector) {
        this.appender = appender;
        this.collector = collector;
    }

    @Override
    public void run() {
        Stats stats = collector.collect();
        StatMessage message = message(stats);
        appender.append(message);
    }

    StatMessage message(Stats stats) {
        var message = new StatMessage();
        var now = Instant.now();
        message.date = now;
        message.id = LogManager.ID_GENERATOR.next(now);
        message.result = stats.result();
        message.app = LogManager.APP_NAME;
        message.host = Network.LOCAL_HOST_NAME;
        message.errorCode = stats.errorCode;
        message.errorMessage = stats.errorMessage;
        message.stats = stats.stats;
        message.info = stats.info;
        return message;
    }
}
