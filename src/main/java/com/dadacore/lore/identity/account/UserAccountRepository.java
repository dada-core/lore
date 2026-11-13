package com.dadacore.lore.identity.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {

    private static final String COLUMNS =
            "id, email, display_name, password_hash, status, locale, last_login_at, created_at";

    private final JdbcClient jdbc;

    public UserAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from user_account where id = ?")
                .param(id)
                .query(UserAccountRepository::account)
                .optional();
    }

    /** The address must already be normalized (lower case). */
    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql("select " + COLUMNS + " from user_account where email = ?")
                .param(email)
                .query(UserAccountRepository::account)
                .optional();
    }

    public boolean emailTaken(String email) {
        return jdbc.sql("select exists (select 1 from user_account where email = ?)")
                .param(email)
                .query(Boolean.class)
                .single();
    }

    public UserAccount create(String email, String displayName, String passwordHash, String locale) {
        return jdbc.sql("insert into user_account (email, display_name, password_hash, locale) values (?, ?, ?, ?)"
                        + " returning " + COLUMNS)
                .params(email, displayName, passwordHash, locale)
                .query(UserAccountRepository::account)
                .single();
    }

    public void updateProfile(UUID id, String displayName, String locale) {
        jdbc.sql("update user_account set display_name = ?, locale = ?, updated_at = now() where id = ?")
                .params(displayName, locale, id)
                .update();
    }

    public void updateEmail(UUID id, String email) {
        jdbc.sql("update user_account set email = ?, updated_at = now() where id = ?").params(email, id).update();
    }

    public void updatePassword(UUID id, String passwordHash) {
        jdbc.sql("update user_account set password_hash = ?, updated_at = now() where id = ?")
                .params(passwordHash, id)
                .update();
    }

    public void updateStatus(UUID id, AccountStatus status) {
        jdbc.sql("update user_account set status = ?, updated_at = now() where id = ?")
                .params(status.name(), id)
                .update();
    }

    public void recordLogin(UUID id, Instant at) {
        jdbc.sql("update user_account set last_login_at = ? where id = ?").params(Timestamp.from(at), id).update();
    }

    static UserAccount account(ResultSet rs, int row) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getString("locale"),
                lastLogin == null ? null : lastLogin.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
