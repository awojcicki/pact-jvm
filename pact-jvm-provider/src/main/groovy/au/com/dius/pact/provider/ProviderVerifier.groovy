package au.com.dius.pact.provider

import au.com.dius.pact.model.PactReader
import au.com.dius.pact.model.Response
import au.com.dius.pact.model.v3.messaging.Message
import au.com.dius.pact.model.v3.messaging.MessagePact
import au.com.dius.pact.provider.reporters.AnsiConsoleReporter
import groovy.util.logging.Slf4j
import org.apache.commons.lang3.StringUtils
import org.reflections.Reflections
import org.reflections.scanners.MethodAnnotationsScanner
import org.reflections.util.ConfigurationBuilder
import org.reflections.util.FilterBuilder
import scala.Function1

import java.lang.reflect.Method

/**
 * Verifies the providers against the defined consumers in the context of a build plugin
 */
@Slf4j
class ProviderVerifier {

  static final String PACT_FILTER_CONSUMERS = 'pact.filter.consumers'
  static final String PACT_FILTER_DESCRIPTION = 'pact.filter.description'
  static final String PACT_FILTER_PROVIDERSTATE = 'pact.filter.providerState'
  static final String PACT_SHOW_STACKTRACE = 'pact.showStacktrace'

  def projectHasProperty = { }
  def projectGetProperty = { }
  def pactLoadFailureMessage
  def isBuildSpecificTask = { }
  def executeBuildSpecificTask = { }
  def projectClasspath = { }
  def reporters = [ new AnsiConsoleReporter() ]

  Map verifyProvider(ProviderInfo provider) {
    Map failures = [:]

    initialiseReporters(provider)

    def consumers = provider.consumers.findAll(this.&filterConsumers)
    if (consumers.empty) {
      reporters.each { it.warnProviderHasNoConsumers(provider) }
    }
    consumers.each(this.&runVerificationForConsumer.curry(failures, provider))

    failures
  }

  void initialiseReporters(ProviderInfo provider) {
    reporters.each { it.initialise(provider) }
  }

  void runVerificationForConsumer(Map failures, ProviderInfo provider, ConsumerInfo consumer) {
    reportVerificationForConsumer(consumer, provider)
    def pact = loadPactFileForConsumer(consumer)
    forEachInteraction(pact, this.&verifyInteraction.curry(provider, consumer, pact, failures))
  }

  void reportVerificationForConsumer(ConsumerInfo consumer, ProviderInfo provider) {
    reporters.each { it.reportVerificationForConsumer(consumer, provider) }
  }

  List interactions(def pact) {
    if (pact instanceof MessagePact) {
      pact.messages.findAll(this.&filterInteractions)
    } else {
      pact.interactions.findAll(this.&filterInteractions)
    }
  }

  void forEachInteraction(def pact, Closure verifyInteraction) {
    List interactions = interactions(pact)
    if (interactions.empty) {
      reporters.each { it.warnPactFileHasNoInteractions(pact) }
    } else {
      interactions.each(verifyInteraction)
    }
  }

  @SuppressWarnings('ThrowRuntimeException')
  def loadPactFileForConsumer(ConsumerInfo consumer) {
    if (consumer.pactFile instanceof URL) {
      reporters.each { it.verifyConsumerFromUrl(consumer) }
      def options = [:]
      if (consumer.pactFileAuthentication) {
        options.authentication = consumer.pactFileAuthentication
      }
      PactReader.loadPact(options, consumer.pactFile)
    } else if (consumer.pactFile instanceof File || ProviderUtils.pactFileExists(consumer.pactFile)) {
      reporters.each { it.verifyConsumerFromFile(consumer) }
      PactReader.loadPact(consumer.pactFile)
    } else {
      String message
      if (pactLoadFailureMessage instanceof Closure) {
        message = pactLoadFailureMessage.call(consumer) as String
      } else if (pactLoadFailureMessage instanceof Function1) {
        message = pactLoadFailureMessage.apply(consumer) as String
      } else {
        message = pactLoadFailureMessage as String
      }
      reporters.each { it.pactLoadFailureForConsumer(consumer, message) }
      throw new RuntimeException(message)
    }
  }

