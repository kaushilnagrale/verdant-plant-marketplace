#!/usr/bin/env bash
# Real, previously-missing gap, found the hard way: this realm was
# always configured by hand against whichever Keycloak container
# happened to be running, and never captured as a script - Keycloak's
# own docker-compose service (start-dev, no persistent volume) loses
# its entire realm/user/role configuration on every container restart,
# and this project had no way to reconstruct it. This script is that
# missing piece: idempotent (safe to re-run), and the one real source
# of truth for what scripts/token.sh and every seller-auth-dependent
# manual smoke test actually needs to exist.
set -e
KC=http://localhost:8081

admin_token() {
  curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" -d "username=admin" -d "password=admin" -d "grant_type=password" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])"
}

TOKEN=$(admin_token)
auth() { curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }

echo "Creating realm 'verdant' (idempotent - a 409 here is fine, means it already exists)..."
auth -X POST "$KC/admin/realms" -d '{"realm":"verdant","enabled":true}' >/dev/null

echo "Disabling VERIFY_PROFILE (Keycloak 26's default realm behavior)..."
# Real error, found by actually trying to log in, not assumed: with this
# left enabled, a user created with only a username (no email/name) gets
# a silent, un-overridable VERIFY_PROFILE requirement regardless of that
# user's own requiredActions:[] - the token endpoint then returns the
# generic "invalid_grant: Account is not fully set up", which gives no
# hint that profile completeness, not credentials, is the real cause.
auth -X PUT "$KC/admin/realms/verdant/authentication/required-actions/VERIFY_PROFILE" \
  -d '{"alias":"VERIFY_PROFILE","enabled":false}' >/dev/null

echo "Creating client 'verdant-seller' (matches scripts/token.sh's own hardcoded client_id/secret)..."
auth -X POST "$KC/admin/realms/verdant/clients" -d '{
  "clientId": "verdant-seller",
  "secret": "dev-secret",
  "publicClient": false,
  "directAccessGrantsEnabled": true,
  "standardFlowEnabled": false,
  "serviceAccountsEnabled": false
}' >/dev/null

echo "Creating client 'verdant-buyer-web' (public, browser-based - buyer-web calls Keycloak's own token endpoint directly for login)..."
auth -X POST "$KC/admin/realms/verdant/clients" -d '{
  "clientId": "verdant-buyer-web",
  "publicClient": true,
  "directAccessGrantsEnabled": true,
  "standardFlowEnabled": false,
  "serviceAccountsEnabled": false,
  "redirectUris": ["http://localhost:5173/*"],
  "webOrigins": ["http://localhost:5173"]
}' >/dev/null

echo "Creating client 'verdant-backend' (confidential, service-account only - the app's own real Keycloak-admin identity, narrowly scoped to manage-users)..."
auth -X POST "$KC/admin/realms/verdant/clients" -d '{
  "clientId": "verdant-backend",
  "secret": "buyer-backend-dev-secret",
  "publicClient": false,
  "standardFlowEnabled": false,
  "directAccessGrantsEnabled": false,
  "serviceAccountsEnabled": true
}' >/dev/null

