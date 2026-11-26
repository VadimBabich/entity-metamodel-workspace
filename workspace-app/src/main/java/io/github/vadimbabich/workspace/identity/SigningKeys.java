package io.github.vadimbabich.workspace.identity;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.SignedJWT;
import reactor.core.publisher.Flux;

public interface SigningKeys {

  Flux<JWK> keysFor(SignedJWT token);
}
