package ch.maxant.kdc.mf.billing.boundary

import ch.maxant.kdc.mf.library.Context.Companion.COMMAND
import ch.maxant.kdc.mf.library.Context.Companion.EVENT
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.quarkus.arc.Arc
import io.quarkus.runtime.StartupEvent
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.utils.Bytes
import org.apache.kafka.streams.*
import org.apache.kafka.streams.kstream.*
import org.apache.kafka.streams.processor.ProcessorContext
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.QueryableStoreTypes
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.metrics.MetricUnits
import org.eclipse.microprofile.metrics.annotation.Timed
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.logging.Logger
import org.jboss.resteasy.annotations.SseElementType
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import javax.annotation.PreDestroy
import javax.enterprise.context.ApplicationScoped
import javax.enterprise.event.Observes
import javax.inject.Inject
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

@ApplicationScoped
@Path("/billing-stream")
@Tag(name = "billing")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@SuppressWarnings("unused")
class BillingStreamApplication(
    @Inject
    var om: ObjectMapper,

    @Inject
    var billingGroupStateConsumer: BillingGroupStateConsumer,

    @ConfigProperty(name = "kafka.bootstrap.servers")
    val kafkaBootstrapServers: String,

    @ConfigProperty(name = "ch.maxant.kdc.mf.billing.failRandomlyForTestingPurposes", defaultValue = "false")
    var failRandomlyForTestingPurposes: Boolean
) {
    private lateinit var jobsView: ReadOnlyKeyValueStore<String, String>

    private lateinit var groupsView: ReadOnlyKeyValueStore<String, String>

    private lateinit var streams: KafkaStreams

    private val random = Random()

    private val log = Logger.getLogger(this.javaClass)

    val groupsStoreName = "billing-store-groups"

    val allJobsStoreName = "billing-store-jobs-all"

    @SuppressWarnings("unused")
    fun init(@Observes e: StartupEvent) {
        val props = Properties()
        props[StreamsConfig.APPLICATION_ID_CONFIG] = "mf-billing-streamapplication"
        props[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaBootstrapServers
        props[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.String()::class.java
        props[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = Serdes.String()::class.java

        val builder = StreamsBuilder()

        val streamOfGroups = builder.stream<String, String>("billing-internal-stream")

        // we do the following, so that we dont have to create locks across JVMs, for updating state. imagine two pods
        // processing two results and updating stats in the job at the same time after both have read the latest state
        // from the GKT/KT. the last one wins and overwrites the data written by the first one.
        // doing it this way with the aggregate, we are guaranteed to have correct data.
        // this is the kafka way of doing things.

        // JOBS - this state is NOT guaranteed to be totally up to date with every group, as it might be processed
        // after a group is processed and sent downstream, processed and returned! the single source of synchronous
        // truth is the group state!
        streamOfGroups.groupByKey()
                .aggregate(Initializer { om.writeValueAsString(JobState()) },
                            jobsAggregator,
                            Named.`as`("billing-internal-aggregate-state-jobs"),
                            Materialized.`as`("billing-store-jobs"))
                .toStream(Named.`as`("billing-internal-stream-state-jobs"))
                .peek {k, v -> log.info("aggregated group into job $k: $v")}
                .to(BILLING_INTERNAL_STATE_JOBS)

        val allJobsStore: Materialized<String, String, KeyValueStore<Bytes, ByteArray>> = Materialized.`as`(allJobsStoreName)
        builder.globalTable(BILLING_INTERNAL_STATE_JOBS, allJobsStore)

        builder.stream<String, String>(BILLING_INTERNAL_STATE_JOBS)
            .branch(Named.`as`("billing-internal-branch-job-successful"),
                Predicate{ _, v -> catching(v) { om.readValue<JobState>(v).completed != null } }
            )[0]
            .map<String, String>({ k, v ->
                KeyValue(k, buildReadPricesCommand(v))
             }, Named.`as`("billing-job-completed-to-event-mapper"))
            .transform(TransformerSupplier<String, String, KeyValue<String, String>>{
                MfTransformer(EVENT, "BILL_CREATED")
            })
            .peek {k, v -> log.info("publishing successful billing event $k: $v")}
            .to("billing-events")

        // GROUPS - single truth of true state relating to the billing of groups of contracts
        val groupsStore: Materialized<String, String, KeyValueStore<Bytes, ByteArray>> = Materialized.`as`(groupsStoreName)
        val groupsTable = streamOfGroups
                .groupBy { _, value -> om.readTree(value).get("groupId").asText() }
                .aggregate(Initializer { om.writeValueAsString(GroupState()) },
                        groupsAggregator,
                        Named.`as`("billing-internal-aggregate-state-groups"),
                        groupsStore)
        groupsTable.toStream(Named.`as`("billing-internal-stream-state-groups"))
                    .peek {k, v -> log.info("aggregated group $k: $v")}
                    .to(BILLING_INTERNAL_STATE_GROUPS)

        // now route the processed group to wherever it needs to go, depending on the next process step
        val branches = builder.stream<String, String>(BILLING_INTERNAL_STATE_GROUPS)
            .branch(Named.`as`("billing-internal-branch"),
                Predicate{ _, v -> catching(v) { om.readValue<GroupState>(v).group.nextProcessStep == BillingProcessStep.READ_PRICE } },
                Predicate{ _, v -> catching(v) { om.readValue<GroupState>(v).group.nextProcessStep == BillingProcessStep.RECALCULATE_PRICE } },
                Predicate{ _, v -> catching(v) { om.readValue<GroupState>(v).group.nextProcessStep == BillingProcessStep.BILL } }
            )

        // READ_PRICE
        branches[0]
            .map<String, String>({ k, v ->
                KeyValue(k, buildReadPricesCommand(v))
             }, Named.`as`("billing-internal-to-read-pricing-mapper"))
            .transform(TransformerSupplier<String, String, KeyValue<String, String>>{
                MfTransformer(COMMAND, "READ_PRICES_FOR_GROUP_OF_CONTRACTS")
            })
            .peek {k, v -> log.info("sending group to read prices $k: $v")}
            .to("contracts-event-bus")

        // RECALCULATE_PRICE
        branches[1]
            .map<String, String>({ k, v ->
                TODO() //KeyValue(k, v) // TODO
            }, Named.`as`("billing-internal-to-recalculate-pricing-mapper"))
            .transform(TransformerSupplier<String, String, KeyValue<String, String>>{
                MfTransformer(COMMAND, "RECALCULATE_PRICES_FOR_GROUP_OF_CONTRACTS")
            })
            .peek {k, v -> log.info("sending group to recalculate prices $k: $v")}
            .to("contracts-event-bus")

        // BILL
        branches[2]
            .map<String, String>({ k, v ->
                KeyValue(k, mapGroupStateToGroup(v))
            }, Named.`as`("billing-internal-to-billing-mapper"))
            .transform(TransformerSupplier<String, String, KeyValue<String, String>>{
                MfTransformer(COMMAND, BILL_GROUP)
            })
            .peek {k, v -> log.info("sending group for internal billing $k: $v")}
            .to("billing-internal-bill")

        val topology = builder.build()
        println(topology.describe())

        streams = KafkaStreams(topology, props)

        streams.setUncaughtExceptionHandler { thread, throwable ->
            log.error("BSA002 handling an uncaught error on thread $thread: ${throwable.message} AND SHUTTING DOWN", throwable )
            Arc.shutdown()
        }

        streams.start()

        println(topology.describe())
    }

    private fun catching(value: String, f: () -> Boolean) =
        try {
            f()
        } catch(e: Exception) {
            log.error("BSA003 Failed to do something ${e.message} AND DISPOSING OF RECORD WITH VALUE $value AWAY!", e)
            false
        }

    private fun buildReadPricesCommand(v: String): String {
        val state = om.readValue<GroupState>(v)

        val fail = failRandomlyForTestingPurposes && random.nextInt(100) == 1
        if(fail) {
            log.warn("failing job ${state.group.jobId} and group ${state.group.groupId} for testing purposes at pricing!")
        }

        val commands = mutableListOf<PricingCommand>()
        for(contract in state.group.contracts) {
            for(period in contract.basePeriodsToPrice) {
                commands.add(PricingCommand(contract.contractId, period.from, period.to))
            }
        }
        val command = PricingCommandGroup(state.group.groupId, commands, false, fail)
        return om.writeValueAsString(command)
    }

    private fun mapGroupStateToGroup(v: String): String {
        val state = om.readValue<GroupState>(v)
        return om.writeValueAsString(state.group)
    }

    val jobsAggregator = Aggregator {k: String, v: String, j: String ->
        val jobState = om.readValue<JobState>(j)
        val group = om.readValue<Group>(v)
        when(group.failedProcessStep) {
            BillingProcessStep.READ_PRICE, BillingProcessStep.RECALCULATE_PRICE  -> {
                jobState.groups.computeIfAbsent(group.groupId) { State.FAILED }
                jobState.state = State.FAILED
                jobState.numContractsPricingFailed += group.contracts.size
            }
            BillingProcessStep.BILL  -> {
                jobState.groups.computeIfAbsent(group.groupId) { State.FAILED }
                jobState.state = State.FAILED
                jobState.numContractsBillingFailed += group.contracts.size
            }
            else -> Unit // noop
        }
        when(group.nextProcessStep) {
            BillingProcessStep.READ_PRICE, BillingProcessStep.RECALCULATE_PRICE  -> {
                jobState.jobId = group.jobId // just in case its not set yet

                jobState.groups.computeIfAbsent(group.groupId) { State.STARTED }
                jobState.numContractsTotal += group.contracts.size
                jobState.numContractsPricing += group.contracts.size
            }
            BillingProcessStep.BILL -> {
                jobState.numContractsBilling += group.contracts.size
            }
            BillingProcessStep.COMMS -> {
                jobState.groups[group.groupId] = State.COMPLETED
                if(jobState.groups.values.all { it == State.COMPLETED }) {
                    jobState.state = State.COMPLETED
                    jobState.completed = LocalDateTime.now()
                }
                jobState.numContractsComplete += group.contracts.size
            }
        }
        om.writeValueAsString(jobState)
    }

    val groupsAggregator = Aggregator {k: String, v: String, g: String ->
        try {
            val groupState = om.readValue<GroupState>(g)
            val group = om.readValue<Group>(v)

            // copy latest state from group into state object
            groupState.group = group

            if(group.failedProcessStep != null) {
                groupState.state = State.FAILED
            }

            when(group.nextProcessStep) {
                BillingProcessStep.READ_PRICE,
                BillingProcessStep.RECALCULATE_PRICE,
                BillingProcessStep.BILL -> Unit // noop
                BillingProcessStep.COMMS -> {
                    groupState.state = State.COMPLETED
                    groupState.finished = LocalDateTime.now()
                }
            }

            om.writeValueAsString(groupState)
        } catch(e: Exception) {
            log.error("BSA001 Failed to aggregate group ${e.message} - DISPOSING OF RECORD WITH KEY $k AND VALUE $v", e)
            g
        }
    }

    @PreDestroy
    @SuppressWarnings("unused")
    fun predestroy() {
        streams.close()
    }

    /** seems to not like immediate fetching of the store during init - so lets do it lazily */
    private fun getAllJobs() =
        try {
            jobsView.all()
        } catch(e: UninitializedPropertyAccessException) {
            jobsView = streams.store(StoreQueryParameters.fromNameAndType(allJobsStoreName, QueryableStoreTypes.keyValueStore<String, String>()))
            jobsView.all()
        }

    /** seems to not like immediate fetching of the store during init - so lets do it lazily */
    private fun getJob(key: String) =
        try {
            jobsView.get(key)
        } catch(e: UninitializedPropertyAccessException) {
            jobsView = streams.store(StoreQueryParameters.fromNameAndType(allJobsStoreName, QueryableStoreTypes.keyValueStore<String, String>()))
            jobsView.get(key)
        }

    /** seems to not like immediate fetching of the store during init - so lets do it lazily */
    private fun getGroup(key: String) =
        try {
            groupsView.get(key)
        } catch(e: UninitializedPropertyAccessException) {
            groupsView = streams.store(StoreQueryParameters.fromNameAndType(groupsStoreName, QueryableStoreTypes.keyValueStore<String, String>()))
            groupsView.get(key)
        }

    @GET
    @Path("/jobs")
    @Operation(summary = "NOTE: knows about every job!")
    @Timed(unit = MetricUnits.MILLISECONDS)
    fun getJobs(): Response {
        val jobs = mutableListOf<JobState>()
        getAllJobs().forEachRemaining { jobs.add(om.readValue(it.value)) }
        return Response.ok(jobs).build()
    }

    @GET
    @Path("/job/{id}")
    @Operation(summary = "NOTE: knows about every job!")
    @Timed(unit = MetricUnits.MILLISECONDS)
    fun getJob(@Parameter(name = "id") @PathParam("id") id: UUID): Response {
        return Response.ok(getJob(id.toString())).build()
    }

    @GET
    @Path("/group/{id}")
    @Operation(summary = "NOTE: only knows about local state!")
    @Timed(unit = MetricUnits.MILLISECONDS)
    fun getGroup(@Parameter(name = "id") @PathParam("id") id: UUID): Response {
        return Response.ok(getGroup(id.toString())).build()
    }

    @GET
    @Path("/notifications")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @SseElementType(MediaType.APPLICATION_JSON)
    @Operation(summary = "get notification of changes to all groups of any job that is currently running")
    fun notifications() = billingGroupStateConsumer.broadcaster

    /** NOTE: only knows about local state! */
    fun getContract(groupId: UUID, contractId: UUID) = om.readValue<GroupState>(getGroup(groupId.toString())).group.contracts.find { it.contractId == contractId }!!

    companion object {
        const val BILL_GROUP = "BILL_GROUP"
        const val BILLING_INTERNAL_STATE_JOBS = "billing-internal-state-jobs"
        const val BILLING_INTERNAL_STATE_GROUPS = "billing-internal-state-groups"
    }

}

class MfTransformer(private val headerName: String, private val headerValue: String): Transformer<String, String, KeyValue<String, String>> {
    private lateinit var context: ProcessorContext

    override fun init(context: ProcessorContext) {
        this.context = context
    }

    override fun transform(key: String, value: String): KeyValue<String, String> {
        context.headers().add(headerName, headerValue.toByteArray(StandardCharsets.UTF_8))
        return KeyValue(key, value)
    }

    override fun close() {
    }
}

data class JobState(var jobId: UUID,
                    var state: State,
                    var groups: HashMap<UUID, State>, // 1'000 x 36-chars-UUID + 10-chars-state => less than 100kb?

                    // estimates:

                    var numContractsTotal: Int,
                    var numContractsPricing: Int,
                    var numContractsPricingFailed: Int,
                    var numContractsBilling: Int,
                    var numContractsBillingFailed: Int,
                    var numContractsComplete: Int,
                    var failedContractIds: List<UUID>, // if every contract failed, that'd be 36MB... a litle big!
                    var started: LocalDateTime,
                    var completed: LocalDateTime?
) {
    constructor():
            this(UUID.randomUUID(), State.STARTED, hashMapOf<UUID, State>(), 0, 0, 0, 0, 0, 0, emptyList(), LocalDateTime.now(), null)

}

enum class State {
    STARTED, COMPLETED, FAILED
}

// 1'000 x 1'000 bytes => less than 1MB? that's the aim, as kafkas default max record size is 1mb
// - control it using group size! 1'000 is probably too big as it is, regarding reading/writing to DB
data class GroupState(var group: Group,
                      var state: State,
                      var started: LocalDateTime,
                      var finished: LocalDateTime?
) {
    constructor():
            this(Group(UUID.randomUUID(), UUID.randomUUID(), listOf(), BillingProcessStep.READ_PRICE), State.STARTED, LocalDateTime.now(), null)
}

// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// internal models which is enriched as we go through the billing process
// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
data class Group(val jobId: UUID,
                 val groupId: UUID,
                 val contracts: List<Contract>,
                 val nextProcessStep: BillingProcessStep?,  // effectively, where to send the model next, because in
                                                            // order to ensure that the state that is written to rocksdb
                                                            // is always up to date before it is used, we need a
                                                            // "point-to-point" architecture in that we send data from
                                                            // one station to the next. the state store is one of those
                                                            // stations, and is in charge of updating the global ktable
                                                            // before sending a command to the next component to execute
                                                            // the next process step. null if failed.
                 val failedProcessStep: BillingProcessStep? = null, // where, if at all the group failed
                 val started: LocalDateTime = LocalDateTime.now()
)

data class Contract(val jobId: UUID,
                    val groupId: UUID,
                    val contractId: UUID,
                    val billingDefinitionId: String,
                    val basePeriodsToPrice: List<Period>,
                    val periodsToBill: List<BillPeriod>
)

open class Period(val from: LocalDate,
                  val to: LocalDate,
                  var price: BigDecimal = BigDecimal.ZERO // initially unknown, but read/recalculated and written here
)

class BillPeriod(from: LocalDate,
                  to: LocalDate,
                  price: BigDecimal = BigDecimal.ZERO,
                  var billId: UUID? = null): Period(from, to, price)

enum class BillingProcessStep {
    READ_PRICE, // for new contracts, the price is already known, as it was offered to the customer, so just go read it
    RECALCULATE_PRICE, // for recurring billing, we first need to go recalculate the price
    BILL, // create the actual bill
    COMMS // send the bill to the customer
    // RECEIVE_PAYMENT, SEND_REMINDER, TERMINATE_CONTRACT, etc., etc.
}

// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// pricing command => sent to pricing. dicated by the pricing component!
// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
data class PricingCommandGroup(val groupId: UUID, val commands: List<PricingCommand>, val recalculate: Boolean, val failForTestingPurposes: Boolean)

data class PricingCommand(val contractId: UUID, val from: LocalDate, val to: LocalDate)