// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.java.decompiler.roundTrip.fixtures.java;

import org.jetbrains.java.decompiler.roundTrip.fixtures.Compiler;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.support.ParameterDeclarations;

import java.util.stream.Stream;

@NullMarked
public final class JavaCompilerArgumentsProvider implements ArgumentsProvider {
  @Override
  public Stream<? extends Arguments> provideArguments(ParameterDeclarations parameters, ExtensionContext context) {
    // Phantom-plugins: the tests run on the IDE's runtime, which has no javac; ECJ covers them there
    if (javax.tools.ToolProvider.getSystemJavaCompiler() == null) {
      return Stream.of(Arguments.of(Compiler.ECJ));
    }
    return Stream.of(Arguments.of(Compiler.JAVAC), Arguments.of(Compiler.ECJ));
  }
}
