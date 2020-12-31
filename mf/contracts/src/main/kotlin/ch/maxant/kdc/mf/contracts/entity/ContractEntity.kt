package ch.maxant.kdc.mf.contracts.entity

import ch.maxant.kdc.mf.contracts.dto.Component
import org.hibernate.annotations.Type
import java.time.LocalDateTime
import java.util.*
import javax.persistence.*

@Entity
@Table(name = "T_CONTRACTS")
open class ContractEntity( // add open, rather than rely on maven plugin, because @QuarkusTest running in IntelliJ seems to think its final

        @Id
        @Column(name = "ID")
        @Type(type = "uuid-char")
        open var id: UUID,

        @Column(name = "STARTTIME", nullable = false)
        open var start: LocalDateTime,

        @Column(name = "ENDTIME", nullable = false)
        open var end: LocalDateTime,

        @Column(name = "STATE", nullable = false)
        @Enumerated(EnumType.STRING)
        open var contractState: ContractState,

        @Column(name = "SYNC_TIMESTAMP", nullable = false)
        open var syncTimestamp: Long,

        @Column(name = "CREATED_AT", nullable = false)
        open var createdAt: LocalDateTime = LocalDateTime.now(),

        @Column(name = "CREATED_BY", nullable = false)
        open var createdBy: String,

        @Column(name = "OFFERED_AT")
        open var offeredAt: LocalDateTime?,

        @Column(name = "OFFERED_BY")
        open var offeredBy: String?,

        @Column(name = "ACCEPTED_AT")
        open var acceptedAt: LocalDateTime?,

        @Column(name = "ACCEPTED_BY")
        open var acceptedBy: String?,

        @Column(name = "APPROVED_AT")
        open var approvedAt: LocalDateTime?,

        @Column(name = "APPROVED_BY")
        open var approvedBy: String?
) {
    /** for hibernate */
    constructor() : this(UUID.randomUUID(), LocalDateTime.MIN, LocalDateTime.MAX, ContractState.DRAFT, 0, LocalDateTime.now(), "", null, null, null, null, null, null)

    /** for initially creating a draft */
    constructor(id: UUID, start: LocalDateTime, end: LocalDateTime, createdBy: String) :
            this(id, start, end, ContractState.DRAFT, System.currentTimeMillis(), LocalDateTime.now(), createdBy, null, null, null, null, null, null)

    @Transient
    var components: List<Component>? = null
}
