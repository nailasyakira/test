package sewalapangan.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import sewalapangan.config.Koneksi;
import sewalapangan.model.Lapangan;
import sewalapangan.model.ValidasiException;

public class LapanganDAO {

    private Lapangan petakan(ResultSet rs) throws SQLException {
        return new Lapangan(
                rs.getInt("id_lapangan"),
                rs.getString("nama_lapangan"),
                rs.getString("jenis"),
                rs.getLong("harga"),
                rs.getString("status"));
    }

    public List<Lapangan> semua() throws SQLException {
        List<Lapangan> hasil = new ArrayList<>();
        String sql = "SELECT * FROM tb_lapangan ORDER BY jenis, nama_lapangan";
        try (Connection c = Koneksi.getKoneksi();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                hasil.add(petakan(rs));
            }
        }
        return hasil;
    }

    public boolean tambah(Lapangan l) throws SQLException {
        String sql = "INSERT INTO tb_lapangan (nama_lapangan, jenis, harga, status) VALUES (?, ?, ?, ?)";
        try (Connection c = Koneksi.getKoneksi();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, l.getNamaLapangan());
            ps.setString(2, l.getJenis());
            ps.setLong(3, l.getHarga());
            ps.setString(4, l.getStatus());
            boolean ok = ps.executeUpdate() > 0;
            try (ResultSet kunci = ps.getGeneratedKeys()) {
                if (kunci.next()) {
                    l.setIdLapangan(kunci.getInt(1));
                }
            }
            return ok;
        }
    }

    public boolean ubah(Lapangan l) throws SQLException {
        String sql = "UPDATE tb_lapangan SET nama_lapangan = ?, jenis = ?, harga = ?, status = ? WHERE id_lapangan = ?";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, l.getNamaLapangan());
            ps.setString(2, l.getJenis());
            ps.setLong(3, l.getHarga());
            ps.setString(4, l.getStatus());
            ps.setInt(5, l.getIdLapangan());
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateStatus(int idLapangan, String status) throws SQLException {
        String sql = "UPDATE tb_lapangan SET status = ? WHERE id_lapangan = ?";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, idLapangan);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean hapus(int idLapangan) throws SQLException, ValidasiException {
        String sql = "DELETE FROM tb_lapangan WHERE id_lapangan = ?";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idLapangan);
            return ps.executeUpdate() > 0;
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new ValidasiException("Lapangan ini sudah memiliki riwayat booking dan tidak dapat dihapus.\n"
                    + "Ubah statusnya menjadi \"Perawatan\" jika tidak ingin disewakan lagi.");
        }
    }
}