  boolean filterConsumers(def consumer) {
    !callProjectHasProperty(PACT_FILTER_CONSUMERS) ||
      consumer.name in callProjectGetProperty(PACT_FILTER_CONSUMERS).split(',')*.trim()
  }

  boolean filterInteractions(def interaction) {
    if (callProjectHasProperty(PACT_FILTER_DESCRIPTION) && callProjectHasProperty(PACT_FILTER_PROVIDERSTATE)) {
      matchDescription(interaction) && matchState(interaction)
    } else if (callProjectHasProperty(PACT_FILTER_DESCRIPTION)) {
      matchDescription(interaction)
    } else if (callProjectHasProperty(PACT_FILTER_PROVIDERSTATE)) {
      matchState(interaction)
    } else {
      true
    }
  }

  private boolean matchState(interaction) {
    if (interaction.providerState) {
      interaction.providerState ==~ callProjectGetProperty(PACT_FILTER_PROVIDERSTATE)
    } else {
      callProjectGetProperty(PACT_FILTER_PROVIDERSTATE).empty
    }
  }

  private boolean matchDescription(interaction) {
    interaction.description ==~ callProjectGetProperty(PACT_FILTER_DESCRIPTION)
  }

  void verifyInteraction(ProviderInfo provider, ConsumerInfo consumer, def pact, Map failures, def interaction) {
    def interactionMessage = "Verifying a pact between ${consumer.name} and ${provider.name}" +
      " - ${interaction.description}"

    def stateChangeOk = true
    if (interaction.providerState) {
      stateChangeOk = stateChange(interaction.providerState, provider, consumer)
      log.debug "State Change: \"${interaction.providerState}\" -> ${stateChangeOk}"
      if (stateChangeOk != true) {
        failures[interactionMessage] = stateChangeOk
        stateChangeOk = false
      } else {
        interactionMessage += " Given ${interaction.providerState}"
      }
    }

    if (stateChangeOk) {
      reportInteractionDescription(interaction)

      if (ProviderUtils.verificationType(provider, consumer) == PactVerification.REQUST_RESPONSE) {
        log.debug('Verifying via request/response')
        verifyResponseFromProvider(provider, interaction, interactionMessage, failures)
      } else {
        log.debug('Verifying via annotated test method')
        verifyResponseByInvokingProviderMethods(pact, provider, consumer, interaction, interactionMessage, failures)
      }

      if (provider.stateChangeTeardown) {
        stateChange(interaction.providerState, provider, consumer, false)
      }
    }
  }

  void reportInteractionDescription(interaction) {
    reporters.each { it.interactionDescription(interaction) }
  }

  def stateChange(String state, ProviderInfo provider, ConsumerInfo consumer, boolean isSetup = true) {
    reportStateForInteraction(state, provider, consumer, isSetup)
    try {
      def stateChangeHandler = consumer.stateChange
      def stateChangeUsesBody = consumer.stateChangeUsesBody
      if (stateChangeHandler == null) {
        stateChangeHandler = provider.stateChangeUrl
        stateChangeUsesBody = provider.stateChangeUsesBody
      }
      if (stateChangeHandler == null || (stateChangeHandler instanceof String
        && StringUtils.isBlank(stateChangeHandler))) {
        reporters.each { it.warnStateChangeIgnored(state, provider, consumer) }
        return true
      } else if (stateChangeHandler instanceof Closure) {
        def result
        if (provider.stateChangeTeardown) {
          result = stateChangeHandler.call(state, isSetup ? 'setup' : 'teardown')
        } else {
          result = stateChangeHandler.call(state)
        }
        log.debug "Invoked state change closure -> ${result}"
        if (!(result instanceof URL)) {
          return result
        }
        stateChangeHandler = result
      } else if (isBuildSpecificTask(stateChangeHandler)) {
        log.debug "Invokeing build specific task ${stateChangeHandler}"
        executeBuildSpecificTask(stateChangeHandler, state)
        return true
      }
      return executeHttpStateChangeRequest(stateChangeHandler, stateChangeUsesBody, state, provider, isSetup)
    } catch (e) {
      reporters.each {
        it.stateChangeRequestFailedWithException(state, provider, consumer, isSetup, e,
          callProjectHasProperty(PACT_SHOW_STACKTRACE))
      }
      return e
    }
  }

