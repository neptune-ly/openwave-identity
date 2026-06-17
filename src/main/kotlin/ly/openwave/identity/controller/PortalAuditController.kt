package ly.openwave.identity.controller

import ly.openwave.identity.service.PortalAuditService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/portal/audit-events")
class PortalAuditController(private val auditService: PortalAuditService) {
    @GetMapping
    fun auditEvents(
        @RequestParam(required = false, name = "entity_type") entityType: String?,
        @RequestParam(required = false, name = "entity_id") entityId: String?,
        @RequestParam(defaultValue = "100") limit: Int
    ): Map<String, Any> = mapOf("events" to auditService.list(limit, entityType, entityId))

    @GetMapping("/{id}")
    fun auditEvent(@PathVariable id: Long): Map<String, Any> = mapOf("event" to auditService.get(id))
}
