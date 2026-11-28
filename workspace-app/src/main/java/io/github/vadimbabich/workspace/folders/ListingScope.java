package io.github.vadimbabich.workspace.folders;

sealed interface ListingScope {

  record TopLevel() implements ListingScope {
  }

  record ChildrenOf(long folderId) implements ListingScope {
  }
}
