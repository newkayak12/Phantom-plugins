// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.messaging

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ProcessingContext
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringCacheDeps
import dev.phantom.spring.core.SpringProjectGate
import org.jetbrains.annotations.TestOnly
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

/** Kafka, RabbitMQ and JMS: which strings name a destination, on the sending and on the receiving side. */
internal enum class Broker(val display: String) { KAFKA("Kafka topic"), RABBIT("RabbitMQ queue"), JMS("JMS destination") }

internal class MessagingSite(val broker: Broker, val channel: String, val sends: Boolean, val element: SmartPsiElementPointer<PsiElement>, val label: String)

internal object MessagingModel {
  /** Listener annotation to the attributes that hold destination names. */
  private val LISTENERS = mapOf(
    "org.springframework.kafka.annotation.KafkaListener" to (Broker.KAFKA to listOf("topics", "value")),
    "org.springframework.amqp.rabbit.annotation.RabbitListener" to (Broker.RABBIT to listOf("queues", "value")),
    "org.springframework.jms.annotation.JmsListener" to (Broker.JMS to listOf("destination")),
  )
  /** Sending APIs: class, method names, and which argument is the destination (by argument count). */
  private class Sender(val broker: Broker, val owner: String, val methods: Set<String>, val channelIndex: (Int) -> Int?)
  private val SENDERS = listOf(
    Sender(Broker.KAFKA, "org.springframework.kafka.core.KafkaTemplate", setOf("send")) { 0 },
    Sender(Broker.KAFKA, "org.apache.kafka.clients.producer.ProducerRecord", setOf("ProducerRecord")) { 0 },
    Sender(Broker.JMS, "org.springframework.jms.core.JmsTemplate", setOf("send", "convertAndSend")) { if (it >= 2) 0 else null },
    // convertAndSend(routingKey, message) goes through the default exchange to the queue of that name
    Sender(Broker.RABBIT, "org.springframework.amqp.rabbit.core.RabbitTemplate", setOf("convertAndSend")) { when (it) { 2 -> 0; 3 -> 1; else -> null } },
  )
  private val CONSUMERS = listOf("org.apache.kafka.clients.consumer.Consumer", "io.confluent.parallelconsumer.ParallelConsumer")
  private val MARKERS = listOf("org.springframework.kafka.core.KafkaTemplate", "org.apache.kafka.clients.producer.ProducerRecord",
                               "org.springframework.amqp.rabbit.core.RabbitTemplate", "org.springframework.jms.core.JmsTemplate")

  /** Names cheap enough to check on every identifier before any resolve. */
  val CALL_NAMES: Set<String> = SENDERS.flatMap { it.methods }.toSet() + "subscribe"

  fun isMessagingProject(project: Project): Boolean {
    if (!SpringProjectGate.isSpringProject(project)) return false
    if (MARKERS.any { JavaLibraryUtil.hasLibraryClass(project, it) }) return true
    return ApplicationManager.getApplication().isUnitTestMode &&
           MARKERS.any { JavaPsiFacade.getInstance(project).findClass(it, GlobalSearchScope.allScope(project)) != null }
  }

  fun all(project: Project): List<MessagingSite> = CachedValuesManager.getManager(project).getCachedValue(project) {
    CachedValueProvider.Result.create(find(project), PsiModificationTracker.getInstance(project))
  }

  fun channels(project: Project): List<Pair<Broker, String>> = all(project).map { it.broker to it.channel }.distinct().sortedBy { it.second }

