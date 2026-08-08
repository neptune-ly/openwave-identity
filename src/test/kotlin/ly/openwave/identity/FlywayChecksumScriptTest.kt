package ly.openwave.identity

import org.flywaydb.core.api.resource.LoadableResource
import org.flywaydb.core.internal.resolver.ChecksumCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class FlywayChecksumScriptTest {
    @Test
    fun `recovery checksum matches the Flyway library used by the service`() {
        val source = Path.of(
            "src/main/resources/db/migration/V18__handle_rename_and_retirement.sql",
        ).toAbsolutePath().normalize()
        val resource = object : LoadableResource() {
            override fun read(): Reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)
            override fun getAbsolutePath(): String = source.toString()
            override fun getAbsolutePathOnDisk(): String = source.toString()
            override fun getFilename(): String = source.fileName.toString()
            override fun getRelativePath(): String = source.fileName.toString()
        }

        val flywayChecksum = ChecksumCalculator.calculate(resource)
        val process = ProcessBuilder(
            "python3",
            "scripts/flyway-sql-checksum.py",
            source.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()

        assertEquals(0, process.waitFor(), output)
        assertTrue(output.matches(Regex("-?[0-9]+")), output)
        assertEquals(flywayChecksum, output.toInt())
    }
}
