package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.workspace.folders.ListingScope.ChildrenOf;
import io.github.vadimbabich.workspace.folders.ListingScope.TopLevel;
import io.github.vadimbabich.workspace.query.ListingOrder;
import io.github.vadimbabich.workspace.web.ProblemCondition;
import io.github.vadimbabich.workspace.web.ProblemConditionException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Issues and reads the signed cursor of a folder listing. A cursor carries the item the next page starts after and
 * a keyed hash of the query it belongs to, so neither the caller nor the folder can be read from it.
 */
@Component
class ListingCursors {

  private static final byte FORMAT_VERSION = 1;
  private static final String MAC_ALGORITHM = "HmacSHA256";
  private static final int MINIMUM_KEY_BYTES = 32;
  private static final int BINDING_BYTES = 16;
  private static final int SIGNATURE_BYTES = 32;
  private static final int PAYLOAD_BYTES = 1 + Long.BYTES + BINDING_BYTES;
  private static final int CURSOR_BYTES = PAYLOAD_BYTES + SIGNATURE_BYTES;

  private final SecretKeySpec signingKey;

  ListingCursors(ListingProperties properties) {
    String configuredKey = properties.cursorKey();

    if (configuredKey == null || configuredKey.isBlank()) {
      throw new IllegalStateException("workspace.listing.cursor-key is not set; give each deployment its own key");
    }

    byte[] keyBytes = configuredKey.getBytes(StandardCharsets.UTF_8);

    if (keyBytes.length < MINIMUM_KEY_BYTES) {
      throw new IllegalStateException("workspace.listing.cursor-key must be at least 32 bytes");
    }

    this.signingKey = new SecretKeySpec(keyBytes, MAC_ALGORITHM);
  }

  String cursorAfter(ListingQuery query, long anchorId) {
    byte[] payload = ByteBuffer.allocate(PAYLOAD_BYTES)
        .put(FORMAT_VERSION)
        .putLong(anchorId)
        .put(bindingOf(query))
        .array();

    byte[] signedPayload = ByteBuffer.allocate(CURSOR_BYTES)
        .put(payload)
        .put(macOf(payload))
        .array();

    return Base64.getUrlEncoder().withoutPadding().encodeToString(signedPayload);
  }

  long anchorOf(ListingQuery query, String cursor) {
    byte[] signedPayload = decoded(cursor);
    byte[] payload = Arrays.copyOfRange(signedPayload, 0, PAYLOAD_BYTES);
    byte[] signature = Arrays.copyOfRange(signedPayload, PAYLOAD_BYTES, CURSOR_BYTES);

    if (!MessageDigest.isEqual(signature, macOf(payload))) {
      throw new ProblemConditionException(ProblemCondition.INVALID_CURSOR);
    }

    ByteBuffer payloadReader = ByteBuffer.wrap(payload);
    byte version = payloadReader.get();
    long anchorId = payloadReader.getLong();
    byte[] binding = new byte[BINDING_BYTES];
    payloadReader.get(binding);

    if (version != FORMAT_VERSION) {
      throw new ProblemConditionException(ProblemCondition.INVALID_CURSOR);
    }

    if (!MessageDigest.isEqual(binding, bindingOf(query))) {
      throw new ProblemConditionException(ProblemCondition.CURSOR_OF_ANOTHER_QUERY);
    }

    return anchorId;
  }

  private static byte[] decoded(String cursor) {
    byte[] signedPayload;

    try {
      signedPayload = Base64.getUrlDecoder().decode(cursor);
    } catch (IllegalArgumentException notBase64) {
      throw new ProblemConditionException(ProblemCondition.INVALID_CURSOR);
    }

    if (signedPayload.length != CURSOR_BYTES) {
      throw new ProblemConditionException(ProblemCondition.INVALID_CURSOR);
    }

    return signedPayload;
  }

  private byte[] bindingOf(ListingQuery query) {
    String queryDescription = "listing-query|" + query.companyId() + "|" + query.appUserId()
        + "|" + scopeOf(query.scope()) + "|" + ListingOrder.IDENTIFIER;
    byte[] fullBinding = macOf(queryDescription.getBytes(StandardCharsets.UTF_8));

    return Arrays.copyOf(fullBinding, BINDING_BYTES);
  }

  private byte[] macOf(byte[] message) {
    try {
      Mac mac = Mac.getInstance(MAC_ALGORITHM);
      mac.init(signingKey);

      return mac.doFinal(message);
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
    }
  }

  private static String scopeOf(ListingScope scope) {
    return switch (scope) {
      case TopLevel topLevel -> "top-level";
      case ChildrenOf childrenOf -> "children-of-" + childrenOf.folderId();
    };
  }
}
