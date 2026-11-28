package io.github.vadimbabich.workspace.folders;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("workspace.listing")
record ListingProperties(@Nullable String cursorKey) {
}
