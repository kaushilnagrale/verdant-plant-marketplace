ALTER TABLE seller_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE seller_users FORCE ROW LEVEL SECURITY;

CREATE POLICY seller_users_isolation ON seller_users
  USING (
    seller_id::text = current_setting('app.current_tenant', true)
    OR current_setting('app.is_platform_admin', true) = 'true'
  );

CREATE ROLE verdant_app NOLOGIN;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO verdant_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO verdant_app;
GRANT verdant_app TO verdant;
