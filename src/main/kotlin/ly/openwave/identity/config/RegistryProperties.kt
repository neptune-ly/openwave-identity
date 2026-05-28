package ly.openwave.identity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "registry")
class RegistryProperties {
    var operator: String = "Neptune Fintech"
    var operatorUrl: String = "https://www.neptune.ly"
    var governanceCharterUrl: String = ""
    var sourceCodeUrl: String = ""
    var countryScope: String = "LY"
    var futureOperator: String = ""
    var uptimeSla: String = "99.9%"
    var adminKey: String = ""
    var adminUsername: String = ""
    var adminPassword: String = ""
    var resolveCacheTtlSeconds: Int = 60
    var resolveRateLimitPerMinute: Int = 120
}
