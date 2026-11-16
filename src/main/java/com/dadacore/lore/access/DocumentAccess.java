package com.dadacore.lore.access;

import java.util.List;
import java.util.Optional;

import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.SqlCondition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Who sees a document, and what they may do with it.
 *
 * <ul>
 *   <li>A document is seen by those who see its space (see {@link SpaceAccess}).</li>
 *   <li>A restricted document is seen only by the people chosen, the maintainers of the space,
 *       the person in charge of the document, and the owners and administrators.</li>
 *   <li>What one may do follows the role in the space; the person in charge of a document may
 *       also restrict it or open it again, and the person who imported it may delete it while
 *       it has only one version.</li>
 * </ul>
 *
 * <p>A document the person cannot see answers {@code 404}, never {@code 403}.
 */
@Service
public class DocumentAccess {

    private final JdbcClient jdbc;
    private final SpaceAccess spaces;

    public DocumentAccess(JdbcClient jdbc, SpaceAccess spaces) {
        this.jdbc = jdbc;
        this.spaces = spaces;
    }

    /** The role of the person in the space of the document, empty when they cannot see it. */
    public Optional<SpaceRole> role(Viewer viewer, DocumentRef document) {
        Optional<SpaceRole> role = spaces.role(viewer, document.space());
        if (role.isEmpty() || !document.restricted() || role.get() == SpaceRole.MAINTAINER
                || viewer.userId().equals(document.ownerId())) {
            return role;
        }
        boolean chosen = jdbc.sql("select exists (select 1 from document_grant where document_id = ? and user_id = ?)")
                .params(document.id(), viewer.userId())
                .query(Boolean.class)
                .single();
        return chosen ? role : Optional.empty();
    }

    /**
     * The role of the person, when it includes {@code minimum}: {@code 404} when they cannot see the
     * document, {@code 403} when their role is not enough.
     */
    public SpaceRole require(Viewer viewer, DocumentRef document, SpaceRole minimum) {
        SpaceRole role = role(viewer, document).orElseThrow(ApiException::notFound);
        if (!role.includes(minimum)) {
            throw ApiException.forbidden(minimum == SpaceRole.MAINTAINER ? "maintainer_only" : "contributor_only");
        }
        return role;
    }

    /** True when the person may restrict the document or open it again: a maintainer, or its owner. */
    public boolean canManageAccess(Viewer viewer, DocumentRef document, SpaceRole role) {
        return role == SpaceRole.MAINTAINER
                || (role.includes(SpaceRole.CONTRIBUTOR) && viewer.userId().equals(document.ownerId()));
    }

    /** True when the person may delete the document: a maintainer, or who imported its only version. */
    public boolean canDelete(Viewer viewer, DocumentRef document, SpaceRole role) {
        return role == SpaceRole.MAINTAINER
                || (role.includes(SpaceRole.CONTRIBUTOR) && document.versionCount() == 1
                        && viewer.userId().equals(document.createdBy()));
    }

    /**
     * The documents the person can see, as a condition on a query that reads the document table
     * and the table of its space.
     *
     * <p>The caller limits its query to the organization of the person, then adds this condition.
     *
     * @param document the alias of the document table, for example {@code d}
     * @param space    the alias of the space table, for example {@code s}
     */
    public SqlCondition readableDocuments(Viewer viewer, String document, String space) {
        if (viewer.isAdmin()) {
            return SqlCondition.always();
        }
        String sql = "exists (select 1 from space_member sm where sm.space_id = " + document + ".space_id"
                + " and sm.user_id = ? and (" + space + ".archived_at is null or sm.role = 'MAINTAINER')"
                + " and (not " + document + ".restricted or sm.role = 'MAINTAINER' or " + document + ".owner_id = ?"
                + " or exists (select 1 from document_grant g where g.document_id = " + document + ".id and g.user_id = ?)))";
        return new SqlCondition(sql, List.of(viewer.userId(), viewer.userId(), viewer.userId()));
    }
}
