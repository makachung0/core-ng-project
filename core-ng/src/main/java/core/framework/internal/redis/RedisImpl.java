package core.framework.internal.redis;

import core.framework.internal.log.filter.ArrayLogParam;
import core.framework.internal.log.filter.BytesLogParam;
import core.framework.internal.log.filter.BytesValueMapLogParam;
import core.framework.internal.log.filter.MapLogParam;
import core.framework.internal.resource.Pool;
import core.framework.internal.resource.PoolItem;
import core.framework.log.ActionLogContext;
import core.framework.log.Markers;
import core.framework.redis.Redis;
import core.framework.redis.RedisAdmin;
import core.framework.redis.RedisHash;
import core.framework.redis.RedisList;
import core.framework.redis.RedisSet;
import core.framework.util.Maps;
import core.framework.util.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import static core.framework.internal.redis.Protocol.Command.DEL;
import static core.framework.internal.redis.Protocol.Command.GET;
import static core.framework.internal.redis.Protocol.Command.INCRBY;
import static core.framework.internal.redis.Protocol.Command.MGET;
import static core.framework.internal.redis.Protocol.Command.MSET;
import static core.framework.internal.redis.Protocol.Command.PEXPIRE;
import static core.framework.internal.redis.Protocol.Command.PTTL;
import static core.framework.internal.redis.Protocol.Command.PUBLISH;
import static core.framework.internal.redis.Protocol.Command.SCAN;
import static core.framework.internal.redis.Protocol.Command.SET;
import static core.framework.internal.redis.Protocol.Keyword.COUNT;
import static core.framework.internal.redis.Protocol.Keyword.MATCH;
import static core.framework.internal.redis.Protocol.Keyword.NX;
import static core.framework.internal.redis.Protocol.Keyword.PX;
import static core.framework.internal.redis.RedisEncodings.decode;
import static core.framework.internal.redis.RedisEncodings.encode;

/**
 * @author neo
 */
public class RedisImpl implements Redis {
    private final Logger logger = LoggerFactory.getLogger(RedisImpl.class);
    private final RedisSet redisSet = new RedisSetImpl(this);
    private final RedisHash redisHash = new RedisHashImpl(this);
    private final RedisList redisList = new RedisListImpl(this);
    private final RedisAdmin redisAdmin = new RedisAdminImpl(this);
    private final String name;
    public Pool<RedisConnection> pool;
    public String host;
    long slowOperationThresholdInNanos = Duration.ofMillis(500).toNanos();
    int timeoutInMs = (int) Duration.ofSeconds(5).toMillis();

    public RedisImpl(String name) {
        this.name = name;
        pool = new Pool<>(() -> createConnection(timeoutInMs), name);
        pool.size(5, 50);
        pool.maxIdleTime = Duration.ofMinutes(30);
        pool.checkoutTimeout(Duration.ofSeconds(5));
    }

    public void timeout(Duration timeout) {
        timeoutInMs = (int) timeout.toMillis();
        pool.checkoutTimeout(timeout);
    }

    public void slowOperationThreshold(Duration threshold) {
        slowOperationThresholdInNanos = threshold.toNanos();
    }

    RedisConnection createConnection(int timeoutInMs) {
        if (host == null) throw new Error("redis.host must not be null");
        try {
            var connection = new RedisConnection();
            connection.connect(host, timeoutInMs);
            return connection;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void close() {
        logger.info("close redis client, name={}, host={}", name, host);
        pool.close();
    }

    @Override
    public String get(String key) {
        return decode(getBytes(key));
    }

    public byte[] getBytes(String key) {
        var watch = new StopWatch();
        byte[] value = null;
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            connection.writeKeyCommand(GET, key);
            value = connection.readBlobString();
            return value;
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, 1, 0);
            logger.debug("get, key={}, returnedValue={}, elapsed={}", key, new BytesLogParam(value), elapsed);
            checkSlowOperation(elapsed);
        }
    }

    @Override
    public RedisSet set() {
        return redisSet;
    }

    @Override
    public boolean set(String key, String value, Duration expiration, boolean onlyIfAbsent) {
        return set(key, encode(value), expiration, onlyIfAbsent);
    }