  private fun find(project: Project): List<MessagingSite> {
    if (!isMessagingProject(project)) return emptyList()
    val facade = JavaPsiFacade.getInstance(project)
    val libraries = GlobalSearchScope.allScope(project)
    val sources = GlobalSearchScope.projectScope(project)
    val result = ArrayList<MessagingSite>()
    for (fqn in LISTENERS.keys) {
      val annotation = facade.findClass(fqn, libraries) ?: continue
      AnnotatedElementsSearch.searchElements(annotation, sources, PsiMethod::class.java, PsiClass::class.java).forEach { owner ->
        owner.toUElement()?.let { result += listenerSites(it) }
        true
      }
    }
    for (sender in SENDERS) {
      val owner = facade.findClass(sender.owner, libraries) ?: continue
      val methods = if (sender.methods.contains(owner.name)) owner.constructors.toList() else sender.methods.flatMap { owner.findMethodsByName(it, false).toList() }
      for (method in methods) {
        for (reference in MethodReferencesSearch.search(method, sources, true).findAll()) {
          val call = reference.element.getUastParentOfType(UCallExpression::class.java) ?: continue
          senderSite(call, sender)?.let { result += it }
        }
      }
    }
    CONSUMERS.mapNotNull { facade.findClass(it, libraries) }.flatMap { it.findMethodsByName("subscribe", true).toList() }.forEach { method ->
      for (reference in MethodReferencesSearch.search(method, sources, true).findAll()) {
        val call = reference.element.getUastParentOfType(UCallExpression::class.java) ?: continue
        result += subscribeSites(call)
      }
    }
    return result
  }

  fun listenerSites(declaration: org.jetbrains.uast.UElement): List<MessagingSite> {
    val annotated = declaration as? UAnnotated ?: return emptyList()
    val anchor = (declaration as? UDeclaration)?.uastAnchor?.sourcePsi ?: return emptyList()
    val label = when (declaration) {
      is UMethod -> "${declaration.getContainingUClass()?.name}.${declaration.name}"
      is UClass -> declaration.name ?: ""
      else -> ""
    }
    return annotated.uAnnotations.flatMap { annotation ->
      val (broker, attributes) = LISTENERS[annotation.qualifiedName] ?: return@flatMap emptyList()
      attributes.flatMap { strings(annotation.findDeclaredAttributeValue(it)) }.map { ConfigPlaceholders.resolve(declaration.sourcePsi ?: anchor, it) }.distinct()
        .map { MessagingSite(broker, it, false, pointer(anchor), label) }
    }
  }

  private fun senderSite(call: UCallExpression, sender: Sender): MessagingSite? {
    val index = sender.channelIndex(call.valueArgumentCount) ?: return null
    val argument = call.getArgumentForParameter(index) ?: call.valueArguments.getOrNull(index) ?: return null
    if (argument.getExpressionType()?.canonicalText != "java.lang.String") return null
    val anchor = anchorOf(call) ?: return null
    val channel = argument.evaluateString()?.let { ConfigPlaceholders.resolve(anchor, it) } ?: return null
    return MessagingSite(sender.broker, channel, true, pointer(anchor), location(call))
  }

  private fun subscribeSites(call: UCallExpression): List<MessagingSite> {
    val anchor = anchorOf(call) ?: return emptyList()
    val argument = call.valueArguments.firstOrNull()
    val names = strings(argument).ifEmpty { localListStrings(argument, call) }.ifEmpty { helperStrings(argument) }
    return names.distinct().map { MessagingSite(Broker.KAFKA, it, false, pointer(anchor), location(call)) }
  }

