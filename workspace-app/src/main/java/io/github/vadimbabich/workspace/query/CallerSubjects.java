package io.github.vadimbabich.workspace.query;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.workspace.domain.AccessGrant;
import io.github.vadimbabich.workspace.domain.AccessGrant__;
import java.util.List;

public record CallerSubjects(long companyId, long appUserId, List<Long> groupIds) {

  public CallerSubjects {
    groupIds = List.copyOf(groupIds);
  }

  public Condition grantReachesThem() {
    Condition inTheCompany = AccessGrant__.COMPANY_ID.is(companyId);
    Condition toTheUser = AccessGrant__.SUBJECT_KIND.is(AccessGrant.USER)
        .and(AccessGrant__.SUBJECT_ID.is(appUserId));
    Condition toTheCompany = AccessGrant__.SUBJECT_KIND.is(AccessGrant.COMPANY)
        .and(AccessGrant__.SUBJECT_ID.is(companyId));
    Condition toTheUserOrTheCompany = toTheUser.or(toTheCompany);

    if (groupIds.isEmpty()) {
      return inTheCompany.and(toTheUserOrTheCompany);
    }

    Condition toOneOfTheGroups = AccessGrant__.SUBJECT_KIND.is(AccessGrant.GROUP)
        .and(AccessGrant__.SUBJECT_ID.in(groupIds));

    return inTheCompany.and(toTheUserOrTheCompany.or(toOneOfTheGroups));
  }
}
