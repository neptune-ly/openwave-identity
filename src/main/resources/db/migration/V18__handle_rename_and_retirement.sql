-- A CUSTOMER CAN CHANGE THEIR NAME. THE OLD ONE IS NEVER GIVEN TO ANYONE ELSE.
--
-- Until now there was no rename path at all. `claimHandle` looks the customer up
-- by national ID and throws HandleTaken whenever the stored handle differs from
-- the requested one, so a customer's SECOND username was refused forever — the
-- bank saved it, OpenWave refused it, and the app reported a failure every time.
--
-- ## Why retirement, and why it is permanent
--
-- An npt handle is a PAYMENT ADDRESS. It gets saved as a payee, printed on a
-- QR, pasted into a WhatsApp message, written on a shop window. If `ahmed`
-- renames to `ahmed.ali` and `ahmed` is later claimed by somebody else, then
-- every one of those still-circulating references quietly starts paying a
-- stranger — with no error, no warning, and a plausible-looking name on the
-- confirmation screen.
--
-- That is not a small risk on a bank rail, and there is no cooling-off period
-- long enough to make it safe: a payee saved in 2026 is still a payee in 2030.
-- So a retired handle is retired for good. The cost is a slowly growing table of
-- reserved strings; the alternative is misdirected money that looks correct to
-- everyone involved.
--
-- ## Retirement is NOT a redirect
--
-- A retired handle resolves to an explicit refusal, never to the new one.
-- Forwarding would defeat the point for the customer who renamed to stop being
-- reachable under the old name, and it would let anyone discover the new handle
-- by paying the old one.

CREATE TABLE retired_handles (
    id                  BIGSERIAL    PRIMARY KEY,

    -- The reserved string. UNIQUE because retiring the same handle twice is a
    -- bug, and because this column IS the reservation.
    handle              VARCHAR(32)  NOT NULL UNIQUE,

    -- Who it used to belong to. Kept so an operator can answer "where did this
    -- name go" without reading application logs. Deliberately not a foreign key:
    -- retirement must survive deletion or archival of the former identity.
    former_identity_id  BIGINT       NOT NULL,

    -- What it became. Current renames populate it for protected audit review,
    -- but it remains nullable for a governed retirement with no published
    -- successor. Runtime resolution never returns this field.
    replaced_by_handle  VARCHAR(32)  NULL,

    -- Which bank authenticated the customer-directed rename, for audit. This is
    -- deliberately retained as a value rather than a foreign key so the audit
    -- record survives a later bank archival or handle change.
    performed_by_bank   VARCHAR(20)  NULL,

    retired_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Only canonical NPT strings may become permanent reservations. This is a
    -- database backstop; the service performs the same validation first.
    CONSTRAINT chk_retired_handle_canonical
        CHECK (handle = LOWER(BTRIM(handle)) AND handle ~ '^[a-z0-9_.-]{3,32}$')
);

CREATE INDEX idx_retired_handles_former_identity
    ON retired_handles (former_identity_id);
CREATE INDEX idx_retired_handles_retired_at
    ON retired_handles (retired_at);

-- Rename accounting on the identity itself.
--
-- `handle_renamed_at` is what the rate limit reads. Without it a customer could
-- cycle through handles, retiring one reserved string per attempt — which is
-- handle squatting paid for by the registry, and a way to probe which names are
-- free by watching which renames succeed.
ALTER TABLE npt_identities
    ADD COLUMN IF NOT EXISTS handle_renamed_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS handle_rename_count INTEGER NOT NULL DEFAULT 0;

-- Existing handles are NOT retired by this migration.
--
-- Nothing has been renamed yet, so there is nothing to reserve. Seeding the
-- table with every live handle would be wrong twice over: those names are in
-- use, and a live handle must fail the "is it taken" check by being in
-- `identities`, not by being in a table that means "gone".
