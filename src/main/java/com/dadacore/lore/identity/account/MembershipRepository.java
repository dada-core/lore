package com.dadacore.lore.identity.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.identity.OrganizationRole;
import com.dadacore.lore.shared.PageQuery;
import com.dadacore.lore.shared.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MembershipRepository {

    private static final String MEMBER_COLUMNS = """
            select u.id, u.display_name, u.email, u.status, u.last_login_at, m.role, m.created_at
            from membership m join user_account u on u.id = m.user_id
            """;

    private final JdbcClient jdbc;

    public MembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrganizationRole> role(UUID organizationId, UUID userId) {
        return jdbc.sql("select role from membership where organization_id = ? and user_id = ?")
                .params(organizationId, userId)
                .query((rs, row) -> OrganizationRole.valueOf(rs.getString("role")))
                .optional();
    }

    public void add(UUID organizationId, UUID userId, OrganizationRole role) {
        jdbc.sql("insert into membership (organization_id, user_id, role) values (?, ?, ?)")
                .params(organizationId, userId, role.name())
                .update();
    }

    public void changeRole(UUID organizationId, UUID userId, OrganizationRole role) {
        jdbc.sql("update membership set role = ? where organization_id = ? and user_id = ?")
                .params(role.name(), organizationId, userId)
                .update();
    }

    public void remove(UUID organizationId, UUID userId) {
        jdbc.sql("delete from membership where organization_id = ? and user_id = ?")
                .params(organizationId, userId)
                .update();
    }

    /**
     * Locks the owner memberships until the end of the transaction and counts the active ones:
     * two administrators cannot remove the last two owners at the same time.
     */
    public int lockActiveOwners(UUID organizationId) {
        return jdbc.sql("""
                select m.user_id from membership m join user_account u on u.id = m.user_id
                where m.organization_id = ? and m.role = 'OWNER' and u.status = 'ACTIVE'
                for update of m
                """)
                .param(organizationId)
                .query(UUID.class)
                .list()
                .size();
    }

    public Optional<MemberRow> member(UUID organizationId, UUID userId) {
        return jdbc.sql(MEMBER_COLUMNS + " where m.organization_id = ? and m.user_id = ?")
                .params(organizationId, userId)
                .query(MembershipRepository::member)
                .optional();
    }

    /**
     * Members ordered by name. The search ignores case and accents, on the name and the address.
     */
    public PageResponse<MemberRow> members(UUID organizationId, String search, PageQuery page) {
        StringBuilder where = new StringBuilder(" where m.organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        if (search != null && !search.isBlank()) {
            String pattern = "%" + escapeLike(search.strip()) + "%";
            where.append(" and (unaccent(u.display_name) ilike unaccent(?) or u.email ilike ?)");
            params.add(pattern);
            params.add(pattern);
        }
        long total = jdbc.sql("select count(*) from membership m join user_account u on u.id = m.user_id" + where)
                .params(params)
                .query(Long.class)
                .single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.size());
        pageParams.add(page.offset());
        List<MemberRow> items = jdbc.sql(MEMBER_COLUMNS + where
                        + " order by lower(unaccent(u.display_name)), u.id limit ? offset ?")
                .params(pageParams)
                .query(MembershipRepository::member)
                .list();
        return new PageResponse<>(items, page.page(), page.size(), total);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static MemberRow member(ResultSet rs, int row) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        return new MemberRow(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                rs.getString("email"),
                AccountStatus.valueOf(rs.getString("status")),
                OrganizationRole.valueOf(rs.getString("role")),
                lastLogin == null ? null : lastLogin.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
