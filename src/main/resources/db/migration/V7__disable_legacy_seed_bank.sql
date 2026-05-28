-- Disable legacy sample bank credentials if an existing environment was created
-- from earlier seed data. Real banks must be registered explicitly.

UPDATE registered_banks
SET active = FALSE,
    updated_at = NOW()
WHERE bank_handle = 'andalus'
  AND api_key_hash = 'e2f7be4108caddfdae391e52baab730f0c767eff9c90d704b5a91cc9187645e8';

UPDATE portal_users
SET active = FALSE,
    updated_at = NOW()
WHERE username = 'andalus_admin'
  AND bank_handle = 'andalus';