  void reportStateForInteraction(String state, ProviderInfo provider, ConsumerInfo consumer, boolean isSetup) {
    reporters.each { it.stateForInteraction(state, provider, consumer, isSetup) }
  }

  private executeHttpStateChangeRequest(stateChangeHandler, useBody, String state, ProviderInfo provider,
                                        boolean isSetup) {
    try {
      def url = stateChangeHandler instanceof URI ? stateChangeHandler
        : new URI(stateChangeHandler.toString())
      ProviderClient client = new ProviderClient(provider: provider)
      def response = client.makeStateChangeRequest(url, state, useBody, isSetup, provider.stateChangeTeardown)
      log.debug "Invoked state change $url -> ${response?.statusLine}"
      if (response) {
        try {
          if (response.statusLine.statusCode >= 400) {
            reporters.each { it.stateChangeRequestFailed(state, provider, isSetup, response.statusLine.toString()) }
            return 'State Change Request Failed - ' + response.statusLine.toString()
          }
        } finally {
          response.close()
        }
      }
    } catch (URISyntaxException ex) {
      reporters.each { it.warnStateChangeIgnoredDueToInvalidUrl(state, provider, isSetup, stateChangeHandler) }
    }
    true
  }

  void verifyResponseFromProvider(ProviderInfo provider, def interaction, String interactionMessage, Map failures) {
    try {
      ProviderClient client = new ProviderClient(request: interaction.request, provider: provider)

      def expectedResponse = interaction.response
      def actualResponse = client.makeRequest()

      verifyRequestResponsePact(expectedResponse, actualResponse, interactionMessage, failures)
    } catch (e) {
      failures[interactionMessage] = e
      reporters.each {
        it.requestFailed(provider, interaction, interactionMessage, e, callProjectHasProperty(PACT_SHOW_STACKTRACE))
      }
    }
  }

  void verifyRequestResponsePact(Response expectedResponse, Map actualResponse, String interactionMessage,
                                 Map failures) {
    def comparison = ResponseComparison.compareResponse(expectedResponse, actualResponse,
      actualResponse.statusCode, actualResponse.headers, actualResponse.data)

    reporters.each { it.returnsAResponseWhich() }

    def s = ' returns a response which'
    displayStatusResult(failures, expectedResponse.status, comparison.method, interactionMessage + s)
    displayHeadersResult(failures, expectedResponse.headers, comparison.headers, interactionMessage + s)
    displayBodyResult(failures, comparison.body, interactionMessage + s)
  }

  void displayStatusResult(Map failures, int status, def comparison, String comparisonDescription) {
    if (comparison == true) {
      reporters.each { it.statusComparisonOk(status) }
    } else {
      reporters.each { it.statusComparisonFailed(status, comparison) }
      failures["$comparisonDescription has status code $status"] = comparison
    }
  }

  void displayHeadersResult(Map failures, def expected, Map comparison, String comparisonDescription) {
    if (!comparison.isEmpty()) {
      reporters.each { it.includesHeaders() }
      Map expectedHeaders = expected
      comparison.each { key, headerComparison ->
        def expectedHeaderValue = expectedHeaders[key]
        if (headerComparison == true) {
          reporters.each { it.headerComparisonOk(key, expectedHeaderValue) }
        } else {
          reporters.each { it.headerComparisonFailed(key, expectedHeaderValue, headerComparison) }
          failures["$comparisonDescription includes headers \"$key\" with value \"$expectedHeaderValue\""] =
            headerComparison
        }
      }
    }
  }

