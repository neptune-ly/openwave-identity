package ly.openwave.identity.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

@Service
class BrandingAssetService(
    @Value("\${identity.assets.branding-path:/opt/openwave/assets/identity-branding}") private val brandingPath: String,
    @Value("\${identity.assets.public-base-url:https://identity.neptune.ly/v1/assets}") private val publicBaseUrl: String
) {
    private val allowedTypes = mapOf(
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/webp" to "webp"
    )

    fun storeBankLogo(bankHandle: String, file: MultipartFile): String {
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Logo file is required")
        if (file.size > 1_000_000) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Logo must be 1 MB or smaller")
        val extension = allowedTypes[file.contentType?.lowercase()]
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Logo must be PNG, JPG, or WebP")
        val dir = Path.of(brandingPath, "banks")
        Files.createDirectories(dir)
        val filename = "${safe(bankHandle)}-${UUID.randomUUID().toString().replace("-", "").take(16)}.$extension"
        val target = dir.resolve(filename).normalize()
        if (!target.startsWith(dir.normalize())) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid logo path")
        file.inputStream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        return "${publicBaseUrl.trimEnd('/')}/branding/banks/$filename"
    }

    fun resolve(scope: String, filename: String): Path {
        val dir = Path.of(brandingPath, safe(scope)).normalize()
        val target = dir.resolve(safe(filename)).normalize()
        if (!target.startsWith(dir) || !Files.exists(target)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found")
        }
        return target
    }

    private fun safe(value: String): String {
        val cleaned = value.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-")
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid asset name")
    }
}
