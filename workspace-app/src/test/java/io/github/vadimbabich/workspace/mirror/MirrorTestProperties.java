package io.github.vadimbabich.workspace.mirror;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

final class MirrorTestProperties {

  private static final String ADMIN_URI = "http://keycloak.invalid/admin/realms/workspace";

  private MirrorTestProperties() {
  }

  static MirrorProperties defaults() {
    Map<String, String> adminUriOnly = Map.of("workspace.mirror.admin-uri", ADMIN_URI);
    Binder binder = new Binder(new MapConfigurationPropertySource(adminUriOnly));

    return binder.bind("workspace.mirror", MirrorProperties.class).get();
  }
}
