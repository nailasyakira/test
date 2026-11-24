package sewalapangan.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import sewalapangan.config.Koneksi;
import sewalapangan.model.Booking;
import sewalapangan.model.Pembayaran;
import sewalapangan.model.ValidasiException;

public class PembayaranDAO {

    private static final String SELECT_DETAIL =
            "SELECT pb.*, b.id_pengguna, b.id_lapangan, b.tanggal, b.jam_mulai, b.jam_selesai, "
          + "b.total_harga, b.status_booking, l.nama_lapangan, p.nama AS nama_pengguna "
          + "FROM tb_pembayaran pb "
          + "JOIN tb_booking b ON b.id_booking = pb.id_booking "
          + "JOIN tb_lapangan l ON l.id_lapangan = b.id_lapangan "
          + "JOIN tb_pengguna p ON p.id_pengguna = b.id_pengguna ";

    /**
     * Menyimpan bukti pembayaran milik user.
     * Jika sudah ada pembayaran (mis. sebelumnya ditolak) maka bukti diganti dan status kembali MENUNGGU.
     */
    public Pembayaran simpanBukti(int idPengguna, int idBooking, String namaFileBukti)
            throws SQLException, ValidasiException {
        try (Connection c = Koneksi.getKoneksi()) {
            c.setAutoCommit(false);
            try {
                long total;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT total_harga, status_booking FROM tb_booking "
                      + "WHERE id_booking = ? AND id_pengguna = ? FOR UPDATE")) {
                    ps.setInt(1, idBooking);
                    ps.setInt(2, idPengguna);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ValidasiException("Booking tidak ditemukan.");
                        }
                        if (!Booking.STATUS_AKTIF.equals(rs.getString("status_booking"))) {
                            throw new ValidasiException("Booking ini sudah dibatalkan.");
                        }
                        total = rs.getLong("total_harga");
                    }
                }

                Integer idLama = null;
                String statusLama = null;
                String buktiLama = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id_pembayaran, status_bayar, bukti_bayar FROM tb_pembayaran WHERE id_booking = ?")) {
                    ps.setInt(1, idBooking);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            idLama = rs.getInt("id_pembayaran");
                            statusLama = rs.getString("status_bayar");
                            buktiLama = rs.getString("bukti_bayar");
                        }
                    }
                }

                Pembayaran hasil = new Pembayaran();
                hasil.setIdBooking(idBooking);
                hasil.setJumlah(total);
                hasil.setBuktiBayar(namaFileBukti);
                hasil.setStatusBayar(Pembayaran.MENUNGGU);

                if (idLama == null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO tb_pembayaran (id_booking, jumlah, bukti_bayar) VALUES (?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, idBooking);
                        ps.setLong(2, total);
                        ps.setString(3, namaFileBukti);
                        ps.executeUpdate();
                        try (ResultSet kunci = ps.getGeneratedKeys()) {
                            if (kunci.next()) {
                                hasil.setIdPembayaran(kunci.getInt(1));
                            }
                        }
                    }
                } else {
                    if (Pembayaran.DISETUJUI.equals(statusLama)) {
                        throw new ValidasiException("Pembayaran booking ini sudah disetujui (lunas).");
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE tb_pembayaran SET jumlah = ?, bukti_bayar = ?, status_bayar = 'MENUNGGU', "
                          + "tanggal_bayar = CURRENT_TIMESTAMP WHERE id_pembayaran = ?")) {
                        ps.setLong(1, total);
                        ps.setString(2, namaFileBukti);
                        ps.setInt(3, idLama);
                        ps.executeUpdate();
                    }
                    hasil.setIdPembayaran(idLama);
                }
                c.commit();
                if (buktiLama != null) {
                    sewalapangan.util.BuktiBayar.hapus(buktiLama);   // bersihkan bukti lama yang tergantikan
                }
                return hasil;
            } catch (SQLException | ValidasiException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Semua pembayaran beserta detail booking. {@code status} null = semua status. */
    public List<Pembayaran> semua(String status) throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT_DETAIL);
        if (status != null) {
            sql.append("WHERE pb.status_bayar = ? ");
        }
        sql.append("ORDER BY FIELD(pb.status_bayar, 'MENUNGGU', 'DITOLAK', 'DISETUJUI'), pb.tanggal_bayar DESC");

        List<Pembayaran> hasil = new ArrayList<>();
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            if (status != null) {
                ps.setString(1, status);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Pembayaran p = new Pembayaran();
                    p.setIdPembayaran(rs.getInt("id_pembayaran"));
                    p.setIdBooking(rs.getInt("id_booking"));
                    p.setJumlah(rs.getLong("jumlah"));
                    p.setBuktiBayar(rs.getString("bukti_bayar"));
                    p.setStatusBayar(rs.getString("status_bayar"));
                    Booking b = BookingDAO.petakan(rs);
                    p.setBooking(b);
                    hasil.add(p);
                }
            }
        }
        return hasil;
    }

    /** Hanya pembayaran untuk booking yang masih AKTIF yang bisa divalidasi. */
    public boolean ubahStatus(int idPembayaran, String statusBaru) throws SQLException {
        String sql = "UPDATE tb_pembayaran pb JOIN tb_booking b ON b.id_booking = pb.id_booking "
                + "SET pb.status_bayar = ? WHERE pb.id_pembayaran = ? AND b.status_booking = 'AKTIF'";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, statusBaru);
            ps.setInt(2, idPembayaran);
            return ps.executeUpdate() > 0;
        }
    }
}
