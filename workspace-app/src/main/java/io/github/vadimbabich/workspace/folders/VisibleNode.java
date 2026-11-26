package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.workspace.domain.Node;
import io.github.vadimbabich.workspace.query.Role;

record VisibleNode(Node node, Role effectiveRole) {
}
