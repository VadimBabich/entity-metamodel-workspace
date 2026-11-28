package io.github.vadimbabich.workspace.query;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.SortOrder;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.workspace.domain.Node;
import io.github.vadimbabich.workspace.domain.Node__;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.sql.IdentifierProcessing;
import org.springframework.stereotype.Component;

/**
 * The order every listing pages in, folders first, then by name, then by id, and the keyset condition that resumes
 * it after an item. Both are built from one column list; a cursor names the order by {@link #IDENTIFIER}.
 */
@Component
public class ListingOrder {

  public static final String IDENTIFIER = "FOLDERS_FIRST_BY_NAME";

  private static final List<PropertyRef<Node, ?>> ORDER_COLUMNS = List.of(Node__.KIND_RANK, Node__.NAME, Node__.ID);

  private final String keysetSql;

  ListingOrder(RelationalMappingContext mappingContext) {
    this.keysetSql = keysetSql(mappingContext);
  }

  public SortOrder[] sortOrders() {
    SortOrder[] sortOrders = new SortOrder[ORDER_COLUMNS.size()];

    for (int position = 0; position < ORDER_COLUMNS.size(); position++) {
      sortOrders[position] = ORDER_COLUMNS.get(position).asc();
    }

    return sortOrders;
  }

  public Condition after(long anchorId) {
    List<Object> fragmentArguments = new ArrayList<>(ORDER_COLUMNS);
    fragmentArguments.add(anchorId);

    return SqlExpr.raw(keysetSql, fragmentArguments.toArray());
  }

  private static String keysetSql(RelationalMappingContext mappingContext) {
    String node = mappingContext.getRequiredPersistentEntity(Node.class)
        .getQualifiedTableName()
        .toSql(IdentifierProcessing.NONE);
    String id = Node__.ID.columnName(mappingContext);
    String anchorPlaceholder = "{" + ORDER_COLUMNS.size() + "}";

    List<String> orderPlaceholders = new ArrayList<>();
    List<String> anchorColumns = new ArrayList<>();

    for (int position = 0; position < ORDER_COLUMNS.size(); position++) {
      orderPlaceholders.add("{" + position + "}");
      anchorColumns.add("anchor." + ORDER_COLUMNS.get(position).columnName(mappingContext));
    }

    return "(" + String.join(", ", orderPlaceholders) + ") > ("
        + "select " + String.join(", ", anchorColumns)
        + " from " + node + " anchor where anchor." + id + " = " + anchorPlaceholder + ")";
  }
}
