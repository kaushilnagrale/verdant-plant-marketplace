// No real seller-facing login UI exists anywhere in this project (every
// prior week's own verification used curl + scripts/token.sh against a
// real Keycloak instance directly). Building a full OIDC login flow here
// is a real, separate undertaking outside this week's own scope - the
// seller pastes a real token obtained from ops/scripts/token.sh instead,
// same real Keycloak-issued JWT every other real check this whole
// project has ever done actually used. A real deployment would replace
// this with a real Keycloak login redirect.

const TOKEN_KEY = 'verdant.seller.token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

/** The JWT's own "sub" claim decoded client-side, purely for display (e.g. "signed in as sub-seller-a") - never trusted for anything real, exactly like every other JWT consumer in this codebase, the token itself is what the real backend actually verifies. */
export function currentSubject(): string | null {
  const token = getToken();
  if (!token) return null;
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.sub ?? null;
  } catch {
    return null;
  }
}
