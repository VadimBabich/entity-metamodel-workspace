package io.github.vadimbabich.workspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkspaceApplication {

  static void main(String[] args) {
    SpringApplication.run(WorkspaceApplication.class, args);
  }
}
