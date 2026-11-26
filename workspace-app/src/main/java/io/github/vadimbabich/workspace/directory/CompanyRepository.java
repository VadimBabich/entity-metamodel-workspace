package io.github.vadimbabich.workspace.directory;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.FluentSelect;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.workspace.domain.Company;
import io.github.vadimbabich.workspace.domain.Company__;
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

  private final MetamodelQueryExecutor executor;

  CompanyQueriesImpl(MetamodelQueryExecutor executor) {
    this.executor = executor;
  }

  @Override
  public Mono<Company> withAlias(String alias) {
    FluentSelect<Company> companyWithAlias = FluentSelect.from(Company__.ENTITY)
        .where(Company__.ALIAS.is(alias));

    return executor.one(companyWithAlias);
  }
}
