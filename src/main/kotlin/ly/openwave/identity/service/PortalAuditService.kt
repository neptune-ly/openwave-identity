package ly.openwave.identity.service

import com.fasterxml.jackson.databind.ObjectMapper
import ly.openwave.identity.entity.PortalAuditEventEntity
import ly.openwave.identity.repository.PortalAuditEventRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class PortalAuditService(
    private val repo: PortalAuditEventRepository,
    private val objectMapper: ObjectMapper
) {
    fun get(id: Long) = repo.findById(id).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Audit event not found")
    }.let {
        mapOf(
            "id" to it.id,
            "actor" to it.actor,
            "action" to it.action,
            "entity_type" to it.entityType,
            "entity_id" to it.entityId,
            "details" to it.details,
            "created_at" to it.createdAt
        )
    }

    fun record(authentication: Authentication?, action: String, entityType: String, entityId: String, details: Map<String, Any?> = emptyMap()) {
        repo.save(
            PortalAuditEventEntity(
                actor = authentication?.name ?: "system",
                action = action,
                entityType = entityType,
                entityId = entityId,
                details = if (details.isEmpty()) null else objectMapper.writeValueAsString(details)
            )
        )
    }

    fun list(limit: Int, entityType: String?, entityId: String?) =
        if (!entityType.isNullOrBlank() && !entityId.isNullOrBlank()) {
            repo.findAllByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType.uppercase(), entityId)
        } else {
            repo.findAllByOrderByCreatedAtDesc()
        }.take(limit.coerceIn(1, 500)).map { get(it.id) }
}
