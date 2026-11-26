package io.github.vadimbabich.workspace.query;

public enum Role {

  LISTER((short) 1),
  VIEWER((short) 2),
  CONTRIBUTOR((short) 3),
  EDITOR((short) 4),
  MANAGER((short) 5);

  private final short rank;

  Role(short rank) {
    this.rank = rank;
  }

  public static Role fromRank(int rank) {
    for (Role role : values()) {
      if (role.rank == rank) {
        return role;
      }
    }

    throw new IllegalArgumentException("no role has the rank " + rank);
  }

  public short rank() {
    return rank;
  }

  public boolean mayList() {
    return rank >= LISTER.rank;
  }

  public boolean mayRead() {
    return rank >= VIEWER.rank;
  }

  public Role atLeast(Role other) {
    if (other.rank > rank) {
      return other;
    }

    return this;
  }
}