echo "Granting 'verdant-backend' service account the realm-management 'manage-users' + 'view-realm' roles..."
# Real finding, from an actual registration attempt failing with a 403 on
# the role-mapping GET call, not assumed: manage-users alone is enough to
# create/update users, but reading a REALM ROLE's own representation
# (needed to assign the real BUYER role, not just its name - Keycloak's
# role-mappings endpoint needs the full representation) needs view-realm
# too. manage-users + view-realm is the minimal real pairing for "create
# users and assign an existing realm role to them" - still nowhere near
# the full realm-admin role.
BACKEND_CLIENT_ID=$(auth "$KC/admin/realms/verdant/clients?clientId=verdant-backend" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")
SERVICE_ACCOUNT_USER_ID=$(auth "$KC/admin/realms/verdant/clients/$BACKEND_CLIENT_ID/service-account-user" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
REALM_MGMT_CLIENT_ID=$(auth "$KC/admin/realms/verdant/clients?clientId=realm-management" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")
MANAGE_USERS_ROLE=$(auth "$KC/admin/realms/verdant/clients/$REALM_MGMT_CLIENT_ID/roles/manage-users")
VIEW_REALM_ROLE=$(auth "$KC/admin/realms/verdant/clients/$REALM_MGMT_CLIENT_ID/roles/view-realm")
auth -X POST "$KC/admin/realms/verdant/users/$SERVICE_ACCOUNT_USER_ID/role-mappings/clients/$REALM_MGMT_CLIENT_ID" \
  -d "[$MANAGE_USERS_ROLE, $VIEW_REALM_ROLE]" >/dev/null

for role in PLATFORM_ADMIN SELLER_OWNER SELLER_STAFF BUYER; do
  echo "Creating realm role $role..."
  auth -X POST "$KC/admin/realms/verdant/roles" -d "{\"name\":\"$role\"}" >/dev/null
done

create_user_with_role() {
  local username=$1 role=$2
  echo "Creating user $username with role $role..."
  # emailVerified + requiredActions:[] explicitly, or a fresh Keycloak
  # user defaults to needing email verification - real error found by
  # actually trying to log in as one of these users, not assumed:
  # "invalid_grant: Account is not fully set up" from the token
  # endpoint, which a bare "enabled":true does NOT prevent on its own.
  auth -X POST "$KC/admin/realms/verdant/users" -d "{
    \"username\": \"$username\",
    \"enabled\": true,
    \"emailVerified\": true,
    \"requiredActions\": [],
    \"credentials\": [{\"type\": \"password\", \"value\": \"password\", \"temporary\": false}]
  }" >/dev/null
  local user_id
  user_id=$(auth "$KC/admin/realms/verdant/users?username=$username&exact=true" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")
  local role_json
  role_json=$(auth "$KC/admin/realms/verdant/roles/$role")
  auth -X POST "$KC/admin/realms/verdant/users/$user_id/role-mappings/realm" -d "[$role_json]" >/dev/null
  echo "  -> user id: $user_id"
}

create_user_with_role "ops-1" "PLATFORM_ADMIN"
create_user_with_role "seller-a" "SELLER_OWNER"
# A convenience login for manually poking at buyer-only endpoints without
# going through the real registration form first - buyer-web's own
# registration flow (POST /v1/buyers/register) is the real path a new
# buyer actually takes, and creates their own real buyers row itself;
# this one has no matching buyers row until you register through the UI
# with this same email, or insert one by hand.
create_user_with_role "buyer-a" "BUYER"

cat <<'EOF'

Done. scripts/token.sh ops-1      -> a real PLATFORM_ADMIN bearer token.
      scripts/token.sh seller-a   -> a real SELLER_OWNER bearer token.
      scripts/token.sh buyer-a    -> a real BUYER bearer token (Keycloak-side only - see note below).

NOT done here, and deliberately not fabricated: linking seller-a's real
Keycloak user id into a seller_users row against a real sellers.id -
that requires deciding WHICH real seller row this dev user should act
as, which is a per-scenario choice, not something this setup script can
know generically. Insert it manually per the pattern
com.verdant.identity.SellerUser / TenantContextFilter expect:

  INSERT INTO seller_users (subject_id, seller_id, role) VALUES ('<the user id above>', '<a real sellers.id>', 'OWNER');

Same real gap for buyer-a: this user exists in Keycloak but has no
matching row in the app's own buyers table (that only gets created by
POST /v1/buyers/register, which buyer-a's own bare Keycloak user never
went through) - /v1/buyers/me and friends will 403 for it until you
either register a real account through buyer-web with a real email/
password, or insert a buyers row by hand:

  INSERT INTO buyers (subject_id, email, name) VALUES ('<buyer-a's user id>', 'buyer-a@example.com', 'Buyer A');
EOF
