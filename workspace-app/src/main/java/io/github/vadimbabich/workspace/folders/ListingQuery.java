package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.workspace.identity.Caller;

record ListingQuery(long companyId, long appUserId, ListingScope scope) {

  static ListingQuery of(Caller caller, ListingScope scope) {
    return new ListingQuery(caller.companyId(), caller.appUserId(), scope);
  }
}
