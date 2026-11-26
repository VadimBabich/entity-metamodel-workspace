package io.github.vadimbabich.workspace.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("workspace.identity.signing-keys")
record SigningKeyProperties(
    @DefaultValue("300s") Duration refreshAfter,
    @DefaultValue("30s") Duration fetchInterval,
    @DefaultValue("10m") Duration staleTolerance,
    @DefaultValue("1s") Duration fetchTimeout) {
}
