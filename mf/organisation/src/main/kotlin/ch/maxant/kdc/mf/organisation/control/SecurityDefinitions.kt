package ch.maxant.kdc.mf.organisation.control

import ch.maxant.kdc.mf.library.Role
import ch.maxant.kdc.mf.organisation.control.ProcessSteps.*
import ch.maxant.kdc.mf.organisation.control.StaffRole.*
import ch.maxant.kdc.mf.organisation.control.PartnerRole.*
import javax.enterprise.context.Dependent

@Dependent
class SecurityDefinitions {

    fun getDefinitions(): SecurityDefinitionResponse {
        return SecurityDefinitionResponse(
                buildProcessSteps(Processes.values())
        )
    }

    private fun buildProcessSteps(processes: Array<Processes>): List<Node> {
        return processes.map {
            Node(it.name, Data(it.name, "Process"), buildProcessSteps(it.processSteps, it.name))
        }
    }
    private fun buildProcessSteps(steps: Set<ProcessSteps>, parentKey: String): List<Node> {
        return steps.map { ps ->
            val key = parentKey + "::" + ps.name
            val users: MutableList<User> = Partner.values().toMutableList()
            users.addAll(Staff.values())
            val relevantRoleMappings = RoleMappings.values()
                    .filter { rm -> rm.processStep == ps }
            val userList = relevantRoleMappings
                    .map { rm -> users.filter { u -> u.roles.contains(rm.role) } }
                    .flatten()
                    .map { it.un }
            Node(key, Data(ps.name, "Step", relevantRoleMappings.joinToString { "${it.role}" }, userList, ps.fqMethodNames), emptyList())
        }
    }
}

data class SecurityDefinitionResponse(val root: List<Node>)
data class Node(val key: String, val data: Data, val children: List<Node>)
data class Data(val name: String, val type: String, val roleMappings: String? = null, val users: List<String> = emptyList(), val methods: Set<String> = emptySet())

enum class StaffRole(private val description: String): Role {
    SALES_REP("A person who sells stuff"),
    SUPPLY_CHAIN_SPECIALIST("Someone specialising in sourcing products"),
    ORDER_COMPLETION_CONSULTANT("Someone who completes bespoke orders"),
    FINANCE_SPECIALIST("Someone who works with finances");

    override fun getDescription(): String {
        return this.description
    }
}

enum class PartnerRole(private val description: String): Role {
    CUSTOMER("Someone with a contract"),
    SUPPLIER("Supplies MF with materials");

    override fun getDescription(): String {
        return this.description
    }
}

enum class ProcessSteps(val fqMethodNames: Set<String>) {
    DRAFT(setOf(
        "ch.maxant.kdc.mf.contracts.boundary.DraftsResource.create",
        "ch.maxant.kdc.mf.contracts.boundary.DraftsResource.updateConfig",
        "ch.maxant.kdc.mf.contracts.boundary.ContractResource.getById"
    )),
    OFFER(setOf(
        "ch.maxant.kdc.mf.contracts.boundary.ContractResource.getById",
        "ch.maxant.kdc.mf.contracts.boundary.DraftsResource.offerDraft"
    )),
    ACCEPT(setOf(
        "ch.maxant.kdc.mf.contracts.boundary.ContractResource.getById",
        "ch.maxant.kdc.mf.contracts.boundary.ContractResource.acceptOffer"
    ))
}

enum class Processes(val processSteps: Set<ProcessSteps>) {
    SALES(setOf(DRAFT, OFFER, ACCEPT))
}

enum class RoleMappings(val role: Role, val processStep: ProcessSteps) {
    SALES_REP___DRAFT(SALES_REP, DRAFT),
    SALES_REP___OFFER(SALES_REP, OFFER),
    CUSTOMER___ACCEPT(CUSTOMER, ACCEPT)
}

