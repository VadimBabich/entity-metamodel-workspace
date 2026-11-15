# UI (not started)

This directory is reserved for the browser UI. Nothing is built yet, and the Maven build does not
include it.

## Where it lives

In this repository, in this directory, as its own Node project with its own `package.json`. It is
kept out of the repository root so that the Java build and the front-end tooling do not mix, and so
that the library demonstration stays readable. A separate repository is the alternative if the UI
grows its own release cycle.

## How it authenticates

The recommended pattern for a browser application is a **Backend for Frontend**: a small server-side
component holds the OAuth tokens and gives the browser only an HTTP-only session cookie. RFC 10017
(OAuth 2.0 for Browser-Based Applications, Best Current Practice) presents it first, as the most
secure option, and strongly recommends it for applications that handle personal data.

Two ways to provide it, to be decided with the UI:

- **Spring Cloud Gateway as the BFF**: OAuth 2 login against Keycloak, the `TokenRelay` filter
  forwarding the access token to this application, the built UI served as static content. One more
  deployable.
- **A public client with PKCE in the browser**: the `workspace-ui` client already in the local
  Keycloak realm. Simpler, and the less secure option by the same RFC.

The local Keycloak realm already contains the `workspace-ui` client (public, authorization code with
PKCE, redirect to `http://localhost:5173`), so either path can start without changing the stack.
