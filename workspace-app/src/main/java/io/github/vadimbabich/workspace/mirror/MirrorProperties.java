package io.github.vadimbabich.workspace.mirror;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("workspace.mirror")
record MirrorProperties(
    URI adminUri,
    @DefaultValue("1s") Duration connectTimeout,
    @DefaultValue("2s") Duration responseTimeout,
    @DefaultValue("16") int maxConnections,
    @DefaultValue("64") int maxPendingConnections,
    @DefaultValue("1s") Duration connectionAcquireTimeout,
    @DefaultValue("2") int retries,
    @DefaultValue("100ms") Duration firstRetryBackoff,
    @DefaultValue("1s") Duration maxRetryBackoff,
    @DefaultValue("3s") Duration loadTimeout,
    @DefaultValue("100") int pageSize,
    @DefaultValue("5m") Duration membershipMaxAge,
    @DefaultValue("1m") Duration reloadBackoff,
    @DefaultValue("30s") Duration groupPassTimeout) {
}
