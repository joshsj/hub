package com.flightstats.hub.webhook;

import com.flightstats.hub.cluster.LastContentPath;
import com.flightstats.hub.dao.ChannelService;
import com.flightstats.hub.exception.ConflictException;
import com.flightstats.hub.exception.NoSuchChannelException;
import com.flightstats.hub.model.ContentKey;
import com.flightstats.hub.model.ContentPath;
import com.flightstats.hub.util.ChannelNameUtils;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

import static com.flightstats.hub.webhook.WebhookLeader.WEBHOOK_LAST_COMPLETED;

public class WebhookService {
    private final static Logger logger = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookDao webhookDao;
    private final WebhookValidator webhookValidator;
    private final WebhookProcessor webhookProcessor;
    private final LastContentPath lastContentPath;
    private ChannelService channelService;

    @Inject
    public WebhookService(WebhookDao webhookDao, WebhookValidator webhookValidator,
                          WebhookProcessor webhookProcessor, LastContentPath lastContentPath, ChannelService channelService) {
        this.webhookDao = webhookDao;
        this.webhookValidator = webhookValidator;
        this.webhookProcessor = webhookProcessor;
        this.lastContentPath = lastContentPath;
        this.channelService = channelService;
    }

    public Optional<Webhook> upsert(Webhook webhook) {
        ContentPath requestKey = webhook.getStartingKey();
        webhook = webhook.withDefaults(true);
        logger.info("upsert webhook with defaults " + webhook);
        webhookValidator.validate(webhook);
        String name = webhook.getName();
        Optional<Webhook> webhookOptional = get(name);
        if (webhookOptional.isPresent()) {
            Webhook existing = webhookOptional.get();
            if (existing.equals(webhook)) {
                return webhookOptional;
            } else if (!existing.allowedToChange(webhook)) {
                throw new ConflictException("{\"error\": \"channelUrl can not change. \"}");
            }
        }
        ContentPath existing = lastContentPath.getOrNull(name, WEBHOOK_LAST_COMPLETED);
        logger.info("webhook {} existing {} requestKey {}", name, existing, requestKey);
        if (existing == null || requestKey != null) {
            logger.info("initializing {} {}", name, webhook.getStartingKey());
            lastContentPath.initialize(name, webhook.getStartingKey(), WEBHOOK_LAST_COMPLETED);
        }
        webhookDao.upsert(webhook);
        webhookProcessor.notifyWatchers();
        return webhookOptional;
    }

    public Optional<Webhook> get(String name) {
        return webhookDao.get(name);
    }

    public Collection<Webhook> getAll() {
        return webhookDao.getAll();
    }

    WebhookStatus getStatus(Webhook webhook) {
        WebhookStatus.WebhookStatusBuilder builder = WebhookStatus.builder().webhook(webhook);
        String channel = ChannelNameUtils.extractFromChannelUrl(webhook.getChannelUrl());
        try {
            Optional<ContentKey> lastKey = channelService.getLatest(channel, true, false);
            if (lastKey.isPresent()) {
                builder.channelLatest(lastKey.get());
            }
        } catch (NoSuchChannelException e) {
            logger.info("no channel found for " + channel);
        }
        webhookProcessor.getStatus(webhook, builder);
        return builder.build();
    }

    public void delete(String name) {
        logger.info("deleting webhook " + name);
        webhookDao.delete(name);
        webhookProcessor.delete(name);
    }

}