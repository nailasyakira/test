-- =====================================================================
-- SKEMA DATABASE: Aplikasi Pengelolaan Data Supermarket (SQLite)
-- UAS Praktikum PBO C 2025
-- =====================================================================
-- Catatan penting:
--  * SQLite TIDAK mengaktifkan foreign key secara default.
--    Jalankan "PRAGMA foreign_keys = ON;" setiap kali membuka koneksi
--    (di Java: statement.execute("PRAGMA foreign_keys = ON");)
--  * Uang disimpan sebagai INTEGER (Rupiah utuh) supaya tidak ada
--    masalah pembulatan seperti pada tipe desimal/float.
-- =====================================================================

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------
-- 1. USERS
--    Satu tabel untuk Admin dan Customer, dibedakan lewat kolom "role".
--    Di Java ini dipetakan ke inheritance: User -> Admin / Customer.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    username    TEXT    NOT NULL UNIQUE,
    password    TEXT    NOT NULL,              -- simpan HASH (mis. SHA-256), bukan teks asli
    full_name   TEXT    NOT NULL,
    role        TEXT    NOT NULL CHECK (role IN ('ADMIN', 'CUSTOMER')),
    created_at  TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- ---------------------------------------------------------------------
-- 2. CATEGORIES
--    Kategori barang (Makanan, Minuman, Kebersihan, dst).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS categories (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    name  TEXT NOT NULL UNIQUE
);

-- ---------------------------------------------------------------------
-- 3. PRODUCTS
--    Data utama yang dikelola Admin (tambah / edit / hapus).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    code         TEXT    NOT NULL UNIQUE,      -- kode/barcode barang, mis. "BRG-001"
    name         TEXT    NOT NULL,
    category_id  INTEGER NOT NULL,
    price        INTEGER NOT NULL CHECK (price >= 0),
    stock        INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
    created_at   TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (category_id) REFERENCES categories(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT                     -- kategori tidak boleh dihapus jika masih dipakai
);

-- ---------------------------------------------------------------------
-- 4. TRANSACTIONS  (header transaksi / "struk")
--    status:
--      PENDING   = baru dibuat customer, menunggu admin
--      VERIFIED  = sudah diverifikasi admin
--      CANCELLED = dibatalkan
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transactions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_no      TEXT    NOT NULL UNIQUE,   -- mis. "INV-20261112-0001"
    user_id         INTEGER NOT NULL,          -- customer yang bertransaksi
    total_amount    INTEGER NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    payment_method  TEXT    NOT NULL DEFAULT 'CASH'
                    CHECK (payment_method IN ('CASH', 'DEBIT', 'EWALLET')),
    status          TEXT    NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'VERIFIED', 'CANCELLED')),
    verified_by     INTEGER,                   -- admin yang memverifikasi (boleh kosong)
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (user_id)     REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (verified_by) REFERENCES users(id) ON DELETE SET NULL
);

-- ---------------------------------------------------------------------
-- 5. TRANSACTION_ITEMS  (detail isi keranjang per transaksi)
--    unit_price disimpan sebagai "snapshot" harga saat transaksi terjadi,
--    jadi riwayat lama tidak berubah walau harga barang diedit admin.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction_items (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id  INTEGER NOT NULL,
    product_id      INTEGER NOT NULL,
    quantity        INTEGER NOT NULL CHECK (quantity > 0),
    unit_price      INTEGER NOT NULL CHECK (unit_price >= 0),
    subtotal        INTEGER NOT NULL CHECK (subtotal >= 0),   -- quantity * unit_price
    FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
    FOREIGN KEY (product_id)     REFERENCES products(id)     ON DELETE RESTRICT
);

-- ---------------------------------------------------------------------
-- INDEX  (mempercepat pencarian & riwayat)
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_products_name         ON products(name);
CREATE INDEX IF NOT EXISTS idx_products_category     ON products(category_id);
CREATE INDEX IF NOT EXISTS idx_transactions_user     ON transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_items_transaction     ON transaction_items(transaction_id);

-- ---------------------------------------------------------------------
-- VIEW: riwayat transaksi lengkap (untuk fitur "Lihat Riwayat" & laporan)
-- ---------------------------------------------------------------------
CREATE VIEW IF NOT EXISTS v_transaction_history AS
SELECT  t.id            AS transaction_id,
        t.invoice_no,
        u.username      AS customer,
        t.status,
        t.payment_method,
        t.total_amount,
        t.created_at
FROM    transactions t
JOIN    users u ON u.id = t.user_id;

-- =====================================================================
-- DATA AWAL (SEED) untuk uji coba
-- Password di bawah hanya contoh. Di aplikasi sebenarnya simpan hash-nya.
-- =====================================================================
INSERT OR IGNORE INTO users (username, password, full_name, role) VALUES
    ('admin',  'admin123', 'Administrator',  'ADMIN'),
    ('budi',   'budi123',  'Budi Santoso',   'CUSTOMER');

INSERT OR IGNORE INTO categories (name) VALUES
    ('Makanan'), ('Minuman'), ('Kebersihan'), ('Kebutuhan Dapur');

INSERT OR IGNORE INTO products (code, name, category_id, price, stock) VALUES
    ('BRG-001', 'Mie Instan Goreng',       1,  3500, 200),
    ('BRG-002', 'Roti Tawar',              1, 16000,  40),
    ('BRG-003', 'Air Mineral 600ml',       2,  4000, 150),
    ('BRG-004', 'Teh Botol 450ml',         2,  6000, 100),
    ('BRG-005', 'Sabun Cuci Piring 400ml', 3, 12500,  60),
    ('BRG-006', 'Minyak Goreng 1L',        4, 19000,  80);

-- =====================================================================
-- CONTOH QUERY YANG BERGUNA
-- =====================================================================
-- Cari barang berdasarkan nama:
--   SELECT * FROM products WHERE name LIKE '%mie%';
--
-- Urutkan barang dari harga termurah:
--   SELECT * FROM products ORDER BY price ASC;
--
-- Daftar barang beserta kategorinya:
--   SELECT p.code, p.name, c.name AS kategori, p.price, p.stock
--   FROM products p JOIN categories c ON c.id = p.category_id;
--
-- Riwayat transaksi milik satu customer:
--   SELECT * FROM v_transaction_history WHERE customer = 'budi';
--
-- Isi detail satu transaksi (untuk cetak invoice):
--   SELECT p.name, i.quantity, i.unit_price, i.subtotal
--   FROM transaction_items i JOIN products p ON p.id = i.product_id
--   WHERE i.transaction_id = 1;
--
-- Laporan penjualan per barang (hanya transaksi terverifikasi):
--   SELECT p.name, SUM(i.quantity) AS terjual, SUM(i.subtotal) AS omzet
--   FROM transaction_items i
--   JOIN products p     ON p.id = i.product_id
--   JOIN transactions t ON t.id = i.transaction_id
--   WHERE t.status = 'VERIFIED'
--   GROUP BY p.id ORDER BY terjual DESC;
