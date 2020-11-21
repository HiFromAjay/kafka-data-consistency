package ch.maxant.kdc.mf.library

import ch.maxant.kdc.mf.library.Context.Companion.COMMAND
import ch.maxant.kdc.mf.library.Context.Companion.DEMO_CONTEXT
import ch.maxant.kdc.mf.library.Context.Companion.REQUEST_ID
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.commons.lang3.StringUtils
import org.jboss.logging.Logger
import org.jboss.logging.MDC
import java.util.*
import javax.inject.Inject
import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.annotation.WebFilter
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@WebFilter(asyncSupported = true, urlPatterns = ["*"])
@SuppressWarnings("unused")
class ContextWebFilter: Filter {

    @Inject
    lateinit var context: Context

    @Inject
    lateinit var om: ObjectMapper

    val log: Logger = Logger.getLogger(this.javaClass)

    override fun doFilter(req: ServletRequest, res: ServletResponse, filterChain: FilterChain) {
        val request = req as HttpServletRequest
        val response = res as HttpServletResponse

        context.requestId = getRequestId(request)
        MDC.put(REQUEST_ID, context.requestId)
        MDC.put(COMMAND, "${request.method} ${request.requestURI}")

        context.demoContext = readDemoContext(request)

        filterChain.doFilter(request, response)

        response.setHeader(REQUEST_ID, context.requestId.toString())
    }

    private fun getRequestId(request: HttpServletRequest): RequestId {
        val requestId = request.getHeader(REQUEST_ID)
        val rId = if (requestId != null && requestId.isNotEmpty())
            requestId
        else {
            log.info("creating requestId as it is missing in the request")
            UUID.randomUUID().toString()
        }
        return RequestId(rId)
    }

    private fun readDemoContext(request: HttpServletRequest): DemoContext {
        var raw = request.getHeader(DEMO_CONTEXT)
        raw = if(StringUtils.isEmpty(raw)) "{}" else raw
        val dc = om.readValue<DemoContext>(raw)
        dc.json = raw
        return dc
    }
}