    public boolean set(String key, byte[] value, Duration expiration, boolean onlyIfAbsent) {
        var watch = new StopWatch();
        byte[] expirationTime = expiration == null ? null : expirationTime(expiration);
        boolean updated = false;
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            int length = 3 + (onlyIfAbsent ? 1 : 0) + (expiration != null ? 2 : 0);
            connection.writeArray(length);
            connection.writeBlobString(SET);
            connection.writeBlobString(encode(key));
            connection.writeBlobString(value);
            if (onlyIfAbsent) connection.writeBlobString(NX);
            if (expiration != null) {
                connection.writeBlobString(PX);
                connection.writeBlobString(expirationTime);
            }
            connection.flush();
            String result = connection.readSimpleString();
            updated = "OK".equals(result);
            return updated;
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, 0, updated ? 1 : 0);
            logger.debug("set, key={}, value={}, expiration={}, onlyIfAbsent={}, updated={}, elapsed={}", key, new BytesLogParam(value), expiration, onlyIfAbsent, updated, elapsed);
            checkSlowOperation(elapsed);
        }
    }

    @Override
    public void expire(String key, Duration expiration) {
        var watch = new StopWatch();
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            connection.writeKeyArgumentCommand(PEXPIRE, key, encode(expiration.toMillis()));    // PEXPIRE accepts zero and negative ttl
            connection.readLong();
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, 0, 1);
            logger.debug("pexpire, key={}, expiration={}, elapsed={}", key, expiration, elapsed);
            checkSlowOperation(elapsed);
        }
    }

    @Override
    public long del(String... keys) {
        var watch = new StopWatch();
        if (keys.length == 0) throw new Error("keys must not be empty");
        long deletedKeys = 0;
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            connection.writeKeysCommand(DEL, keys);
            deletedKeys = connection.readLong();
            return deletedKeys;
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, 0, (int) deletedKeys);
            logger.debug("del, keys={}, size={}, deletedKeys={}, elapsed={}", new ArrayLogParam(keys), keys.length, deletedKeys, elapsed);
            checkSlowOperation(elapsed);
        }
    }

    @Override
    public long increaseBy(String key, long increment) {
        var watch = new StopWatch();
        long value = 0;
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            connection.writeKeyArgumentCommand(INCRBY, key, encode(increment));
            value = connection.readLong();
            return value;
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, 0, 1);
            logger.debug("incrby, key={}, increment={}, returnedValue={}, elapsed={}", key, increment, value, elapsed);
            checkSlowOperation(elapsed);
        }
    }

    @Override
    public Map<String, String> multiGet(String... keys) {
        Map<String, byte[]> values = multiGetBytes(keys);
        Map<String, String> result = Maps.newLinkedHashMapWithExpectedSize(values.size());
        for (Map.Entry<String, byte[]> entry : values.entrySet()) {
            result.put(entry.getKey(), decode(entry.getValue()));
        }
        return result;
    }

    public Map<String, byte[]> multiGetBytes(String... keys) {
        var watch = new StopWatch();
        if (keys.length == 0) throw new Error("keys must not be empty");
        Map<String, byte[]> values = Maps.newLinkedHashMapWithExpectedSize(keys.length);
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            connection.writeKeysCommand(MGET, keys);
            Object[] response = connection.readArray();
            for (int i = 0; i < response.length; i++) {
                byte[] value = (byte[]) response[i];
                if (value != null) values.put(keys[i], value);
            }
            return values;
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, values.size(), 0);
            logger.debug("mget, keys={}, size={}, returnedValues={}, elapsed={}", new ArrayLogParam(keys), keys.length, new BytesValueMapLogParam(values), elapsed);
            checkSlowOperation(elapsed);
        }
    }

    @Override
    public void multiSet(Map<String, String> values) {
        var watch = new StopWatch();
        if (values.isEmpty()) throw new Error("values must not be empty");
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            connection.writeArray(1 + values.size() * 2);
            connection.writeBlobString(MSET);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                connection.writeBlobString(encode(entry.getKey()));
                connection.writeBlobString(encode(entry.getValue()));
            }
            connection.flush();
            connection.readSimpleString();
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            int size = values.size();
            ActionLogContext.track("redis", elapsed, 0, size);
            logger.debug("mset, values={}, size={}, elapsed={}", new MapLogParam(values), size, elapsed);
            checkSlowOperation(elapsed);
        }
    }

    public void multiSet(Map<String, byte[]> values, Duration expiration) {
        var watch = new StopWatch();
        if (values.isEmpty()) throw new Error("values must not be empty");
        byte[] expirationValue = expirationTime(expiration);
        int size = values.size();
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            for (Map.Entry<String, byte[]> entry : values.entrySet()) { // redis doesn't support mset with expiration, here to use pipeline
                connection.writeArray(5);
                connection.writeBlobString(SET);
                connection.writeBlobString(encode(entry.getKey()));
                connection.writeBlobString(entry.getValue());
                connection.writeBlobString(PX);
                connection.writeBlobString(expirationValue);
            }
            connection.flush();
            connection.readAll(size);
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, 0, size);
            logger.debug("set, values={}, size={}, expiration={}, elapsed={}", new BytesValueMapLogParam(values), size, expiration, elapsed);
            checkSlowOperation(elapsed);
        }
    }

    @Override
    public RedisHash hash() {
        return redisHash;
    }

    @Override
    public RedisList list() {
        return redisList;
    }

    @Override
    public void forEach(String pattern, Consumer<String> consumer) {
        var watch = new StopWatch();
        long start = System.nanoTime();
        long redisTook = 0;
        PoolItem<RedisConnection> item = pool.borrowItem();
        int returnedKeys = 0;
        try {
            RedisConnection connection = item.resource;
            byte[] batchSize = encode("500"); // use 500 as batch
            String cursor = "0";
            do {
                connection.writeArray(6);
                connection.writeBlobString(SCAN);
                connection.writeBlobString(encode(cursor));
                connection.writeBlobString(MATCH);
                connection.writeBlobString(encode(pattern));
                connection.writeBlobString(COUNT);
                connection.writeBlobString(batchSize);
                connection.flush();
                Object[] response = connection.readArray();
                cursor = decode((byte[]) response[0]);
                Object[] keys = (Object[]) response[1];
                returnedKeys += keys.length;
                redisTook += System.nanoTime() - start;
                for (Object key : keys) {
                    consumer.accept(decode((byte[]) key));
                }
                start = System.nanoTime();
            } while (!"0".equals(cursor));
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", redisTook, returnedKeys, 0);
            logger.debug("scan, pattern={}, returnedKeys={}, redisTook={}, elapsed={}", pattern, returnedKeys, redisTook, elapsed);
        }
    }

    @Override
    public RedisAdmin admin() {
        return redisAdmin;
    }

    public long[] expirationTime(String... keys) {
        var watch = new StopWatch();
        if (keys.length == 0) throw new Error("keys must not be empty");
        int size = keys.length;
        long[] expirationTimes = null;
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            for (String key : keys) {
                connection.writeArray(2);
                connection.writeBlobString(PTTL);
                connection.writeBlobString(encode(key));
            }
            connection.flush();
            Object[] results = connection.readAll(size);
            expirationTimes = new long[size];
            for (int i = 0; i < results.length; i++) {
                Long result = (Long) results[i];
                expirationTimes[i] = result;
            }
            return expirationTimes;
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, size, 0);
            logger.debug("pttl,  keys={}, size={}, returnedValues={}, elapsed={}", new ArrayLogParam(keys), size, expirationTimes, elapsed);
            checkSlowOperation(elapsed);
        }
    }

    public void publish(String channel, byte[] message) {
        var watch = new StopWatch();
        PoolItem<RedisConnection> item = pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            connection.writeKeyArgumentCommand(PUBLISH, channel, message);
            connection.readLong();
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, 0, 1);
            logger.debug("publish, channel={}, message={}, elapsed={}", channel, new BytesLogParam(message), elapsed);
            checkSlowOperation(elapsed);
        }
    }

    void checkSlowOperation(long elapsed) {
        if (elapsed > slowOperationThresholdInNanos) {
            logger.warn(Markers.errorCode("SLOW_REDIS"), "slow redis operation, elapsed={}", elapsed);
        }
    }

    private byte[] expirationTime(Duration expiration) {
        long expirationTime = expiration.toMillis();
        if (expirationTime <= 0) throw new Error("expiration time must be longer than 1ms");
        return encode(expirationTime);
    }
}
