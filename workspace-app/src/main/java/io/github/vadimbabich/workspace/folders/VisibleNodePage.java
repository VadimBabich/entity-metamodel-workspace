package io.github.vadimbabich.workspace.folders;

import java.util.List;
import java.util.Optional;

record VisibleNodePage(List<VisibleNode> nodes, Optional<String> nextCursor) {
}
