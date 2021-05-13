package `in`.specmatic.conversions

import `in`.specmatic.core.HttpRequest
import `in`.specmatic.core.HttpResponse
import `in`.specmatic.core.parseGherkinStringToFeature
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.value.Value
import `in`.specmatic.stub.HttpStub
import `in`.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.io.File
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue


internal class OpenApiKtTest {
    companion object {
        const val OPENAPI_FILE = "openApiTest.yaml"

        val openAPISpec = """
Feature: Hello world

Background:
  Given openapi openApiTest.yaml            

Scenario: zero should return not found
  When GET /hello/0
  Then status 404
  And response-header Content-Type application/json
        """.trimIndent()

        val specThatIsNotAsPerOpenApiSpec = """
Feature: Hello world

Background:
  Given openapi openApiTest.yaml            

Scenario: sending string instead of number should return not found
  When GET /hello/test
  Then status 404
  And response-header Content-Type application/json
        """.trimIndent()

        val openAPISpec2 = """
Feature: Hello world

Background:
  Given openapi openApiTest.yaml            

Scenario: zero should return forbidden
  When GET /hello/0
  Then status 403
  And response-header Content-Type application/json
        """.trimIndent()
    }

    @BeforeEach
    fun `setup`() {
        val openAPI = """
openapi: 3.0.0
info:
  title: Sample API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://api.example.com/v1
    description: Optional server description, e.g. Main (production) server
  - url: http://staging-api.example.com
    description: Optional server description, e.g. Internal staging server for testing
paths:
  /hello/{id}:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - in: path
          name: id
          schema:
            type: integer
          required: true
          description: Numeric ID
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: string
        '404':
          description: Not Found
          content:
            application/json:
              schema:
                error: 'Not Found'
    """.trim()

        val openApiFile = File(OPENAPI_FILE)
        openApiFile.createNewFile()
        openApiFile.writeText(openAPI)

    }

    @AfterEach
    fun `teardown`() {
        File(OPENAPI_FILE).delete()
    }

    @Ignore
    fun `should create stub from OpenAPI spec`() {
        val feature = toFeatures(File(OPENAPI_FILE).absolutePath)

        val response = HttpStub(feature).use { mock ->
            val restTemplate = RestTemplate()
            restTemplate.exchange(URI.create("http://localhost:9000/hello/1"), HttpMethod.GET, null, String::class.java)
        }

        assertThat(response.statusCodeValue).isEqualTo(200)
    }

    @Ignore
    fun `should create test from OpenAPI spec`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = toFeatures(File(OPENAPI_FILE).absolutePath)

        val results = feature[0].executeTests(
                object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        flags["executed"] = true
                        assertThat(request.path).matches("""\/hello\/[0-9]+""")
                        val headers: HashMap<String, String> = object : HashMap<String, String>() {
                            init {
                                put("Content-Type", "application/json")
                            }
                        }
                        return HttpResponse(200, "", headers)
                    }

                    override fun setServerState(serverState: Map<String, Value>) {
                    }
                }
        )

        assertThat(flags["executed"]).isTrue
        assertTrue(results.success(), results.report())
    }

    @Ignore
    fun `should report error on test from OpenAPI spec with scenario name`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = toFeatures(File(OPENAPI_FILE).absolutePath)

        val results = feature[0].executeTests(
                object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        flags["executed"] = true
                        assertThat(request.path).matches("""\/hello\/[0-9]+""")
                        val headers: HashMap<String, String> = object : HashMap<String, String>() {
                            init {
                                put("Content-Type", "application/json")
                            }
                        }
                        return HttpResponse(400, "Error", headers)
                    }

                    override fun setServerState(serverState: Map<String, Value>) {
                    }
                }
        )

        assertThat(flags["executed"]).isTrue
        assertFalse(results.success())
        assertThat(results.report()).isEqualTo("""
            In scenario "Request: hello world Response: Says hello"
            >> RESPONSE.STATUS

            Expected status: 200, actual: 400
        """.trimIndent())
    }

    @Test
    fun `should create stub from gherkin that includes OpenAPI spec`() {
        val feature = parseGherkinStringToFeature(openAPISpec)

        val response = HttpStub(feature).use { mock ->
            val restTemplate = RestTemplate()
            restTemplate.exchange(URI.create("http://localhost:9000/hello/1"), HttpMethod.GET, null, String::class.java)
        }

        assertThat(response.statusCodeValue).isEqualTo(200)

        HttpStub(feature).use { mock ->
            val restTemplate = RestTemplate()
            try {
                restTemplate.exchange(URI.create("http://localhost:9000/hello/0"), HttpMethod.GET, null, String::class.java)
            } catch (e: HttpClientErrorException) {
                assertThat(e.statusCode).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND)
            }
        }
    }

    @Test
    fun `should create test from gherkin that includes OpenAPI spec`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(openAPISpec)

        val results = feature.executeTests(
                object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        flags["executed"] = true
                        assertThat(request.path).matches("""\/hello\/[0-9]+""")
                        val headers: HashMap<String, String> = object : HashMap<String, String>() {
                            init {
                                put("Content-Type", "application/json")
                            }
                        }
                        val id = request.path!!.split('/')[2].toInt()
                        val status = when (id) {
                            0 -> 404
                            else -> 200
                        }
                        return HttpResponse(status, "", headers)
                    }

                    override fun setServerState(serverState: Map<String, Value>) {
                    }
                }
        )

        assertThat(flags["executed"]).isTrue
        assertTrue(results.success(), results.report())
    }

    @Test
    fun `should report error in test with both OpenAPI and Gherkin scenario names`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(openAPISpec)

        val results = feature.executeTests(
                object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        flags["executed"] = true
                        assertThat(request.path).matches("""\/hello\/[0-9]+""")
                        val headers: HashMap<String, String> = object : HashMap<String, String>() {
                            init {
                                put("Content-Type", "application/json")
                            }
                        }
                        val id = request.path!!.split('/')[2].toInt()
                        val status = when (id) {
                            0 -> 403
                            else -> 202
                        }
                        return HttpResponse(status, "", headers)
                    }

                    override fun setServerState(serverState: Map<String, Value>) {
                    }
                }
        )

        assertThat(flags["executed"]).isTrue
        assertFalse(results.success())
        assertThat(results.report()).isEqualTo("""
            In scenario "zero should return not found"
            >> RESPONSE.STATUS

            Expected status: 404, actual: 403

            In scenario "Request: hello world Response: Says hello"
            >> RESPONSE.STATUS

            Expected status: 200, actual: 202
        """.trimIndent())
    }

    @Test
    fun `should throw error when request in Gherkin scenario does not match included OpenAPI spec`() {
        val (errorMessage, _, _, _) = assertFailsWith<ContractException> {
            parseGherkinStringToFeature(specThatIsNotAsPerOpenApiSpec)
        }
        assertThat(errorMessage).isEqualTo("""Scenario: "sending string instead of number should return not found" request is not as per OpenApi spec""")
    }

    @Test
    fun `should throw error when response code in Gherkin scenario does not match included OpenAPI spec`() {
        val (errorMessage, _, _, _) = assertFailsWith<ContractException> {
            parseGherkinStringToFeature(openAPISpec2)
        }
        assertThat(errorMessage).isEqualTo("""Scenario: "zero should return forbidden" response is not as per OpenApi spec""")
    }
}