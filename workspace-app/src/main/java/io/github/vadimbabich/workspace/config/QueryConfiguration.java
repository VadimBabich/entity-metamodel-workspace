package io.github.vadimbabich.workspace.config;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.QueryRenderer;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.r2dbc.core.DatabaseClient;

@Configuration(proxyBeanMethods = false)
class QueryConfiguration {

  /**
   * A bean of its own so that a preview is rendered by the renderer the executor runs with. The executor adds a
   * paged query's window after that, so a page's preview has no {@code LIMIT} or {@code OFFSET}.
   */
  @Bean
  QueryRenderer queryRenderer(RelationalMappingContext mappingContext, ConnectionFactory connectionFactory) {
    R2dbcDialect dialect = DialectResolver.getDialect(connectionFactory);

    return new QueryRenderer(mappingContext, dialect);
  }

  /**
   * The library registers nothing on its own; the executor is an ordinary bean. Renderer and
   * converter must share one mapping context, or projected columns are read back under other names.
   */
  @Bean
  MetamodelQueryExecutor metamodelQueryExecutor(
      DatabaseClient databaseClient,
      QueryRenderer queryRenderer,
      R2dbcConverter converter) {

    return new MetamodelQueryExecutor(databaseClient, queryRenderer, converter);
  }
}
