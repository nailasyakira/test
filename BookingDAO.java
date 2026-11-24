package sewalapangan.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import sewalapangan.config.Koneksi;
import sewalapangan.model.Booking;
import sewalapangan.model.Lapangan;
import sewalapangan.model.ValidasiException;

public class BookingDAO {

    private static final String SELECT_DETAIL =
            "SELECT b.*, l.nama_lapangan, p.nama AS nama_pengguna, pb.status_bayar "
          + "FROM tb_booking b "
          + "JOIN tb_lapangan l ON l.id_lapangan = b.id_lapangan "
          + "JOIN tb_pengguna p ON p.id_pengguna = b.id_pengguna "
          + "LEFT JOIN tb_pembayaran pb ON pb.id_booking = b.id_booking ";

    /** Memetakan satu baris hasil query ke objek Booking (dipakai juga oleh PembayaranDAO). */
    static Booking petakan(ResultSet rs) throws SQLException {
        Booking b = new Booking();
        b.setIdBooking(rs.getInt("id_booking"));
        b.setIdPengguna(rs.getInt("id_pengguna"));
        b.setIdLapangan(rs.getInt("id_lapangan"));
        b.setTanggal(rs.getObject("tanggal", LocalDate.class));
        b.setJamMulai(rs.getObject("jam_mulai", LocalTime.class));
        b.setJamSelesai(rs.getObject("jam_selesai", LocalTime.class));
        b.setTotalHarga(rs.getLong("total_harga"));
        b.setStatusBooking(rs.getString("status_booking"));
        b.setNamaLapangan(rs.getString("nama_lapangan"));
        b.setNamaPengguna(rs.getString("nama_pengguna"));
        b.setStatusBayar(rs.getString("status_bayar"));
        return b;
    }

    /**
     * Membuat booking baru.
     * Seluruh proses berjalan dalam satu transaksi: baris lapangan dikunci (FOR UPDATE)
     * sehingga dua pemesanan bersamaan untuk lapangan yang sama diproses bergantian,
     * dan pengecekan bentrok jadwal selalu memakai data terbaru.
     */
    public Booking buat(int idPengguna, int idLapangan, LocalDate tanggal, LocalTime mulai, LocalTime selesai)
            throws SQLException, ValidasiException {

        if (tanggal == null || mulai == null || selesai == null) {
            throw new ValidasiException("Tanggal dan jam booking wajib diisi.");
        }
        if (!selesai.isAfter(mulai)) {
            throw new ValidasiException("Jam selesai harus lebih besar dari jam mulai.");
        }
        if (mulai.isBefore(Booking.JAM_BUKA) || selesai.isAfter(Booking.JAM_TUTUP)) {
            throw new ValidasiException("Lapangan beroperasi pukul "
                    + Booking.JAM_BUKA + " - " + Booking.JAM_TUTUP + ".");
        }
        if (LocalDateTime.of(tanggal, mulai).isBefore(LocalDateTime.now())) {
            throw new ValidasiException("Waktu yang dipilih sudah lewat.");
        }

        try (Connection c = Koneksi.getKoneksi()) {
            c.setAutoCommit(false);
            try {
                long harga;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT harga, status FROM tb_lapangan WHERE id_lapangan = ? FOR UPDATE")) {
                    ps.setInt(1, idLapangan);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ValidasiException("Lapangan tidak ditemukan.");
                        }
                        if (!Lapangan.STATUS_TERSEDIA.equals(rs.getString("status"))) {
                            throw new ValidasiException("Lapangan sedang dalam perawatan dan belum dapat disewa.");
                        }
                        harga = rs.getLong("harga");
                    }
                }

                // Dua rentang waktu bentrok bila: mulai_lama < selesai_baru DAN selesai_lama > mulai_baru
                String cek = "SELECT COUNT(*) FROM tb_booking WHERE id_lapangan = ? AND tanggal = ? "
                        + "AND status_booking = 'AKTIF' AND jam_mulai < ? AND jam_selesai > ?";
                try (PreparedStatement ps = c.prepareStatement(cek)) {
                    ps.setInt(1, idLapangan);
                    ps.setObject(2, tanggal);
                    ps.setObject(3, selesai);
                    ps.setObject(4, mulai);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) > 0) {
                            throw new ValidasiException(
                                    "Jadwal bentrok. Lapangan sudah dipesan pada jam tersebut, silakan pilih jam lain.");
                        }
                    }
                }

                long menit = Duration.between(mulai, selesai).toMinutes();
                long total = harga * menit / 60;

                Booking b = new Booking();
                String insert = "INSERT INTO tb_booking (id_pengguna, id_lapangan, tanggal, jam_mulai, jam_selesai, total_harga) "
                        + "VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = c.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, idPengguna);
                    ps.setInt(2, idLapangan);
                    ps.setObject(3, tanggal);
                    ps.setObject(4, mulai);
                    ps.setObject(5, selesai);
                    ps.setLong(6, total);
                    ps.executeUpdate();
                    try (ResultSet kunci = ps.getGeneratedKeys()) {
                        if (kunci.next()) {
                            b.setIdBooking(kunci.getInt(1));
                        }
                    }
                }
                c.commit();

                b.setIdPengguna(idPengguna);
                b.setIdLapangan(idLapangan);
                b.setTanggal(tanggal);
                b.setJamMulai(mulai);
                b.setJamSelesai(selesai);
                b.setTotalHarga(total);
                return b;
            } catch (SQLException | ValidasiException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public List<Booking> riwayatUser(int idPengguna) throws SQLException {
        String sql = SELECT_DETAIL + "WHERE b.id_pengguna = ? ORDER BY b.tanggal DESC, b.jam_mulai DESC";
        List<Booking> hasil = new ArrayList<>();
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idPengguna);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hasil.add(petakan(rs));
                }
            }
        }
        return hasil;
    }

    /**
     * Jadwal penggunaan lapangan.
     * @param tanggal       null = semua tanggal
     * @param idLapangan    null = semua lapangan
     * @param sertakanBatal true = ikut menampilkan booking yang dibatalkan (untuk Admin)
     */
    public List<Booking> jadwal(LocalDate tanggal, Integer idLapangan, boolean sertakanBatal) throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT_DETAIL).append("WHERE 1 = 1 ");
        List<Object> parameter = new ArrayList<>();
        if (tanggal != null) {
            sql.append("AND b.tanggal = ? ");
            parameter.add(tanggal);
        }
        if (idLapangan != null) {
            sql.append("AND b.id_lapangan = ? ");
            parameter.add(idLapangan);
        }
        if (!sertakanBatal) {
            sql.append("AND b.status_booking = 'AKTIF' ");
        }
        sql.append("ORDER BY b.tanggal, b.jam_mulai, l.nama_lapangan");

        List<Booking> hasil = new ArrayList<>();
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < parameter.size(); i++) {
                ps.setObject(i + 1, parameter.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hasil.add(petakan(rs));
                }
            }
        }
        return hasil;
    }

    public boolean batalkan(int idBooking) throws SQLException {
        String sql = "UPDATE tb_booking SET status_booking = 'DIBATALKAN' WHERE id_booking = ? AND status_booking = 'AKTIF'";
        try (Connection c = Koneksi.getKoneksi(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idBooking);
            return ps.executeUpdate() > 0;
        }
    }
}