  /**
   * `val topics = mutableListOf<String>(); topics.add(TOPIC); consumer.subscribe(topics)`: a local list filled in the same
   * method. Elements that are not constants (`"${'$'}TOPIC-RETRY-${'$'}n"`) are skipped.
   */
  /**
   * `subscribe(subscribedTopics())` where the helper in the project returns a list of strings: the constants it puts
   * into that list (whole values only; a template such as `"${'$'}TOPIC-RETRY-${'$'}n"` is skipped).
   */
  private fun helperStrings(argument: UExpression?): List<String> {
    val call = (argument as? org.jetbrains.uast.UQualifiedReferenceExpression)?.selector as? UCallExpression ?: argument as? UCallExpression ?: return emptyList()
    val method = call.resolve()?.takeIf { it.containingFile?.virtualFile?.let { file -> com.intellij.openapi.roots.ProjectFileIndex.getInstance(it.project).isInSourceContent(file) } == true }
                 ?: return emptyList()
    val returnType = method.returnType as? com.intellij.psi.PsiClassType ?: return emptyList()
    if (returnType.parameters.singleOrNull()?.canonicalText != "java.lang.String") return emptyList()
    val body = method.toUElementOfType<UMethod>()?.uastBody ?: return emptyList()
    val result = ArrayList<String>()
    body.accept(object : org.jetbrains.uast.visitor.AbstractUastVisitor() {
      override fun visitExpression(node: UExpression): Boolean {
        val parent = node.uastParent
        if (parent is org.jetbrains.uast.UPolyadicExpression || parent is org.jetbrains.uast.UQualifiedReferenceExpression && parent.receiver == node) return false
        if (node is org.jetbrains.uast.ULiteralExpression || node is org.jetbrains.uast.UReferenceExpression || node is org.jetbrains.uast.UPolyadicExpression) {
          if (node.getExpressionType()?.canonicalText == "java.lang.String") node.evaluateString()?.let { result += it }
        }
        return false
      }
    })
    return result
  }

  private fun localListStrings(argument: UExpression?, call: UCallExpression): List<String> {
    val variable = (argument as? org.jetbrains.uast.UResolvable)?.resolve()?.toUElement() as? org.jetbrains.uast.ULocalVariable ?: return emptyList()
    val method = call.getUastParentOfTypeOrSelf<UMethod>() ?: return emptyList()
    val result = ArrayList(strings(variable.uastInitializer))
    method.accept(object : org.jetbrains.uast.visitor.AbstractUastVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (node.methodName in setOf("add", "addAll", "plusAssign")) {
          val receiver = (node.receiver as? org.jetbrains.uast.UResolvable)?.resolve()?.toUElement()
          if (receiver?.sourcePsi == variable.sourcePsi) node.valueArguments.forEach { result += strings(it) }
        }
        return false
      }
    })
    return result
  }

  /** A string, a constant, or a literal collection or array of them: `listOf(A, "b")`, `{"a", "b"}`, `List.of(...)`. */
  private fun strings(expression: UExpression?): List<String> {
    expression ?: return emptyList()
    expression.evaluateString()?.let { return listOf(it) }
    val call = (expression as? org.jetbrains.uast.UQualifiedReferenceExpression)?.selector as? UCallExpression
               ?: expression as? UCallExpression ?: return emptyList()
    val isCollection = call.kind == UastCallKind.NESTED_ARRAY_INITIALIZER || call.kind == UastCallKind.NEW_ARRAY_WITH_INITIALIZER ||
                       call.methodName in setOf("listOf", "setOf", "arrayOf", "mutableListOf", "of", "asList", "singletonList", "singleton")
    return if (isCollection) call.valueArguments.flatMap(::strings) else emptyList()
  }

  fun anchorOf(call: UCallExpression): PsiElement? =
    call.methodIdentifier?.sourcePsi ?: call.classReference?.sourcePsi ?: call.sourcePsi

  private fun location(call: UCallExpression): String {
    val method = call.getUastParentOfTypeOrSelf<UMethod>()
    return listOfNotNull(method?.getContainingUClass()?.name, method?.name).joinToString(".")
  }

  private inline fun <reified T : org.jetbrains.uast.UElement> org.jetbrains.uast.UElement.getUastParentOfTypeOrSelf(): T? {
    var current: org.jetbrains.uast.UElement? = this
    while (current != null && current !is T) current = current.uastParent
    return current as T?
  }

  private fun UMethod.getContainingUClass(): UClass? = uastParent as? UClass

  private fun pointer(element: PsiElement) = SmartPointerManager.createPointer(element)

  /** The sites at [element], when it is the anchor of a sending call, a subscription or a listener. */
  fun sitesAt(element: PsiElement): List<MessagingSite> {
    val project = element.project
    return all(project).filter { site -> site.element.element.let { it == element || it != null && it == element.parent } }
  }
}

