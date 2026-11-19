// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy;

import com.intellij.jarRepository.JarRepositoryManager;
import com.intellij.jarRepository.RemoteRepositoryDescription;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.roots.libraries.ui.OrderRoot;
import com.intellij.testFramework.UsefulTestCase;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.utils.library.RepositoryLibraryProperties;

import java.util.Collection;
import java.util.List;

public final class RepositoryTestLibrary implements TestLibrary {
  public RepositoryTestLibrary(String... coordinates) {
    this(DependencyScope.COMPILE, coordinates);
  }

  public RepositoryTestLibrary(String coordinates, DependencyScope dependencyScope) {
    this(dependencyScope, coordinates);
  }

  private RepositoryTestLibrary(DependencyScope dependencyScope, String... coordinates) {
    assert coordinates.length > 0;
    myCoordinates = coordinates;
    myDependencyScope = dependencyScope;
  }

  @Override
  public void addTo(@NotNull Module module, @NotNull ModifiableRootModel model) {
    final LibraryTable.ModifiableModel tableModel = model.getModuleLibraryTable().getModifiableModel();
    Library library = tableModel.createLibrary(myCoordinates[0]);
    final Library.ModifiableModel libraryModel = library.getModifiableModel();

    for (String coordinates : myCoordinates) {
      Collection<OrderRoot> roots = loadRoots(module.getProject(), coordinates);
      for (OrderRoot root : roots) {
        libraryModel.addRoot(root.getFile(), root.getType());
      }
    }

    WriteAction.runAndWait(() -> {
      libraryModel.commit();
      tableModel.commit();
    });

    model.findLibraryOrderEntry(library).setScope(myDependencyScope);
  }

  public String[] getCoordinates() {
    return myCoordinates;
  }

  public static Collection<OrderRoot> loadRoots(Project project, String coordinates, RemoteRepositoryDescription... additional) {
    Collection<OrderRoot> resolved = loadResolvedRoots(coordinates);
    if (resolved != null) return resolved;
    RepositoryLibraryProperties libraryProperties = new RepositoryLibraryProperties(coordinates, true);
    Collection<OrderRoot> roots = JarRepositoryManager.loadDependenciesModal(project, libraryProperties, false, false, null,
                                                                             ContainerUtil.append(getRemoteRepositoryDescriptions(), additional));
    UsefulTestCase.assertNotEmpty(roots);
    return roots;
  }

  // Phantom-plugins: IntelliJ resolves the libraries from its monorepo's repositories (IntelliJProjectConfiguration reads
  // them from .idea); here Gradle resolves the ones the tests use (build.gradle.kts, testLibraryCoordinates) and lists their
  // jars in the file the groovy.test.libraries system property names. Others still go to Maven Central.
  private static List<RemoteRepositoryDescription> getRemoteRepositoryDescriptions() {
    return List.of(RemoteRepositoryDescription.MAVEN_CENTRAL);
  }

  private static Collection<OrderRoot> loadResolvedRoots(String coordinates) {
    String librariesFile = System.getProperty("groovy.test.libraries");
    if (librariesFile == null) return null;
    java.util.Properties libraries = new java.util.Properties();
    try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(librariesFile))) {
      libraries.load(reader);
    }
    catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    String jars = libraries.getProperty(coordinates);
    if (jars == null) return null;
    List<OrderRoot> roots = new java.util.ArrayList<>();
    for (String jar : jars.split(java.io.File.pathSeparator)) {
      com.intellij.openapi.vfs.VirtualFile file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(jar);
      UsefulTestCase.assertNotNull(jar, file);
      roots.add(new OrderRoot(com.intellij.openapi.vfs.JarFileSystem.getInstance().getJarRootForLocalFile(file),
                              com.intellij.openapi.roots.OrderRootType.CLASSES));
    }
    return roots;
  }

  private final String[] myCoordinates;
  private final DependencyScope myDependencyScope;
}
