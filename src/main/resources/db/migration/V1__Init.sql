-- 계좌 스냅샷 (현재 상태)
CREATE TABLE accounts (
    account_id   BIGSERIAL PRIMARY KEY,
    account_no   TEXT NOT NULL UNIQUE,
    balance      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    reserved     NUMERIC(18, 2) NOT NULL DEFAULT 0,
    currency     TEXT NOT NULL DEFAULT 'USD',
    status       TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'));


CREATE INDEX idx_accounts_account_no ON accounts(account_no);

-- 거래 원장 (append-only)
CREATE TABLE account_ledger (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT NOT NULL,
    entry_type   TEXT NOT NULL,
    request_id   TEXT NOT NULL,
    order_id     TEXT,
    amount       NUMERIC(18, 2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_account_ledger_account FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);

CREATE UNIQUE INDEX ux_ledger_request ON account_ledger (account_id, request_id);

CREATE INDEX idx_ledger_account_created ON account_ledger(account_id, created_at DESC);

ALTER TABLE account_ledger
    ADD CONSTRAINT chk_account_ledger_entry_type
        CHECK (entry_type IN ('RESERVE', 'UNRESERVE', 'DEPOSIT', 'WITHDRAW', 'FEE', 'INTEREST'));



-- 포지션 (현재 상태)
CREATE TABLE positions (
    position_id       BIGSERIAL PRIMARY KEY,
    account_id        BIGINT NOT NULL,
    symbol            TEXT NOT NULL,
    quantity          NUMERIC(20, 8) NOT NULL DEFAULT 0,
    reserved_quantity NUMERIC(20, 8) NOT NULL DEFAULT 0,
    avg_price         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_positions_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_positions_reserved CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity)
);

-- 계좌당 종목 unique
CREATE UNIQUE INDEX idx_positions_account_symbol ON positions(account_id, symbol);

-- 포지션 원장 (append-only)
CREATE TABLE position_ledger (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT NOT NULL,
    position_id     BIGINT,
    symbol          TEXT NOT NULL,
    entry_type      TEXT NOT NULL,
    request_id      TEXT NOT NULL,
    order_id        TEXT,
    quantity_change NUMERIC(20, 8) NOT NULL,
    price           NUMERIC(18, 2) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_position_ledger_account FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);

-- idempotency를 위한 unique index
CREATE UNIQUE INDEX ux_position_ledger_request ON position_ledger (account_id, request_id);

CREATE INDEX idx_position_ledger_account_created ON position_ledger(account_id, created_at DESC);

ALTER TABLE position_ledger
    ADD CONSTRAINT chk_position_ledger_entry_type
        CHECK (entry_type IN ('BUY', 'SELL', 'RESERVE', 'UNRESERVE', 'ADJUST', 'DIVIDEND'));

CREATE TABLE processed_events (
    event_id     TEXT PRIMARY KEY,
    event_type   TEXT NOT NULL,
    account_id   BIGINT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_events_account ON processed_events(account_id);
CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at DESC);

-- Outbox 이벤트 테이블
CREATE TYPE event_status AS ENUM ('PENDING', 'PUBLISHED', 'FAILED');

CREATE TABLE outbox_events (
    id               BIGSERIAL PRIMARY KEY,
    aggregate_type   TEXT NOT NULL,
    aggregate_id     BIGINT NOT NULL,
    event_type       TEXT NOT NULL,
    payload          BYTEA NOT NULL,
    idempotency_key  TEXT NOT NULL,
    status           event_status NOT NULL DEFAULT 'PENDING',
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    available_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status_available ON outbox_events(status, available_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_created ON outbox_events(created_at DESC);
