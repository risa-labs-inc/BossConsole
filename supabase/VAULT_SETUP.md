# Supabase Vault Setup - Master Encryption Key

## ⚠️ CRITICAL SECURITY REQUIREMENT

The BOSS application uses Supabase Vault to securely store the master encryption key used for encrypting user secrets (passwords, API keys, etc.) in the `secrets` table.

**Without proper vault setup, the encryption/decryption functions will fail.**

---

## Initial Setup (After Database Migration)

### Step 1: Generate a Strong Encryption Key

Generate a 256-bit (32-byte) encryption key using OpenSSL:

```bash
openssl rand -base64 32
```

**Example output:**
```
FP3H1XcM766gz0Hi/ZLXnE0AZLOfDloGCKzviSKjuFo=
```

⚠️ **IMPORTANT**:
- Save this key in a secure password manager (1Password, LastPass, etc.)
- **NEVER** commit this key to git
- **NEVER** share this key in Slack, email, or other insecure channels
- Treat this like a root password - compromise of this key means ALL user secrets are exposed

### Step 2: Store Key in Supabase Vault

Connect to your Supabase project and run this SQL:

```sql
SELECT vault.create_secret(
    'YOUR-GENERATED-KEY-HERE',  -- Replace with the key from Step 1
    'master_encryption_key',     -- Unique name for this secret
    'Master key for encrypting user secrets in secrets table'  -- Description
);
```

**Using Supabase Dashboard:**
1. Go to: https://supabase.com/dashboard/project/pcnwqamqdnsadranufjv/sql/new
2. Paste the SQL above (replace `YOUR-GENERATED-KEY-HERE` with your actual key)
3. Click "Run"

**Using Supabase CLI:**
```bash
supabase db psql --linked --command "
SELECT vault.create_secret(
    'YOUR-GENERATED-KEY-HERE',
    'master_encryption_key',
    'Master key for encrypting user secrets in secrets table'
);
"
```

### Step 3: Verify Key is Stored

Run this SQL to verify the key was created:

```sql
SELECT
    id,
    name,
    description,
    created_at
FROM vault.decrypted_secrets
WHERE name = 'master_encryption_key';
```

Expected output:
```
| id                                   | name                   | description                                    | created_at              |
|--------------------------------------|------------------------|------------------------------------------------|-------------------------|
| <uuid>                               | master_encryption_key  | Master key for encrypting user secrets...      | 2025-10-21 10:30:45+00  |
```

✅ If you see a row, the key is properly stored!

### Step 4: Test Encryption/Decryption

Test that the encryption functions work:

```sql
-- Test encryption
SELECT public.encrypt_text('test-secret-value');

-- Should return something like: 'gAAAAABm...' (encrypted base64 string)

-- Test decryption
SELECT public.decrypt_text(public.encrypt_text('test-secret-value'));

-- Should return: 'test-secret-value' (original plaintext)
```

---

## How It Works

### Architecture

```
┌─────────────────────────────────────┐
│  Application (Kotlin/Compose)       │
│  ├─ Creates secret via RPC          │
│  └─ Calls: create_secret()          │
└────────────┬────────────────────────┘
             │
             v
┌─────────────────────────────────────┐
│  Database Function: create_secret() │
│  ├─ Calls: encrypt_text(password)   │
│  └─ Stores encrypted value          │
└────────────┬────────────────────────┘
             │
             v
┌─────────────────────────────────────┐
│  Function: encrypt_text()           │
│  ├─ Calls: get_encryption_key()     │
│  └─ Uses pgcrypto AES encryption    │
└────────────┬────────────────────────┘
             │
             v
┌─────────────────────────────────────┐
│  Function: get_encryption_key()     │
│  ├─ Queries: vault.decrypted_secrets│
│  └─ Returns: master_encryption_key  │
└────────────┬────────────────────────┘
             │
             v
┌─────────────────────────────────────┐
│  Supabase Vault                     │
│  ├─ Stores key encrypted at rest    │
│  ├─ Uses pgsodium for encryption    │
│  └─ Decrypts on-the-fly for queries │
└─────────────────────────────────────┘
```

### Security Benefits

