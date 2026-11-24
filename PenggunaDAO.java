package sewalapangan.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import sewalapangan.config.Koneksi;
import sewalapangan.model.Admin;
import sewalapangan.model.Pengguna;
import sewalapangan.model.User;
import sewalapangan.model.ValidasiException;
import sewalapangan.util.PasswordUtil;

public class PenggunaDAO {

    private Pengguna petakan(ResultSet rs) throws SQLException {
        Pengguna p;
        if (Pengguna.ROLE_ADMIN.equals(rs.getString("role"))) {
            p = new Admin();
        } else {
            User u = new User();
            u.setNoTelp(rs.getString("no_telp"));
            p = u;
        }
        p.setIdPengguna(rs.getInt("id_pengguna"));
        p.setNama(rs.getString("nama"));
        p.setUsername(rs.getString("username"));
        p.setPassword(rs.getString("password"));
        return p;
    }

    /** Mengembalikan Admin atau User sesuai role, atau null jika username tidak ada. */
    public Pengguna cariByUsername(String username) throws SQLException {
        String sql = "SELECT * FROM tb_pengguna WHERE username = ?";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? petakan(rs) : null;
            }
        }
    }

    public boolean usernameAda(String username) throws SQLException {
        String sql = "SELECT COUNT(*) FROM tb_pengguna WHERE username = ?";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Pendaftaran akun User baru (Register). Password disimpan dalam bentuk hash. */
    public void daftarkan(User user, String passwordAsli) throws SQLException, ValidasiException {
        if (usernameAda(user.getUsername())) {
            throw new ValidasiException("Username \"" + user.getUsername() + "\" sudah dipakai.");
        }
        String sql = "INSERT INTO tb_pengguna (nama, username, password, role, no_telp) VALUES (?, ?, ?, 'USER', ?)";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.getNama());
            ps.setString(2, user.getUsername());
            ps.setString(3, PasswordUtil.hash(passwordAsli));
            ps.setString(4, user.getNoTelp());
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new ValidasiException("Username \"" + user.getUsername() + "\" sudah dipakai.");
        }
    }

    /** Daftar pelanggan (role USER), dapat dicari berdasarkan nama / username / no. telepon. */
    public List<User> daftarUser(String kataKunci) throws SQLException {
        String sql = "SELECT * FROM tb_pengguna WHERE role = 'USER' "
                + "AND (nama LIKE ? OR username LIKE ? OR no_telp LIKE ?) ORDER BY nama";
        List<User> hasil = new ArrayList<>();
        String pola = "%" + (kataKunci == null ? "" : kataKunci.trim()) + "%";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, pola);
            ps.setString(2, pola);
            ps.setString(3, pola);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hasil.add((User) petakan(rs));
                }
            }
        }
        return hasil;
    }

    public boolean ubahUser(User user) throws SQLException {
        String sql = "UPDATE tb_pengguna SET nama = ?, no_telp = ? WHERE id_pengguna = ? AND role = 'USER'";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.getNama());
            ps.setString(2, user.getNoTelp());
            ps.setInt(3, user.getIdPengguna());
            return ps.executeUpdate() > 0;
        }
    }

    public boolean hapusUser(int idPengguna) throws SQLException, ValidasiException {
        String sql = "DELETE FROM tb_pengguna WHERE id_pengguna = ? AND role = 'USER'";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idPengguna);
            return ps.executeUpdate() > 0;
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new ValidasiException("Pelanggan ini sudah memiliki riwayat booking sehingga tidak dapat dihapus.");
        }
    }

    /** Membuat akun admin bawaan bila belum ada admin sama sekali. @return true jika dibuat. */
    public boolean pastikanAdminAda() throws SQLException {
        try (Connection c = Koneksi.getKoneksi();
             PreparedStatement cek = c.prepareStatement("SELECT COUNT(*) FROM tb_pengguna WHERE role = 'ADMIN'");
             ResultSet rs = cek.executeQuery()) {
            rs.next();
            if (rs.getInt(1) > 0) {
                return false;
            }
            String sql = "INSERT INTO tb_pengguna (nama, username, password, role) VALUES (?, ?, ?, 'ADMIN')";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, "Administrator");
                ps.setString(2, "admin");
                ps.setString(3, PasswordUtil.hash("admin123"));
                ps.executeUpdate();
            }
            return true;
        }
    }
}
