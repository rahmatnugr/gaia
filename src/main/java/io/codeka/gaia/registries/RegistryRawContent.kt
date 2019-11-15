package io.codeka.gaia.registries

import io.codeka.gaia.modules.bo.TerraformModule
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestTemplate
import java.util.*
import java.util.regex.Pattern

abstract class RegistryRawContent(private val registryType: RegistryType, private val restTemplate: RestTemplate) {

    /**
     * Returns the pattern to match the repository url.
     * The pattern should contains at least one group to extract the part of the repository to keep
     */
    protected abstract val pattern: Pattern

    open fun matches(url: String): Boolean {
        return pattern.matcher(url).matches()
    }

    open fun getReadme(module: TerraformModule): Optional<String> {
        // no project details, impossible to load a readme, so returning empty
        module.registryDetails ?: return Optional.empty()

        val token = module.createdBy?.oAuth2User?.token;

        val headers = HttpHeaders()
        if(token != null) {
            headers.add("Authorization", "Bearer $token")
        }

        val requestEntity = HttpEntity<Any>(headers)

        val response = restTemplate.exchange(
                this.registryType.readmeUrl,
                HttpMethod.GET,
                requestEntity,
                RegistryFile::class.java,
                module.registryDetails.projectId)

        if(response.statusCode == HttpStatus.OK) {
            return Optional.of(String(Base64.getDecoder().decode(response.body?.content)))
        }
        return Optional.empty()
    }

}