package ch.maxant.kdc.mf.contracts.control

import ch.maxant.kdc.mf.contracts.definitions.*
import ch.maxant.kdc.mf.contracts.entity.ComponentEntity
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*
import javax.enterprise.context.Dependent
import javax.inject.Inject
import javax.persistence.EntityManager

@Dependent
class ComponentsRepo(
        @Inject
        var em: EntityManager,

        @Inject
        var om: ObjectMapper
){
    fun saveInitialDraft(contractId: UUID, pack: Packaging) {
        saveInitialDraft(contractId, null, pack)
    }

    private fun saveInitialDraft(contractId: UUID, parentId: UUID?, component: ComponentDefinition) {
        val config = om.writeValueAsString(component.configs)
        val e = ComponentEntity(UUID.randomUUID(), parentId, contractId, config, component.componentDefinitionId)
        if(component is Product) e.productId = component.productId
        component.componentId = e.id
        em.persist(e)
        component.children.forEach { saveInitialDraft(contractId, e.id, it) }
    }

    fun updateConfig(contractId: UUID, componentId: UUID, param: ConfigurableParameter, newValue: String): Configuration<*> {
        val component = em.find(ComponentEntity::class.java, componentId)
        require(contractId == component.contractId) { "contract id doens't match" }

        val configs = om.readValue<ArrayList<Configuration<*>>>(component.configuration)
        val config = configs.find { it.name == param } !!

        when {
            DateConfigurationDefinition.matches(config) -> (config as DateConfiguration).value = LocalDate.parse(newValue)
            StringConfigurationDefinition.matches(config) -> (config as StringConfiguration).value = newValue
            BigDecimalConfigurationDefinition.matches(config) -> (config as BigDecimalConfiguration).value = BigDecimal(newValue)
            IntConfigurationDefinition.matches(config) -> (config as IntConfiguration).value = Integer.parseInt(newValue)
            PercentConfigurationDefinition.matches(config) -> (config as PercentConfiguration).value = BigDecimal(newValue)
            MaterialConfigurationDefinition.matches(config) -> (config as MaterialConfiguration).value = Material.valueOf(newValue)
            else -> TODO()
        }

        // TODO validate within limits of product
        // TODO publish product limits with rest

        component.configuration = om.writeValueAsString(configs)

        return config
    }
}