/** Gutter icons that connect senders and listeners of the same topic, queue or destination. */
class MessagingLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBundle.message("messaging.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ExtAnnotation

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    val text = element.text
    val isDeclarationName = (element.parent as? PsiNameIdentifierOwner)?.nameIdentifier == element
    if (!isDeclarationName && text !in MessagingModel.CALL_NAMES) return null
    if (!MessagingModel.isMessagingProject(element.project)) return null
    if (isDeclarationName) {
      val declaration = getUParentForIdentifier(element) ?: return null
      if (declaration !is UMethod && declaration !is UClass) return null
      if (MessagingModel.listenerSites(declaration).isEmpty()) return null
    }
    else {
      val call = element.getUastParentOfType(UCallExpression::class.java) ?: return null
      val anchor = MessagingModel.anchorOf(call) ?: return null
      if (anchor != element && anchor != element.parent) return null
    }
    val sites = MessagingModel.sitesAt(element).ifEmpty { return null }
    val sends = sites.first().sends
    val channels = sites.map { it.channel }.distinct()
    return NavigationGutterIconBuilder.create(if (sends) AllIcons.Gutter.ImplementedMethod else AllIcons.Gutter.ImplementingMethod)
      .setTargets(NotNullLazyValue.lazy {
        val all = MessagingModel.all(element.project)
        sites.flatMap { site -> all.filter { it.sends != site.sends && it.broker == site.broker && it.channel == site.channel } }
          .mapNotNull { it.element.element }.distinct()
      })
      .setTooltipText(PhantomSpringBundle.message(if (sends) "messaging.gutter.receivers" else "messaging.gutter.senders", channels.joinToString(", ")))
      .setPopupTitle(PhantomSpringBundle.message(if (sends) "messaging.gutter.receivers" else "messaging.gutter.senders", channels.joinToString(", ")))
      .setEmptyPopupText(PhantomSpringBundle.message(if (sends) "messaging.gutter.no.receivers" else "messaging.gutter.no.senders"))
      .createLineMarkerInfo(element)
  }
}

/** Topic, queue and destination names already used in the project, in the strings that name one. */
class MessagingCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val position = parameters.position
        if (!MessagingModel.isMessagingProject(position.project)) return
        val host = position.getUastParentOfType(UInjectionHost::class.java) ?: return
        if (!namesChannel(host)) return
        val psi = host.psiLanguageInjectionHost
        val start = psi.textRange.startOffset + com.intellij.psi.ElementManipulators.getValueTextRange(psi).startOffset
        if (parameters.offset < start) return
        val typed = position.containingFile.text.substring(start, parameters.offset)
        val names = result.withPrefixMatcher(typed)
        for ((broker, channel) in MessagingModel.channels(position.project)) {
          if (channel == typed) continue // the string being typed is itself a site
          names.addElement(LookupElementBuilder.create(channel).withIcon(AllIcons.Nodes.Constant).withTypeText(broker.display, true))
        }
        result.stopHere()
      }
    })
  }

  private fun namesChannel(host: UInjectionHost): Boolean {
    var parent = host.uastParent
    if (parent is UCallExpression && (parent.kind == UastCallKind.NESTED_ARRAY_INITIALIZER || parent.methodName in setOf("listOf", "setOf", "arrayOf", "of"))) parent = parent.uastParent
    if (parent is org.jetbrains.uast.UNamedExpression) parent = parent.uastParent
    return when (parent) {
      is org.jetbrains.uast.UAnnotation -> parent.qualifiedName?.let { it.endsWith(".KafkaListener") || it.endsWith(".RabbitListener") || it.endsWith(".JmsListener") } == true
      is UCallExpression -> parent.valueArguments.firstOrNull() == host &&
                            (parent.methodName in MessagingModel.CALL_NAMES || parent.classReference?.resolvedName == "ProducerRecord")
      else -> false
    }
  }
}

