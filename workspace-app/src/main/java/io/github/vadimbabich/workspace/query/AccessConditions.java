package io.github.vadimbabich.workspace.query;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.workspace.domain.Node;
import io.github.vadimbabich.workspace.domain.Node__;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.sql.IdentifierProcessing;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.stereotype.Component;

@Component
public class AccessConditions {

  private static final String TESTED_NODE = "{0}";
  private static final String COMPANY = "{1}";
  private static final String FOLDER = "{2}";
  private static final String FOLDER_KIND = "{3}";

  private final String folderChainSql;

  AccessConditions(RelationalMappingContext mappingContext) {
    this.folderChainSql = folderChainSql(mappingContext);
  }

  public Condition inFolderChain(PropertyRef<?, Long> nodeColumn, long companyId, long folderId) {
    return SqlExpr.raw(folderChainSql, nodeColumn, companyId, folderId, Node.FOLDER);
  }

  private static String folderChainSql(RelationalMappingContext mappingContext) {
    SqlIdentifier nodeTable = mappingContext.getRequiredPersistentEntity(Node.class).getQualifiedTableName();
    String node = nodeTable.toSql(IdentifierProcessing.NONE);
    String id = Node__.ID.columnName(mappingContext);
    String parentId = Node__.PARENT_ID.columnName(mappingContext);
    String companyId = Node__.COMPANY_ID.columnName(mappingContext);
    String kindRank = Node__.KIND_RANK.columnName(mappingContext);
    String deletedAt = Node__.DELETED_AT.columnName(mappingContext);

    return TESTED_NODE + " in (with recursive chain (chain_node_id, chain_parent_id) as ("
        + " select f." + id + ", f." + parentId + " from " + node + " f"
        + " where f." + id + " = " + FOLDER + " and f." + companyId + " = " + COMPANY
        + " and f." + kindRank + " = " + FOLDER_KIND + " and f." + deletedAt + " is null"
        + " union all"
        + " select n." + id + ", n." + parentId + " from " + node + " n"
        + " join chain c on n." + id + " = c.chain_parent_id"
        + " where n." + companyId + " = " + COMPANY + ")"
        + " select chain_node_id from chain)";
  }
}
