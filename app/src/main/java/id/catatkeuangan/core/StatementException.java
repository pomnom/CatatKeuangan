package id.catatkeuangan.core;

/** Kesalahan saat membaca e-statement; pesannya langsung ditampilkan ke pengguna. */
public class StatementException extends Exception {
    public StatementException(String message) {
        super(message);
    }

    public StatementException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Password kosong atau salah untuk file yang terkunci. */
    public static final class WrongPassword extends StatementException {
        public WrongPassword(String message) {
            super(message);
        }
    }
}
