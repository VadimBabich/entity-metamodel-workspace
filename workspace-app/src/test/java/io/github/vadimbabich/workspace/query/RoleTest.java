package io.github.vadimbabich.workspace.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoleTest {

  @Test
  void everyRoleListsAndOnlyTheListerCannotRead() {
    assertThat(Role.fromRank(1)).isEqualTo(Role.LISTER);
    assertThat(Role.LISTER.mayList()).isTrue();
    assertThat(Role.LISTER.mayRead()).isFalse();

    for (Role role : new Role[] {Role.VIEWER, Role.CONTRIBUTOR, Role.EDITOR, Role.MANAGER}) {
      assertThat(role.mayList()).as("%s lists", role).isTrue();
      assertThat(role.mayRead()).as("%s reads", role).isTrue();
    }
  }

  @Test
  void theContributorAndTheViewerGrantTheSameReadsInTheReadOnlyFirstCut() {
    assertThat(Role.CONTRIBUTOR.mayList()).isEqualTo(Role.VIEWER.mayList());
    assertThat(Role.CONTRIBUTOR.mayRead()).isEqualTo(Role.VIEWER.mayRead());
  }

  @Test
  void theRanksRunFromListerToManager() {
    assertThat(Role.values())
        .extracting(Role::rank)
        .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5);
  }
}