/**
 * `${kafka.topic.order}` in a listener or sender: the value from the project's `application*.properties` or
 * `application*.yml` (all profiles; the first file that has the key wins), or the `:default`. Left as written when
 * the key is in no file, so the same placeholder on both sides still pairs up.
 */
internal object ConfigPlaceholders {
  private val PLACEHOLDER = Regex("""\$\{([^}:]+)(?::([^}]*))?}""")

  fun resolve(context: PsiElement, value: String): String {
    if (!value.contains("\${")) return value
    val properties = properties(context.project)
    var unresolved = false
    val resolved = PLACEHOLDER.replace(value) { match ->
      properties[match.groupValues[1].trim()] ?: match.groups[2]?.value ?: run { unresolved = true; match.value }
    }
    return if (unresolved) value else resolved
  }

  // the files are read from disk, so their contents on disk, the file set and the roots are all the value depends on
  fun properties(project: Project): Map<String, String> = CachedValuesManager.getManager(project).getCachedValue(project) {
    readCount.incrementAndGet()
    val files = files(project)
    CachedValueProvider.Result.create(read(files), *SpringCacheDeps.config(project, files))
  }

  /** How often the files were read, for tests that check the cache survives unrelated edits. */
  @TestOnly
  val readCount: AtomicInteger = AtomicInteger()

  private fun files(project: Project): List<VirtualFile> {
    val scope = GlobalSearchScope.projectScope(project)
    val extensions = listOf("properties", "yml", "yaml")
    val base = listOf("application", "bootstrap").flatMap { name -> extensions.map { "$name.$it" } }
      .flatMap { com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(it, scope) }
    // profile files (application-local.yml) sit next to the base file
    val profiles = base.mapNotNull { it.parent }.distinct().flatMap { dir ->
      dir.children.filter { it.name.startsWith("application-") && it.extension in extensions }.sortedBy { it.name }
    }
    return base + profiles
  }

  private fun read(files: List<VirtualFile>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (file in files) {
      val text = runCatching { com.intellij.openapi.vfs.VfsUtilCore.loadText(file) }.getOrNull() ?: continue
      val entries = if (file.extension == "properties") readProperties(text) else readYaml(text)
      for ((key, value) in entries) result.putIfAbsent(key, value)
    }
    return result
  }

  private fun readProperties(text: String): Map<String, String> =
    java.util.Properties().apply { runCatching { load(java.io.StringReader(text)) } }.entries.associate { it.key.toString() to it.value.toString() }

  /** Nested mappings flattened to dotted keys; lists and multi-line values are skipped. */
  fun readYaml(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    val path = ArrayList<Pair<Int, String>>()
    for (raw in text.lines()) {
      val line = raw.substringBefore(" #").trimEnd()
      if (line.isBlank() || line.trimStart().startsWith("#") || line.startsWith("---")) { if (line.startsWith("---")) path.clear(); continue }
      val indent = line.length - line.trimStart().length
      val content = line.trim()
      if (content.startsWith("- ")) continue
      val colon = Regex("""^([^:'"]+|'[^']*'|"[^"]*"):(\s+|$)""").find(content) ?: continue
      val key = colon.groupValues[1].trim().trim('"', '\'')
      while (path.isNotEmpty() && path.last().first >= indent) path.removeAt(path.lastIndex)
      val value = content.substring(colon.range.last + 1).trim()
      val full = (path.map { it.second } + key).joinToString(".")
      if (value.isEmpty()) path += indent to key
      else if (!value.startsWith("|") && !value.startsWith(">")) result[full] = value.trim('"', '\'')
    }
    return result
  }
}
