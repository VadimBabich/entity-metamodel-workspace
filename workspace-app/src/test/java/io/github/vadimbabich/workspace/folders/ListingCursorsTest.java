package io.github.vadimbabich.workspace.folders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.vadimbabich.workspace.folders.ListingScope.ChildrenOf;
import io.github.vadimbabich.workspace.folders.ListingScope.TopLevel;
import io.github.vadimbabich.workspace.support.ProblemTypes;
import io.github.vadimbabich.workspace.web.ProblemConditionException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class ListingCursorsTest {

  private static final String KEY = "a-listing-cursor-key-of-at-least-thirty-two-bytes";
  private static final String OTHER_KEY = "another-listing-cursor-key-of-thirty-two-bytes";
  private static final long COMPANY = 3;
  private static final long CALLER = 0x1234_5678_9AL;
  private static final long ANCHOR = 42;
  private static final int CONTRACT_MAXIMUM_LENGTH = 76;
  private static final ListingQuery CHILDREN = new ListingQuery(COMPANY, CALLER, new ChildrenOf(7));

  private final ListingCursors cursors = new ListingCursors(new ListingProperties(KEY));

  @Test
  void aCursorLeadsBackToTheItemItWasIssuedAfter() {
    String cursor = cursors.cursorAfter(CHILDREN, ANCHOR);

    assertThat(cursors.anchorOf(CHILDREN, cursor)).isEqualTo(ANCHOR);
  }

  @Test
  void aCursorIsUrlSafeAndFitsTheContractsBound() {
    String cursor = cursors.cursorAfter(CHILDREN, Long.MAX_VALUE);

    assertThat(cursor).matches("[A-Za-z0-9_-]+").hasSize(CONTRACT_MAXIMUM_LENGTH);
  }

  @Test
  void aDecodedCursorNamesNeitherTheCallerNorTheFolder() {
    byte[] decoded = Base64.getUrlDecoder().decode(cursors.cursorAfter(CHILDREN, ANCHOR));
    String decodedText = new String(decoded, StandardCharsets.ISO_8859_1);

    assertThat(decodedText)
        .doesNotContain(new String(bytesOf(CALLER), StandardCharsets.ISO_8859_1))
        .doesNotContain(Long.toString(CALLER))
        .doesNotContain("folder");
  }

  @Test
  void aCursorReusedForAnotherQueryIsRefusedAsSuch() {
    String cursor = cursors.cursorAfter(CHILDREN, ANCHOR);

    expectRefusal(() -> cursors.anchorOf(new ListingQuery(COMPANY, CALLER + 1, new ChildrenOf(7)), cursor),
        "cursor-of-another-query");
    expectRefusal(() -> cursors.anchorOf(new ListingQuery(COMPANY + 1, CALLER, new ChildrenOf(7)), cursor),
        "cursor-of-another-query");
    expectRefusal(() -> cursors.anchorOf(new ListingQuery(COMPANY, CALLER, new ChildrenOf(8)), cursor),
        "cursor-of-another-query");
    expectRefusal(() -> cursors.anchorOf(new ListingQuery(COMPANY, CALLER, new TopLevel()), cursor),
        "cursor-of-another-query");
  }

  @Test
  void aTamperedCursorIsInvalid() {
    String cursor = cursors.cursorAfter(CHILDREN, ANCHOR);
    byte[] decoded = Base64.getUrlDecoder().decode(cursor);
    decoded[3] ^= 1;
    String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);

    expectRefusal(() -> cursors.anchorOf(CHILDREN, tampered), "invalid-cursor");
  }

  @Test
  void aCursorOfAnUnknownVersionIsInvalid() {
    byte[] decoded = Base64.getUrlDecoder().decode(cursors.cursorAfter(CHILDREN, ANCHOR));
    decoded[0] = 2;
    String laterVersion = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);

    expectRefusal(() -> cursors.anchorOf(CHILDREN, laterVersion), "invalid-cursor");
  }

  @Test
  void aCursorSignedWithAnotherKeyIsInvalid() {
    ListingCursors afterRotation = new ListingCursors(new ListingProperties(OTHER_KEY));
    String cursorBeforeRotation = cursors.cursorAfter(CHILDREN, ANCHOR);

    expectRefusal(() -> afterRotation.anchorOf(CHILDREN, cursorBeforeRotation), "invalid-cursor");
  }

  @Test
  void textThatIsNotACursorIsInvalid() {
    for (String notACursor : new String[] {"", "not a cursor", "AAAA", "%%%%"}) {
      expectRefusal(() -> cursors.anchorOf(CHILDREN, notACursor), "invalid-cursor");
    }
  }

  @Test
  void aMissingKeyIsRefusedAtStartUp() {
    assertThatThrownBy(() -> new ListingCursors(new ListingProperties(null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("workspace.listing.cursor-key");
  }

  @Test
  void aKeyShorterThanThirtyTwoBytesIsRefusedAtStartUp() {
    assertThatThrownBy(() -> new ListingCursors(new ListingProperties("too-short-a-key")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
  }

  private static void expectRefusal(ThrowingCallable decoding, String code) {
    assertThatThrownBy(decoding)
        .isInstanceOf(ProblemConditionException.class)
        .extracting(refusal -> ((ProblemConditionException) refusal).getBody().getType())
        .isEqualTo(URI.create(ProblemTypes.BASE + code));
  }

  private static byte[] bytesOf(long number) {
    return ByteBuffer.allocate(Long.BYTES).putLong(number).array();
  }
}