  void displayBodyResult(Map failures, def comparison, String comparisonDescription) {
    if (comparison.isEmpty()) {
      reporters.each { it.bodyComparisonOk() }
    } else {
      reporters.each { it.bodyComparisonFailed(comparison) }
      failures["$comparisonDescription has a matching body"] = comparison
    }
  }

  @SuppressWarnings(['ThrowRuntimeException', 'ParameterCount'])
  void verifyResponseByInvokingProviderMethods(def pact, ProviderInfo providerInfo, ConsumerInfo consumer,
                                               def interaction, String interactionMessage,
                                               Map failures) {
    try {
      def urls = projectClasspath()
      URLClassLoader loader = new URLClassLoader(urls, GroovyObject.classLoader)
      def configurationBuilder = new ConfigurationBuilder()
        .setScanners(new MethodAnnotationsScanner())
        .addClassLoader(loader)
        .addUrls(loader.URLs)

      def scan = ProviderUtils.packagesToScan(providerInfo, consumer)
      if (!scan.empty) {
        def filterBuilder = new FilterBuilder()
        scan.each { filterBuilder.include(it) }
        configurationBuilder.filterInputsBy(filterBuilder)
      }

      Reflections reflections = new Reflections(configurationBuilder)
      def methodsAnnotatedWith = reflections.getMethodsAnnotatedWith(PactVerifyProvider)
      def providerMethods = methodsAnnotatedWith.findAll { Method m ->
        log.debug("Found annotated method $m")
        def annotation = m.annotations.find { it.annotationType().toString() == PactVerifyProvider.toString() }
        log.debug("Found annotation $annotation")
        annotation?.value() == interaction.description
      }

      if (providerMethods.empty) {
        reporters.each { it.errorHasNoAnnotatedMethodsFoundForInteraction(interaction) }
        throw new RuntimeException('No annotated methods were found for interaction ' +
          "'${interaction.description}'")
      } else {
        if (pact instanceof MessagePact) {
          verifyMessagePact(providerMethods, interaction as Message, interactionMessage, failures)
        } else {
          def expectedResponse = interaction.response
          providerMethods.each {
            def actualResponse = invokeProviderMethod(it)
            verifyRequestResponsePact(expectedResponse, actualResponse, interactionMessage, failures)
          }
        }
      }
    } catch (e) {
      failures[interactionMessage] = e
      reporters.each { it.verificationFailed(interaction, e, callProjectHasProperty(PACT_SHOW_STACKTRACE)) }
    }
  }

  boolean callProjectHasProperty(String property) {
    if (projectHasProperty instanceof Function1) {
      projectHasProperty.apply(property)
    } else {
      projectHasProperty(property)
    }
  }

  String callProjectGetProperty(String property) {
    if (projectGetProperty instanceof Function1) {
      projectGetProperty.apply(property)
    } else {
      projectGetProperty(property)
    }
  }

  void verifyMessagePact(Set methods, Message message, String interactionMessage, Map failures) {
    methods.each {
      reporters.each { it.generatesAMessageWhich() }
      def actualMessage = invokeProviderMethod(it)
      def comparison = ResponseComparison.compareMessage(message, actualMessage)
      def s = ' generates a message which'
      displayBodyResult(failures, comparison, interactionMessage + s)
    }
  }

  static invokeProviderMethod(Method m) {
    m.invoke(m.declaringClass.newInstance())
  }

  void displayFailures(def failures) {
    reporters.each { it.displayFailures(failures) }
  }

  void finialiseReports() {
    reporters.each { it.finaliseReport() }
  }
}
