package com.flightstats.hub.dao;

import com.flightstats.hub.app.HubProperties;
import com.flightstats.hub.channel.ChannelValidator;
import com.flightstats.hub.exception.ForbiddenRequestException;
import com.flightstats.hub.exception.NoSuchChannelException;
import com.flightstats.hub.metrics.ActiveTraces;
import com.flightstats.hub.metrics.DataDog;
import com.flightstats.hub.metrics.MetricsSender;
import com.flightstats.hub.metrics.Traces;
import com.flightstats.hub.model.*;
import com.flightstats.hub.replication.ReplicationGlobalManager;
import com.flightstats.hub.util.TimeUtil;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.timgroup.statsd.StatsDClient;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class LocalChannelService implements ChannelService {
    private final static Logger logger = LoggerFactory.getLogger(LocalChannelService.class);
    private final static StatsDClient statsd = DataDog.statsd;
    private static final int DIR_COUNT_LIMIT = HubProperties.getProperty("app.directionCountLimit", 10000);
    @Inject
    private ContentService contentService;
    @Inject
    private ChannelConfigDao channelConfigDao;
    @Inject
    private ChannelValidator channelValidator;
    @Inject
    private ReplicationGlobalManager replicationGlobalManager;
    @Inject
    private MetricsSender sender;

    @Override
    public boolean channelExists(String channelName) {
        return channelConfigDao.channelExists(channelName);
    }

    @Override
    public ChannelConfig createChannel(ChannelConfig configuration) {
        logger.info("create channel {}", configuration);
        channelValidator.validate(configuration, true);
        configuration = ChannelConfig.builder().withChannelConfiguration(configuration).build();
        ChannelConfig created = channelConfigDao.createChannel(configuration);
        notify(created, null);
        return created;
    }

    private void notify(ChannelConfig newConfig, ChannelConfig oldConfig) {
        if (newConfig.isReplicating() || newConfig.isGlobalMaster()) {
            replicationGlobalManager.notifyWatchers();
        } else if (oldConfig != null) {
            if (oldConfig.isReplicating() || oldConfig.isGlobalMaster()) {
                replicationGlobalManager.notifyWatchers();
            }
        }
        contentService.notify(newConfig, oldConfig);
    }

    @Override
    public ChannelConfig updateChannel(ChannelConfig configuration, ChannelConfig oldConfig) {
        if (configuration.hasChanged(oldConfig)) {
            logger.info("updating channel {} from {}", configuration, oldConfig);
            configuration = ChannelConfig.builder().withChannelConfiguration(configuration).build();
            channelValidator.validate(configuration, false);
            channelConfigDao.updateChannel(configuration);
            notify(configuration, oldConfig);
        } else {
            logger.info("update with no changes {}", configuration);
        }
        return configuration;
    }

    @Override
    public ContentKey insert(String channelName, Content content) throws Exception {
        if (content.isNew() && isReplicating(channelName)) {
            throw new ForbiddenRequestException(channelName + " cannot modified while replicating");
        }
        long start = System.currentTimeMillis();
        ContentKey contentKey = contentService.insert(channelName, content);
        long time = System.currentTimeMillis() - start;
        statsd.time("channel", time, "method:post", "type:single", "channel:" + channelName);
        statsd.increment("channel.items", "method:post", "type:single", "channel:" + channelName);
        statsd.count("channel.bytes", content.getSize(), "method:post", "type:single", "channel:" + channelName);
        sender.send("channel." + channelName + ".post", time);
        sender.send("channel." + channelName + ".items", 1);
        sender.send("channel." + channelName + ".post.bytes", content.getSize());
        sender.send("channel.ALL.post", time);
        return contentKey;
    }

    @Override
    public boolean historicalInsert(String channelName, Content content) throws Exception {
        return contentService.historicalInsert(channelName, content);
    }

    @Override
    public Collection<ContentKey> insert(BulkContent bulkContent) throws Exception {
        String channel = bulkContent.getChannel();
        if (bulkContent.isNew() && isReplicating(channel)) {
            throw new ForbiddenRequestException(channel + " cannot modified while replicating");
        }
        long start = System.currentTimeMillis();
        Collection<ContentKey> contentKeys = contentService.insert(bulkContent);
        long time = System.currentTimeMillis() - start;
        statsd.time("channel", time, "method:post", "type:bulk", "channel:" + channel);
        statsd.count("channel.items", bulkContent.getItems().size(), "method:post", "type:bulk", "channel:" + channel);
        statsd.count("channel.bytes", bulkContent.getSize(), "method:post", "type:bulk", "channel:" + channel);
        sender.send("channel." + channel + ".batchPost", time);
        sender.send("channel." + channel + ".items", bulkContent.getItems().size());
        sender.send("channel." + channel + ".post", time);
        sender.send("channel." + channel + ".post.bytes", bulkContent.getSize());
        sender.send("channel.ALL.post", time);
        //todo - gfm - 5/20/16 - add objects to the MetricsRequestFilter.getLocalTraces()
        return contentKeys;
    }

    @Override
    public boolean isReplicating(String channelName) {
        try {
            ChannelConfig configuration = getCachedChannelConfig(channelName);
            return configuration.isReplicating();
        } catch (NoSuchChannelException e) {
            return false;
        }
    }

    @Override
    public Optional<ContentKey> getLatest(String channel, boolean stable, boolean trace) {
        ChannelConfig channelConfig = getCachedChannelConfig(channel);
        if (null == channelConfig) {
            return Optional.absent();
        }
        Traces traces = ActiveTraces.getLocal();
        ContentKey limitKey = getLatestLimit(stable);

        Optional<ContentKey> latest = contentService.getLatest(channel, limitKey, traces, stable);
        if (latest.isPresent()) {
            DateTime ttlTime = getTtlTime(channel);
            if (latest.get().getTime().isBefore(ttlTime)) {
                return Optional.absent();
            }
        }
        if (trace) {
            traces.log(logger);
        }
        return latest;
    }

    static ContentKey getLatestLimit(boolean stable) {
        DateTime time = TimeUtil.stable();
        if (!stable) {
            //if not stable, we don't want to miss any results.
            time = TimeUtil.now().plusMinutes(1);
        }
        return ContentKey.lastKey(time);
    }

    @Override
    public void deleteBefore(String name, ContentKey limitKey) {
        contentService.deleteBefore(name, limitKey);
    }

    @Override
    public Optional<Content> get(Request request) {
        DateTime ttlTime = getTtlTime(request.getChannel()).minusMinutes(15);
        if (request.getKey().getTime().isBefore(ttlTime)) {
            return Optional.absent();
        }
        return contentService.get(request.getChannel(), request.getKey());
    }

    @Override
    public ChannelConfig getChannelConfig(String channelName, boolean allowChannelCache) {
        if (allowChannelCache) {
            return getCachedChannelConfig(channelName);
        }
        return channelConfigDao.getChannelConfig(channelName);
    }

    @Override
    public ChannelConfig getCachedChannelConfig(String channelName) {
        ChannelConfig channelConfig = channelConfigDao.getCachedChannelConfig(channelName);
        if (null == channelConfig) {
            throw new NoSuchChannelException(channelName);
        }
        return channelConfig;
    }

    @Override
    public Collection<ChannelConfig> getChannels() {
        return getChannels(false);
    }

    private Collection<ChannelConfig> getChannels(boolean useCache) {
        return channelConfigDao.getChannels(useCache);
    }

    @Override
    public Collection<ChannelConfig> getChannels(String tag) {
        Collection<ChannelConfig> matchingChannels = new ArrayList<>();
        Iterable<ChannelConfig> channels = getChannels(true);
        for (ChannelConfig channel : channels) {
            if (channel.getTags().contains(tag)) {
                matchingChannels.add(channel);
            }
        }
        return matchingChannels;
    }

    @Override
    public Iterable<String> getTags() {
        Collection<String> matchingChannels = new HashSet<>();
        Iterable<ChannelConfig> channels = getChannels();
        for (ChannelConfig channel : channels) {
            matchingChannels.addAll(channel.getTags());
        }
        return matchingChannels;
    }

    @Override
    public SortedSet<ContentKey> queryByTime(TimeQuery query) {
        if (query == null) {
            return Collections.emptySortedSet();
        }
        DateTime ttlTime = getTtlTime(query.getChannelName());
        Stream<ContentKey> stream = contentService.queryByTime(query).stream()
                .filter(key -> key.getTime().isAfter(ttlTime));
        if (query.isStable()) {
            DateTime stableTime = TimeUtil.stable();
            stream = stream.filter(key -> key.getTime().isBefore(stableTime));
        }
        return stream.collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public SortedSet<ContentKey> getKeys(DirectionQuery query) {
        if (query.getCount() <= 0) {
            return Collections.emptySortedSet();
        }
        if (query.getCount() > DIR_COUNT_LIMIT) {
            query = query.withCount(DIR_COUNT_LIMIT);
        }
        DateTime ttlTime = getTtlTime(query.getChannelName());
        if (query.getContentKey().getTime().isBefore(ttlTime)) {
            query = query.withContentKey(new ContentKey(ttlTime, "0"));
        }
        query = query.withTtlDays(getTtlDays(query.getChannelName()));
        Traces traces = ActiveTraces.getLocal();
        traces.add(query);
        List<ContentKey> keys = new ArrayList<>(contentService.queryDirection(query));
        SortedSet<ContentKey> contentKeys = ContentKeyUtil.filter(keys, query.getContentKey(), ttlTime, query.getCount(), query.isNext(), query.isStable());
        traces.add("ChannelServiceImpl.getKeys", contentKeys);
        return contentKeys;
    }

    @Override
    public void get(String channel, SortedSet<ContentKey> keys, Consumer<Content> callback) {
        contentService.get(channel, keys, callback);
    }

    private DateTime getTtlTime(String channelName) {
        return TimeUtil.getEarliestTime(getTtlDays(channelName));
    }

    private int getTtlDays(String channelName) {
        return (int) getCachedChannelConfig(channelName).getTtlDays();
    }

    @Override
    public boolean delete(String channelName) {
        if (!channelConfigDao.channelExists(channelName)) {
            return false;
        }
        boolean replicating = isReplicating(channelName);
        contentService.delete(channelName);
        channelConfigDao.delete(channelName);
        if (replicating) {
            replicationGlobalManager.notifyWatchers();
        }
        return true;
    }
}