1. **No Hardcoded Keys**: Key never appears in code or git history
2. **Encrypted at Rest**: Vault stores key encrypted using pgsodium
3. **Decrypted On-Demand**: Key only decrypted when explicitly queried
4. **Audit Trail**: Vault access can be logged and monitored
5. **Rotation Capability**: Key can be rotated with proper re-encryption

---

## Key Rotation (Advanced)

If you need to rotate the encryption key (e.g., after a security incident):

### Step 1: Generate New Key
```bash
openssl rand -base64 32
```

### Step 2: Create Rotation Function

```sql
CREATE OR REPLACE FUNCTION rotate_encryption_key(
    old_key TEXT,
    new_key TEXT
) RETURNS void AS $$
DECLARE
    secret_record RECORD;
    decrypted_password TEXT;
BEGIN
    -- Re-encrypt all passwords with new key
    FOR secret_record IN
        SELECT id, password_encrypted
        FROM secrets
        WHERE password_encrypted IS NOT NULL
    LOOP
        -- Decrypt with old key
        decrypted_password := decrypt(
            decode(secret_record.password_encrypted, 'base64'),
            old_key::bytea,
            'aes'
        );

        -- Re-encrypt with new key
        UPDATE secrets
        SET password_encrypted = encode(
            encrypt(
                decrypted_password::bytea,
                new_key::bytea,
                'aes'
            ),
            'base64'
        )
        WHERE id = secret_record.id;
    END LOOP;

    RAISE NOTICE 'Rotated % secrets', (SELECT COUNT(*) FROM secrets);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
```

### Step 3: Execute Rotation

```sql
-- Rotate all secrets
SELECT rotate_encryption_key(
    'OLD-KEY-HERE',
    'NEW-KEY-HERE'
);

-- Update vault with new key
DELETE FROM vault.secrets WHERE name = 'master_encryption_key';
SELECT vault.create_secret(
    'NEW-KEY-HERE',
    'master_encryption_key',
    'Master key for encrypting user secrets (Rotated 2025-10-21)'
);
```

### Step 4: Test

```sql
-- Verify decryption still works
SELECT username, public.decrypt_text(password_encrypted) as password
FROM secrets
LIMIT 1;
```

---

## Troubleshooting

### Error: "Encryption key not found in vault"

**Problem**: The master encryption key is not in Supabase Vault.

**Solution**: Run Step 2 from Initial Setup above.

### Error: "insufficient privilege" when querying vault

**Problem**: User doesn't have permission to read from vault.

**Solution**: Ensure you're connected as `postgres` or `service_role`:
```sql
-- Grant read access (if needed)
GRANT USAGE ON SCHEMA vault TO authenticated;
GRANT SELECT ON vault.decrypted_secrets TO authenticated;
```

### Error: "relation vault.decrypted_secrets does not exist"

**Problem**: Supabase Vault extension is not enabled.

**Solution**:
```sql
CREATE EXTENSION IF NOT EXISTS supabase_vault WITH SCHEMA vault;
```

---

## Security Best Practices

### ✅ DO:
- Generate keys using cryptographically secure random generators
- Store keys in enterprise password managers (1Password, LastPass, etc.)
- Use different keys for dev/staging/production environments
- Rotate keys periodically (annually at minimum)
- Document who has access to the encryption key
- Test key rotation procedure before emergency

### ❌ DON'T:
- Commit keys to git (even in private repos)
- Share keys via Slack, email, or unencrypted channels
- Reuse keys across different environments
- Store keys in plain text files on your computer
- Share keys with third parties without explicit approval

---

## Emergency Key Recovery

If you lose the encryption key:
1. **All encrypted secrets are permanently lost** - there is no recovery
2. Users will need to re-enter all passwords and API keys
3. You'll need to generate a new key and notify all users

**Prevention**:
- Store key in multiple secure locations (team password manager + personal backup)
- Document key location in runbook
- Test recovery procedure quarterly

---

## References

- [Supabase Vault Documentation](https://supabase.com/docs/guides/database/vault)
- [pgsodium Extension](https://github.com/michelp/pgsodium)
- [pgcrypto Documentation](https://www.postgresql.org/docs/current/pgcrypto.html)

---

**Last Updated**: 2025-10-21
