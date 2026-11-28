package io.github.vadimbabich.workspace.directory;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.FluentSelect;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.QueryRenderer;
import io.github.vadimbabich.workspace.domain.Company;
import io.github.vadimbabich.workspace.domain.Company__;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

public interface CompanyRepository extends Repository<Company, Long>, CompanyQueries {
}

interface CompanyQueries {

  Mono<Company> withAlias(String alias);
}

@Component
class CompanyQueriesImpl implements CompanyQueries {

  private static final Logger LOG = LoggerFactory.getLogger(CompanyQueriesImpl.class);

  private final MetamodelQueryExecutor executor;
  private final QueryRenderer renderer;

  CompanyQueriesImpl(MetamodelQueryExecutor executor, QueryRenderer renderer) {
    this.executor = executor;
    this.renderer = renderer;
  }

  @Override
  public Mono<Company> withAlias(String alias) {
    FluentSelect<Company> companyWithAlias = FluentSelect.from(Company__.ENTITY)
        .where(Company__.ALIAS.is(alias));

    LOG.atDebug().addArgument(() -> renderer.render(companyWithAlias).preview()).log("withAlias: {}");

    return executor.one(companyWithAlias);
  }
}
