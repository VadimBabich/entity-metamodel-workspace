package io.github.vadimbabich.workspace.support;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public final class FixtureDatabase {

  private static final String AWAIT_A_LOCK_WAITER = """
      do $$
      declare
        polls integer := 0;
      begin
        while not exists (select 1 from pg_locks where not granted) loop
          if polls >= 1000 then
            raise exception 'no statement waited on the held transaction within 10 seconds';
          end if;
          perform pg_sleep(0.01);
          polls := polls + 1;
        end loop;
      end
      $$
      """;

  private final DatabaseClient migrationRoleClient;

  FixtureDatabase(String r2dbcUrl) {
    ConnectionFactoryOptions migrationRoleOptions = ConnectionFactoryOptions.parse(r2dbcUrl).mutate()
        .option(ConnectionFactoryOptions.USER, TestDatabase.MIGRATION_ROLE)
        .option(ConnectionFactoryOptions.PASSWORD, TestDatabase.MIGRATION_PASSWORD)
        .build();
    ConnectionFactory migrationRoleConnections = ConnectionFactories.get(migrationRoleOptions);

    this.migrationRoleClient = DatabaseClient.create(migrationRoleConnections);
  }

  public GenericExecuteSpec sql(String statement) {
    return migrationRoleClient.sql(statement);
  }

  public Mono<Void> deleteAllRows() {
    return migrationRoleClient.sql("""
            truncate node restart identity
            """)
        .then();
  }

  public void run(String statement) {
    migrationRoleClient.sql(statement).then().block();
  }

  public Mono<Void> runCommittingOnceAnotherStatementWaits(String statement, Sinks.Empty<Void> executed) {
    return migrationRoleClient.inConnection(connection -> Mono.from(connection.beginTransaction())
        .then(Mono.from(connection.createStatement(statement).execute()))
        .flatMap(executionResult -> Mono.from(executionResult.getRowsUpdated()))
        .doOnSuccess(_ -> executed.tryEmitEmpty())
        .then(Mono.from(connection.createStatement(AWAIT_A_LOCK_WAITER).execute()))
        .flatMap(executionResult -> Mono.from(executionResult.getRowsUpdated()))
        .then(Mono.from(connection.commitTransaction())));
  }

}
