// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.*
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.phantom.spring.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class SpringBeansToolWindowFactory : ToolWindowFactory, DumbAware {
  override suspend fun isApplicableAsync(project: Project): Boolean = readAction { SpringProjectGate.isSpringProject(project) }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = SpringBeansPanel(project)
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
  }
}

@Service(Service.Level.PROJECT)
internal class SpringBeansScope(val cs: CoroutineScope)

/** Tree nodes. Beans expand into what they depend on and what depends on them, computed when expanded. */
sealed interface BeanNode {
  class Module(val name: String, val count: Int) : BeanNode
  class Group(val kind: BeanKind, val count: Int) : BeanNode
  class Bean(val info: BeanInfo) : BeanNode
  /** Pointers, so the tree does not hold PSI of files that were reparsed or closed since it was built. */
  class Section(val title: String, val bean: SmartPsiElementPointer<PsiModifierListOwner>, val dependents: Boolean) : BeanNode
  /** Labels are made in the read action that found the dependency, so painting never touches PSI. */
  class Dependency(val dependency: BeanDependency, val text: String, val candidates: List<String>) : BeanNode
  class Dependent(val element: SmartPsiElementPointer<PsiModifierListOwner>, val label: String, val isMethod: Boolean) : BeanNode
  class Message(val text: String) : BeanNode
  data object Loading : BeanNode
}

/**
 * Spring Beans: every bean of the project by module and stereotype, with each bean's dependencies (the beans that fill
 * its constructor, field and setter injection points) and dependents. A tree is the bean graph one hop at a time, which
 * stays readable in projects with hundreds of beans where a drawn graph would not.
 */
class SpringBeansPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
  private val root = DefaultMutableTreeNode()
  private val model = DefaultTreeModel(root)
  val tree: Tree = Tree(model)
  private var job: Job? = null
  // sections whose children are being computed: expanding one again before they arrive must not search twice
  private val expanding = HashSet<DefaultMutableTreeNode>()

  @get:TestOnly
  var loaded: Boolean = false
    private set

  init {
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.cellRenderer = Renderer()
    tree.emptyText.text = PhantomSpringBundle.message("beans.loading")
    TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
    tree.addTreeWillExpandListener(object : TreeWillExpandListener {
      override fun treeWillExpand(event: TreeExpansionEvent) = expand(event.path.lastPathComponent as DefaultMutableTreeNode)
      override fun treeWillCollapse(event: TreeExpansionEvent) {}
    })
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean = navigate()
    }.installOn(tree)
    tree.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_ENTER && navigate()) e.consume()
      }
    })
    val actions = DefaultActionGroup(object : DumbAwareAction(PhantomSpringBundle.message("beans.refresh"), null, AllIcons.Actions.Refresh) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun actionPerformed(e: AnActionEvent) = reload()
    })
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomSpringBeans", actions, true)
    toolbar.targetComponent = tree
    setToolbar(toolbar.component)
    setContent(ScrollPaneFactory.createScrollPane(tree, true))
    reload()
  }

  fun reload() {
    job?.cancel()
    loaded = false
    tree.emptyText.text = PhantomSpringBundle.message("beans.loading")
    job = project.service<SpringBeansScope>().cs.launch {
      val beans = smartReadAction(project) { SpringBeanCatalog.collect(project) }
      withContext(Dispatchers.EDT) { show(beans) }
    }
  }

  private fun show(beans: Map<String, List<BeanInfo>>) {
    root.removeAllChildren()
    for ((module, list) in beans) {
      val moduleNode = DefaultMutableTreeNode(BeanNode.Module(module, list.size + list.sumOf { it.beanMethods.size }))
      for ((kind, ofKind) in list.groupBy { it.kind }.toSortedMap()) {
        val group = DefaultMutableTreeNode(BeanNode.Group(kind, ofKind.size + ofKind.sumOf { it.beanMethods.size }))
        for (bean in ofKind) {
          val node = beanNode(bean)
          bean.beanMethods.forEach { node.add(beanNode(it)) }
          group.add(node)
        }
        moduleNode.add(group)
      }
      root.add(moduleNode)
    }
    tree.emptyText.text = PhantomSpringBundle.message("beans.empty")
    model.reload()
    // one module (the usual Spring Boot app) opens straight to its groups
    if (root.childCount == 1) tree.expandPath(javax.swing.tree.TreePath((root.firstChild as DefaultMutableTreeNode).path))
    loaded = true
  }

  private fun beanNode(info: BeanInfo): DefaultMutableTreeNode = DefaultMutableTreeNode(BeanNode.Bean(info)).apply {
    val pointer = SmartPointerManager.createPointer(info.element)
    add(placeholderSection(PhantomSpringBundle.message("beans.dependencies"), pointer, false))
    add(placeholderSection(PhantomSpringBundle.message("beans.dependents"), pointer, true))
  }

  private fun placeholderSection(title: String, bean: SmartPsiElementPointer<PsiModifierListOwner>, dependents: Boolean) =
    DefaultMutableTreeNode(BeanNode.Section(title, bean, dependents)).apply { add(DefaultMutableTreeNode(BeanNode.Loading, false)) }

  private fun expand(node: DefaultMutableTreeNode) {
    val section = node.userObject as? BeanNode.Section ?: return
    if ((node.firstOrNull()?.userObject) != BeanNode.Loading || !expanding.add(node)) return
    val job = project.service<SpringBeansScope>().cs.launch {
      val children: List<BeanNode> = smartReadAction(project) {
        val bean = section.bean.element?.takeIf { it.isValid }
                   ?: return@smartReadAction listOf(BeanNode.Message(PhantomSpringBundle.message("beans.outdated")))
        if (section.dependents) {
          SpringBeanCatalog.dependents(bean).map { BeanNode.Dependent(SmartPointerManager.createPointer(it), label(it), it is PsiMethod) }
        }
        else {
          SpringBeanCatalog.dependencies(bean).map { d ->
            BeanNode.Dependency(d, (d.point.name ?: "?") + ": " + d.point.type.presentableText, d.candidates.map(::label))
          }
        }.ifEmpty { listOf(BeanNode.Message(PhantomSpringBundle.message(if (section.dependents) "beans.no.dependents" else "beans.no.dependencies"))) }
      }
      withContext(Dispatchers.EDT) {
        node.removeAllChildren()
        children.forEach { node.add(DefaultMutableTreeNode(it, false)) }
        model.nodeStructureChanged(node)
      }
    }
    // also when cancelled, so a later expansion tries again
    job.invokeOnCompletion { ApplicationManager.getApplication().invokeLater { expanding.remove(node) } }
  }

  private fun navigate(): Boolean {
    val payload = TreeUtil.getLastUserObject(tree.selectionPath)
    val navigatable = com.intellij.openapi.application.ReadAction.compute<com.intellij.pom.Navigatable?, RuntimeException> {
      val target: PsiElement = when (payload) {
        is BeanNode.Bean -> payload.info.element
        is BeanNode.Dependent -> payload.element.element ?: return@compute null
        // a dependency filled by exactly one bean goes to that bean; otherwise to the injection point
        is BeanNode.Dependency -> payload.dependency.candidates.singleOrNull() ?: payload.dependency.point.navigationTarget
        else -> return@compute null
      }
      val navigation = target.navigationElement.takeIf { it.isValid } ?: return@compute null
      val file = navigation.containingFile?.virtualFile ?: return@compute null
      PsiNavigationSupport.getInstance().createNavigatable(project, file, navigation.textOffset)
    } ?: return false
    navigatable.navigate(true)
    return true
  }

  private fun DefaultMutableTreeNode.firstOrNull(): DefaultMutableTreeNode? = if (childCount > 0) getChildAt(0) as DefaultMutableTreeNode else null

  private class Renderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
      when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
        is BeanNode.Module -> { icon = AllIcons.Nodes.Module; append(node.name); append("  ${node.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        is BeanNode.Group -> { icon = AllIcons.Nodes.Folder; append(node.kind.title); append("  ${node.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        is BeanNode.Bean -> {
          icon = when {
            node.info.element is PsiMethod -> AllIcons.Nodes.Method
            node.info.kind == BeanKind.REPOSITORY && node.info.isInterface -> AllIcons.Nodes.Interface
            else -> PhantomSpringIcons.Bean
          }
          append(node.info.name)
          append("  " + node.info.typeText.substringAfterLast('.'), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is BeanNode.Section -> { icon = if (node.dependents) AllIcons.Hierarchy.Supertypes else AllIcons.Hierarchy.Subtypes; append(node.title) }
        is BeanNode.Dependency -> {
          icon = AllIcons.Nodes.Parameter
          append(node.text)
          if (node.candidates.isEmpty()) append("  " + PhantomSpringBundle.message("beans.no.candidate"), SimpleTextAttributes.ERROR_ATTRIBUTES)
          else append("  → " + node.candidates.joinToString(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is BeanNode.Dependent -> { icon = if (node.isMethod) AllIcons.Nodes.Method else PhantomSpringIcons.Bean; append(node.label) }
        is BeanNode.Message -> append(node.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        BeanNode.Loading -> append(PhantomSpringBundle.message("beans.loading"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        else -> {}
      }
    }
  }
}

/** `OrderService` for a class, `PaymentConfig.restClient()` for a `@Bean` method. */
private fun label(element: PsiModifierListOwner): String = when (element) {
  is PsiMethod -> (element.containingClass?.name?.let { "$it." } ?: "") + element.name + "()"
  is PsiClass -> element.name.orEmpty()
  else -> (element as? PsiNamedElement)?.name.orEmpty()
}
